import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { SubscribeResult } from '../src/protocol/frames';
import type { Session, SessionEvent } from '../src/protocol/types';

const rpc = vi.fn();
const subscribe = vi.fn();
const unsubscribe = vi.fn();
const updateCursor = vi.fn();

vi.mock('../src/lib/gateway', () => ({
  rpc: (...args: unknown[]) => rpc(...args),
  getSocket: () => ({ subscribe, unsubscribe, updateCursor }),
  setSocket: vi.fn(),
}));

const { useChat } = await import('../src/stores/chat');
const { useSessions } = await import('../src/stores/sessions');
const { useOutbox } = await import('../src/stores/outbox');
const { RequestError } = await import('../src/lib/ws');

const DEVICE = 'dev-1';
const SESSION = 'ses-1';
const KEY = `${DEVICE}/${SESSION}`;

const session: Session = {
  session_id: SESSION,
  device_id: DEVICE,
  agent: 'claude',
  title: 'Fix flaky auth test',
  cwd: '/Users/me/dev/gateway',
  git: null,
  state: 'running',
  state_detail: null,
  origin: 'remote',
  control: 'remote',
  model: 'claude-sonnet-4-5',
  permission_mode: 'default',
  effort: 'high',
  created_at: 1,
  updated_at: 2,
  last_seq: 0,
  archived: false,
  turn: null,
  todos: { total: 4, done: 1 },
  usage: null,
  queued: 2,
};

const todosEvent = (seq: number, done: number): SessionEvent =>
  ({
    seq,
    ts: 1_000 + seq,
    kind: 'todos',
    items: [
      { id: 't1', text: 'Reproduce', status: done > 0 ? 'completed' : 'pending' },
      { id: 't2', text: 'Isolate the clock', status: done > 1 ? 'completed' : 'in_progress' },
      { id: 't3', text: 'Re-run the suite', status: 'pending' },
    ],
  }) as SessionEvent;

const queueEvent = (seq: number, ids: string[]): SessionEvent =>
  ({
    seq,
    ts: 1_000 + seq,
    kind: 'queue',
    pending: ids.map((id) => ({ id, text: `queued ${id}`, ts: 5 })),
  }) as SessionEvent;

const userMessage = (seq: number, blockId: string, text: string): SessionEvent =>
  ({ seq, ts: 1_000 + seq, kind: 'user_message', block_id: blockId, text, source: 'remote' }) as SessionEvent;

const textEvent = (seq: number, text: string): SessionEvent =>
  ({ seq, ts: 1_000 + seq, kind: 'assistant_text', block_id: `b${seq}`, text, done: true }) as SessionEvent;

/** Runs `open()` and hands back the subscribe handlers the store registered. */
function openSession(): {
  onResult: (result: SubscribeResult) => void;
  onError: (err: unknown) => void;
} {
  useChat.getState().open(DEVICE, SESSION);
  const call = subscribe.mock.calls.at(-1);
  if (!call) throw new Error('open() did not subscribe');
  return call[2] as ReturnType<typeof openSession>;
}

const chat = () => useChat.getState().sessions[KEY];
const summary = () => useSessions.getState().sessions[KEY];

beforeEach(() => {
  rpc.mockReset();
  subscribe.mockReset();
  unsubscribe.mockReset();
  updateCursor.mockReset();
  useChat.setState({ sessions: {} });
  useSessions.setState({ sessions: { [KEY]: { ...session } }, loaded: true });
  useOutbox.setState({ pending: {} });
  rpc.mockResolvedValue({ events: [], has_more: false });
});

describe('subscribe replay', () => {
  it('applies todos and queue snapshots carried by buffered events', () => {
    const handlers = openSession();
    handlers.onResult({
      session,
      resync: false,
      events: [todosEvent(10, 1), queueEvent(11, ['q1', 'q2']), textEvent(12, 'hello')],
    });

    expect(chat()?.todos).toHaveLength(3);
    expect(chat()?.todos[0]?.status).toBe('completed');
    expect(chat()?.queue.map((q) => q.id)).toEqual(['q1', 'q2']);
    // The timeline still drops the state-only kinds.
    expect(chat()?.timeline.order).toEqual(['b12']);
    expect(chat()?.ready).toBe(true);
  });

  it('folds the session summary once from the replayed events', () => {
    const handlers = openSession();
    handlers.onResult({
      session,
      resync: false,
      events: [
        todosEvent(10, 2),
        queueEvent(11, ['q1']),
        { seq: 12, ts: 12, kind: 'status', state: 'needs_approval', detail: 'waiting' } as SessionEvent,
      ],
    });

    expect(summary()?.todos).toEqual({ total: 3, done: 2 });
    expect(summary()?.queued).toBe(1);
    expect(summary()?.state).toBe('needs_approval');
    expect(summary()?.state_detail).toBe('waiting');
  });

  it('prefers the explicit A6 queue snapshot over replayed queue events', () => {
    const handlers = openSession();
    handlers.onResult({
      session,
      resync: false,
      events: [queueEvent(10, ['stale'])],
      queue: { pending: [{ id: 'fresh', text: 'from the gateway', ts: 9 }] },
    });

    expect(chat()?.queue.map((q) => q.id)).toEqual(['fresh']);
  });

  it('accepts a subscribe reply without the optional queue field', () => {
    const handlers = openSession();
    handlers.onResult({ session, resync: false, events: [queueEvent(10, ['q1'])] });
    expect(chat()?.queue.map((q) => q.id)).toEqual(['q1']);
  });

  it('resets the timeline on resync and then pages history', async () => {
    const handlers = openSession();
    handlers.onResult({ session, resync: false, events: [textEvent(5, 'old')] });
    expect(chat()?.timeline.order).toEqual(['b5']);

    rpc.mockResolvedValueOnce({ events: [textEvent(20, 'replayed')], has_more: false });
    handlers.onResult({ session, resync: true, events: [] });

    expect(chat()?.timeline.order).toEqual([]);
    expect(chat()?.todos).toEqual([]);
    await vi.waitFor(() => expect(chat()?.timeline.order).toEqual(['b20']));
    expect(rpc).toHaveBeenCalledWith(
      'session.history',
      expect.objectContaining({ session_id: SESSION }),
    );
  });

  it('records a subscribe failure without wedging the view', () => {
    const handlers = openSession();
    handlers.onError(new Error('device_offline'));
    expect(chat()?.ready).toBe(true);
    expect(chat()?.error).toBe('device_offline');
  });
});

describe('history paging', () => {
  it('applies the todos snapshot from the newest page', async () => {
    const handlers = openSession();
    rpc.mockResolvedValueOnce({
      events: [todosEvent(3, 1), textEvent(4, 'first answer')],
      has_more: true,
    });
    handlers.onResult({ session, resync: false, events: [] });

    await vi.waitFor(() => expect(chat()?.timeline.order).toEqual(['b4']));
    expect(chat()?.todos).toHaveLength(3);
    expect(summary()?.todos).toEqual({ total: 3, done: 1 });
  });

  it('never lets an older page roll the todos snapshot back', async () => {
    const handlers = openSession();
    rpc.mockResolvedValueOnce({ events: [todosEvent(30, 2), textEvent(31, 'newest')], has_more: true });
    handlers.onResult({ session, resync: false, events: [] });
    await vi.waitFor(() => expect(chat()?.todos[1]?.status).toBe('completed'));

    rpc.mockResolvedValueOnce({ events: [todosEvent(2, 0), textEvent(3, 'oldest')], has_more: false });
    await useChat.getState().loadOlder(KEY);

    expect(chat()?.timeline.order).toEqual(['b3', 'b31']);
    // Still the newer snapshot: two done, not zero.
    expect(chat()?.todos[0]?.status).toBe('completed');
    expect(chat()?.todos[1]?.status).toBe('completed');
    expect(chat()?.historyHasMore).toBe(false);
  });

  it('sends before_seq only once an older page is requested', async () => {
    const handlers = openSession();
    rpc.mockResolvedValueOnce({ events: [textEvent(40, 'newest')], has_more: true });
    handlers.onResult({ session, resync: false, events: [] });
    await vi.waitFor(() => expect(chat()?.timeline.order).toEqual(['b40']));

    const firstCall = rpc.mock.calls.find((c) => c[0] === 'session.history');
    expect(firstCall?.[1]).not.toHaveProperty('before_seq');

    rpc.mockResolvedValueOnce({ events: [], has_more: false });
    await useChat.getState().loadOlder(KEY);
    const secondCall = rpc.mock.calls.filter((c) => c[0] === 'session.history').at(-1);
    expect(secondCall?.[1]).toMatchObject({ before_seq: 40 });
  });
});

describe('live events', () => {
  it('folds todos and queue and advances the socket cursor', () => {
    const handlers = openSession();
    handlers.onResult({ session, resync: false, events: [] });

    useChat.getState().ingestEvent(SESSION, todosEvent(7, 1), DEVICE);
    useChat.getState().ingestEvent(SESSION, queueEvent(8, ['q9']), DEVICE);

    expect(chat()?.todos).toHaveLength(3);
    expect(chat()?.queue.map((q) => q.id)).toEqual(['q9']);
    expect(updateCursor).toHaveBeenLastCalledWith(SESSION, 8);
  });

  it('routes by session_id whether or not device_id is stamped (A5)', () => {
    const handlers = openSession();
    handlers.onResult({ session, resync: false, events: [] });

    useChat.getState().ingestEvent(SESSION, textEvent(2, 'without device'));
    expect(chat()?.timeline.order).toEqual(['b2']);

    useChat.getState().ingestEvent(SESSION, textEvent(3, 'with device'), DEVICE);
    expect(chat()?.timeline.order).toEqual(['b2', 'b3']);

    // A device_id that does not match still resolves through session_id.
    useChat.getState().ingestEvent(SESSION, textEvent(4, 'other device'), 'dev-other');
    expect(chat()?.timeline.order).toEqual(['b2', 'b3', 'b4']);
  });

  it('drops an event whose seq was already applied', () => {
    const handlers = openSession();
    handlers.onResult({ session, resync: false, events: [textEvent(9, 'kept')] });
    useChat.getState().ingestEvent(SESSION, textEvent(9, 'duplicate'));
    useChat.getState().ingestEvent(SESSION, textEvent(4, 'late'));
    expect(chat()?.timeline.order).toEqual(['b9']);
  });

  it('ignores events for a session that is not open', () => {
    useChat.getState().ingestEvent('other-session', textEvent(1, 'nope'));
    expect(useChat.getState().sessions).toEqual({});
  });
});

describe('sending', () => {
  it('clears the outbox once the gateway confirms', async () => {
    const handlers = openSession();
    handlers.onResult({ session, resync: false, events: [] });
    rpc.mockResolvedValueOnce({ accepted: 'queued', queued_id: 'q1' });

    await useChat.getState().send(KEY, { text: 'hello', mode: 'auto' });

    expect(rpc).toHaveBeenCalledWith(
      'session.send',
      expect.objectContaining({ session_id: SESSION, text: 'hello', mode: 'auto' }),
      expect.objectContaining({ id: expect.any(String) }),
    );
    expect(useOutbox.getState().pending).toEqual({});
  });

  it('drops a definitely-rejected message and rethrows', async () => {
    const handlers = openSession();
    handlers.onResult({ session, resync: false, events: [] });
    rpc.mockRejectedValueOnce(new RequestError({ code: 'conflict', message: 'terminal' }));

    await expect(
      useChat.getState().send(KEY, { text: 'nope', mode: 'auto' }),
    ).rejects.toBeInstanceOf(RequestError);
    expect(useOutbox.getState().pending).toEqual({});
  });

  it('keeps an uncertain send for a manual retry with the same request id', async () => {
    const handlers = openSession();
    handlers.onResult({ session, resync: false, events: [] });
    rpc.mockRejectedValueOnce(new RequestError({ code: 'timeout', message: 'no reply' }));

    await useChat.getState().send(KEY, { text: 'maybe', mode: 'auto' });
    const entries = Object.values(useOutbox.getState().pending);
    expect(entries).toHaveLength(1);
    expect(entries[0]?.error).toBe('no reply');
    const id = entries[0]?.id as string;

    rpc.mockResolvedValueOnce({ accepted: 'sent' });
    await useChat.getState().retrySend(id);

    const sends = rpc.mock.calls.filter((c) => c[0] === 'session.send');
    expect(sends).toHaveLength(2);
    expect((sends[0]?.[2] as { id: string }).id).toBe(id);
    expect((sends[1]?.[2] as { id: string }).id).toBe(id);
    expect(useOutbox.getState().pending).toEqual({});
  });

  it('shows the message before the request is answered, under the request id', async () => {
    const handlers = openSession();
    handlers.onResult({ session, resync: false, events: [] });

    let release = (_: unknown) => {};
    rpc.mockImplementationOnce(() => new Promise((resolve) => (release = resolve)));
    const sending = useChat.getState().send(KEY, { text: 'run the tests', mode: 'auto' });

    // Synchronously, before the gateway has replied.
    const optimistic = chat()?.timeline.optimistic ?? [];
    expect(optimistic).toHaveLength(1);
    expect(optimistic[0]?.text).toBe('run the tests');
    const id = optimistic[0]?.id as string;
    expect(rpc.mock.calls.at(-1)?.[2]).toMatchObject({ id });
    // It is not a device event, so the replay cursor stays where it was.
    expect(chat()?.timeline.lastSeq).toBe(0);

    release({ accepted: 'sent' });
    await sending;
    expect(chat()?.timeline.optimistic).toHaveLength(1);

    // A12: the device echoes the request id, so the row is replaced in place.
    useChat.getState().ingestEvent(SESSION, userMessage(3, id, 'run the tests'), DEVICE);
    expect(chat()?.timeline.optimistic).toEqual([]);
    expect(chat()?.timeline.order).toEqual([id]);
  });

  it('records the attachment metadata on the optimistic row', async () => {
    const handlers = openSession();
    handlers.onResult({ session, resync: false, events: [] });
    rpc.mockResolvedValueOnce({ accepted: 'sent' });

    await useChat.getState().send(KEY, {
      text: 'look at this',
      mode: 'auto',
      attachments: [{ name: 'shot.png', mime: 'image/png', data_base64: 'AAAA' }],
    });

    expect(chat()?.timeline.optimistic[0]?.attachments).toEqual([
      { name: 'shot.png', mime: 'image/png', size: 3 },
    ]);
  });

  it('gives the row up to the queue when the device queues the message', async () => {
    const handlers = openSession();
    handlers.onResult({ session, resync: false, events: [] });
    rpc.mockResolvedValueOnce({ accepted: 'queued', queued_id: 'q-1' });

    await useChat.getState().send(KEY, { text: 'and then lint', mode: 'auto' });
    // The queue row above the composer stands for it from here on.
    expect(chat()?.timeline.optimistic).toEqual([]);
  });

  /**
   * A19: on a shared session a message sent into a running terminal turn is a
   * queue entry and nothing else. The block arrives when the CLI takes it, with
   * a `first_seq` after the output of the turn it waited for.
   */
  it('holds a message sent into a running terminal turn in the queue alone', async () => {
    useSessions.setState({
      sessions: { [KEY]: { ...session, control: 'shared', origin: 'terminal' } },
      loaded: true,
    });
    const handlers = openSession();
    handlers.onResult({ session, resync: false, events: [] });
    useChat.getState().ingestEvent(SESSION, textEvent(10, 'Working on the refactor.'), DEVICE);

    rpc.mockResolvedValueOnce({ accepted: 'queued', queued_id: 'q-held' });
    await useChat.getState().send(KEY, { text: 'and then lint', mode: 'auto' });
    const id = (rpc.mock.calls.at(-1)?.[2] as { id: string }).id;

    useChat.getState().ingestEvent(SESSION, queueEvent(11, [id]), DEVICE);
    expect(chat()?.queue.map((q) => q.id)).toEqual([id]);
    // No bubble anywhere: not optimistic, not a block.
    expect(chat()?.timeline.optimistic).toEqual([]);
    expect(chat()?.timeline.order).toEqual(['b10']);

    // The CLI took it when the turn ended.
    useChat.getState().ingestEvent(SESSION, queueEvent(12, []), DEVICE);
    useChat.getState().ingestEvent(
      SESSION,
      {
        seq: 14,
        ts: 1_014,
        kind: 'user_message',
        first_seq: 13,
        block_id: id,
        text: 'and then lint',
        source: 'remote',
        delivery: 'delivered',
      } as SessionEvent,
      DEVICE,
    );
    expect(chat()?.queue).toEqual([]);
    expect(chat()?.timeline.order).toEqual(['b10', id]);
  });

  it('gives the row up to a queue snapshot that names it, after an uncertain send', async () => {
    const handlers = openSession();
    handlers.onResult({ session, resync: false, events: [] });
    rpc.mockRejectedValueOnce(new RequestError({ code: 'timeout', message: 'no reply' }));

    await useChat.getState().send(KEY, { text: 'and then lint', mode: 'auto' });
    const id = chat()?.timeline.optimistic[0]?.id as string;

    // The device did get it: its snapshot names the request id (A12).
    useChat.getState().ingestEvent(SESSION, queueEvent(4, [id]), DEVICE);
    expect(chat()?.queue.map((q) => q.id)).toEqual([id]);
    expect(chat()?.timeline.optimistic).toEqual([]);
  });

  it('stops offering a Retry for a message removed from the queue', async () => {
    const handlers = openSession();
    handlers.onResult({ session, resync: false, events: [] });
    rpc.mockRejectedValueOnce(new RequestError({ code: 'timeout', message: 'no reply' }));
    await useChat.getState().send(KEY, { text: 'never mind', mode: 'auto' });
    const id = Object.keys(useOutbox.getState().pending)[0] as string;

    rpc.mockResolvedValueOnce({});
    await useChat.getState().removeQueued(KEY, id);
    expect(useOutbox.getState().pending).toEqual({});
  });

  it('removes the row when the gateway definitely refuses the message', async () => {
    const handlers = openSession();
    handlers.onResult({ session, resync: false, events: [] });
    rpc.mockRejectedValueOnce(new RequestError({ code: 'conflict', message: 'terminal' }));

    await expect(
      useChat.getState().send(KEY, { text: 'nope', mode: 'auto' }),
    ).rejects.toBeInstanceOf(RequestError);
    expect(chat()?.timeline.optimistic).toEqual([]);
  });

  it('keeps the row when delivery is merely uncertain, and Retry reuses it', async () => {
    const handlers = openSession();
    handlers.onResult({ session, resync: false, events: [] });
    rpc.mockRejectedValueOnce(new RequestError({ code: 'timeout', message: 'no reply' }));

    await useChat.getState().send(KEY, { text: 'maybe', mode: 'auto' });
    const id = chat()?.timeline.optimistic[0]?.id as string;
    expect(id).toBeTruthy();

    rpc.mockResolvedValueOnce({ accepted: 'sent' });
    await useChat.getState().retrySend(id);
    expect(chat()?.timeline.optimistic.map((b) => b.id)).toEqual([id]);
  });

  it('keeps the row across a resync and drops it once history confirms it', async () => {
    const handlers = openSession();
    handlers.onResult({ session, resync: false, events: [] });
    rpc.mockResolvedValueOnce({ accepted: 'sent' });
    await useChat.getState().send(KEY, { text: 'survive the reconnect', mode: 'auto' });
    const id = chat()?.timeline.optimistic[0]?.id as string;

    // The resync empties the timeline and pages history again.
    rpc.mockResolvedValueOnce({
      events: [userMessage(7, id, 'survive the reconnect')],
      has_more: false,
    });
    handlers.onResult({ session, resync: true, events: [] });
    expect(chat()?.timeline.order).toEqual([]);
    expect(chat()?.timeline.optimistic.map((b) => b.id)).toEqual([id]);

    await vi.waitFor(() => expect(chat()?.timeline.order).toEqual([id]));
    expect(chat()?.timeline.optimistic).toEqual([]);
  });

  it('forwards attachments only when there are any', async () => {
    const handlers = openSession();
    handlers.onResult({ session, resync: false, events: [] });
    rpc.mockResolvedValue({ accepted: 'sent' });

    await useChat.getState().send(KEY, { text: 'bare', mode: 'auto' });
    expect(rpc.mock.calls.at(-1)?.[1]).not.toHaveProperty('attachments');

    await useChat.getState().send(KEY, {
      text: 'with file',
      mode: 'interrupt',
      attachments: [{ name: 'a.png', mime: 'image/png', data_base64: 'AA==' }],
    });
    expect(rpc.mock.calls.at(-1)?.[1]).toMatchObject({
      mode: 'interrupt',
      attachments: [{ name: 'a.png', mime: 'image/png', data_base64: 'AA==' }],
    });
  });
});
