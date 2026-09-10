import { describe, expect, it } from 'vitest';
import {
  applyEvent,
  applyEvents,
  emptyTimeline,
  mergeHistory,
  replaceBlock,
  selectView,
} from '../src/stores/timeline';
import type { SessionEvent } from '../src/protocol/types';
import { fixturesAvailable, readFixture } from './fixtures';

const text = (seq: number, delta: string, done = false): SessionEvent =>
  ({ seq, ts: 1_000 + seq, kind: 'assistant_text', block_id: 'b1', delta, done }) as SessionEvent;

const tool = (seq: number, status: 'running' | 'succeeded', output: string): SessionEvent =>
  ({
    seq,
    ts: 2_000 + seq,
    kind: 'tool_call',
    block_id: 't1',
    tool: 'Bash',
    tool_kind: 'shell',
    title: 'pytest',
    status,
    started_at: 2_000,
    output,
  }) as SessionEvent;

describe('timeline reducer', () => {
  it('appends streaming deltas into one block', () => {
    let state = emptyTimeline();
    state = applyEvent(state, text(1, 'Hello '));
    state = applyEvent(state, text(2, 'world'));
    expect(state.order).toEqual(['b1']);
    const event = state.items.b1?.event;
    expect(event?.kind).toBe('assistant_text');
    expect(event && 'text' in event ? event.text : null).toBe('Hello world');
    expect(state.lastSeq).toBe(2);
  });

  it('replaces the block when a final event carries the full text', () => {
    let state = emptyTimeline();
    state = applyEvent(state, text(1, 'partial'));
    state = applyEvent(state, {
      seq: 2,
      ts: 1_002,
      kind: 'assistant_text',
      block_id: 'b1',
      text: 'the complete answer',
      done: true,
    } as SessionEvent);
    const event = state.items.b1?.event;
    expect(event && 'text' in event ? event.text : null).toBe('the complete answer');
    expect(event && 'done' in event ? event.done : null).toBe(true);
    expect(state.order).toHaveLength(1);
  });

  it('fully replaces a tool_call block and keeps its original position', () => {
    let state = emptyTimeline();
    state = applyEvent(state, text(1, 'before'));
    state = applyEvent(state, tool(2, 'running', 'partial output'));
    state = applyEvent(state, text(3, ' after'));
    state = applyEvent(state, tool(4, 'succeeded', 'final output'));
    expect(state.order).toEqual(['b1', 't1']);
    const event = state.items.t1?.event;
    expect(event?.kind === 'tool_call' && event.status).toBe('succeeded');
    expect(event?.kind === 'tool_call' && event.output).toBe('final output');
    expect(state.items.t1?.seq).toBe(2);
  });

  it('drops late and duplicate frames by seq', () => {
    let state = emptyTimeline();
    state = applyEvent(state, text(5, 'kept'));
    const before = state;
    state = applyEvent(state, text(5, 'duplicate'));
    state = applyEvent(state, text(3, 'late'));
    expect(state).toBe(before);
    const event = state.items.b1?.event;
    expect(event && 'text' in event ? event.text : null).toBe('kept');
  });

  it('keeps state-only kinds out of the timeline', () => {
    let state = emptyTimeline();
    state = applyEvents(state, [
      { seq: 1, ts: 1, kind: 'status', state: 'running' },
      { seq: 2, ts: 2, kind: 'meta', title: 'Renamed' },
      { seq: 3, ts: 3, kind: 'queue', pending: [] },
      { seq: 4, ts: 4, kind: 'todos', items: [] },
      { seq: 5, ts: 5, kind: 'turn_started', turn_id: 'x', trigger: 'remote' },
      { seq: 6, ts: 6, kind: 'turn_completed', turn_id: 'x', stop_reason: 'completed', duration_ms: 1 },
    ] as SessionEvent[]);
    expect(state.order).toEqual([]);
    expect(state.lastSeq).toBe(6);
  });

  it('renders interrupted turns but not clean ones', () => {
    let state = emptyTimeline();
    state = applyEvent(state, {
      seq: 1,
      ts: 1,
      kind: 'turn_completed',
      turn_id: 'x',
      stop_reason: 'interrupted',
      duration_ms: 5,
    } as SessionEvent);
    expect(state.order).toHaveLength(1);
  });

  it('prepends an older history page without disturbing live blocks', () => {
    let state = emptyTimeline();
    state = applyEvent(state, text(10, 'live'));
    state = mergeHistory(state, [
      { seq: 4, ts: 4, kind: 'user_message', block_id: 'h1', text: 'older', source: 'remote' },
      { seq: 5, ts: 5, kind: 'assistant_text', block_id: 'h2', text: 'reply', done: true },
    ] as SessionEvent[]);
    expect(state.order).toEqual(['h1', 'h2', 'b1']);
    expect(state.oldestSeq).toBe(4);
    expect(state.lastSeq).toBe(10);
  });

  it('never lets a history page overwrite a fresher live block', () => {
    let state = emptyTimeline();
    state = applyEvent(state, tool(9, 'succeeded', 'final output'));
    state = mergeHistory(state, [tool(2, 'running', 'stale output')]);
    const event = state.items.t1?.event;
    expect(event?.kind === 'tool_call' && event.output).toBe('final output');
    expect(state.order).toEqual(['t1']);
  });

  it('nests events that carry parent_block_id under their parent row', () => {
    let state = emptyTimeline();
    state = applyEvent(state, {
      seq: 1,
      ts: 1,
      kind: 'tool_call',
      block_id: 'parent',
      tool: 'Task',
      tool_kind: 'subagent',
      title: 'Audit clocks',
      status: 'running',
      started_at: 1,
    } as SessionEvent);
    state = applyEvent(state, {
      seq: 2,
      ts: 2,
      kind: 'assistant_text',
      block_id: 'child',
      parent_block_id: 'parent',
      text: 'sub-agent said this',
      done: true,
    } as SessionEvent);
    const view = selectView(state);
    expect(view.roots.map((i) => i.key)).toEqual(['parent']);
    expect(view.children.parent?.map((i) => i.key)).toEqual(['child']);
  });

  it('swaps in the untruncated block returned by session.block', () => {
    let state = emptyTimeline();
    state = applyEvent(state, tool(1, 'succeeded', 'short'));
    state = replaceBlock(state, tool(1, 'succeeded', 'the whole thing'));
    const event = state.items.t1?.event;
    expect(event?.kind === 'tool_call' && event.output).toBe('the whole thing');
  });
});

describe('block ordering (amendment A8)', () => {
  const block = (
    id: string,
    seq: number,
    options: { firstSeq?: number; kind?: 'tool_call' | 'assistant_text' } = {},
  ): SessionEvent => {
    const base = {
      seq,
      ts: 3_000 + seq,
      block_id: id,
      ...(options.firstSeq === undefined ? {} : { first_seq: options.firstSeq }),
    };
    if (options.kind === 'assistant_text' || options.kind === undefined) {
      return { ...base, kind: 'assistant_text', text: id, done: true } as SessionEvent;
    }
    return {
      ...base,
      kind: 'tool_call',
      tool: 'Bash',
      tool_kind: 'shell',
      title: 'pytest --count 100',
      status: 'succeeded',
      started_at: 3_000,
    } as SessionEvent;
  };

  it('keeps a late-finishing tool call where it started', () => {
    let state = emptyTimeline();
    state = applyEvent(state, block('tool', 5, { kind: 'tool_call', firstSeq: 5 }));
    state = applyEvent(state, block('after', 6));
    // The tool finishes long after the message that followed it started.
    state = applyEvent(state, block('tool', 40, { kind: 'tool_call', firstSeq: 5 }));
    expect(state.order).toEqual(['tool', 'after']);
    expect(state.items.tool?.seq).toBe(5);
    expect(state.lastSeq).toBe(40);
  });

  it('places a history block by first_seq, not by the seq of its latest event', () => {
    let state = emptyTimeline();
    // A reload: history returns only the newest event per block, ascending.
    state = mergeHistory(state, [
      block('early', 3),
      block('mid', 7),
      block('tool', 40, { kind: 'tool_call', firstSeq: 5 }),
    ]);
    expect(state.order).toEqual(['early', 'tool', 'mid']);
    expect(state.items.tool?.seq).toBe(5);
  });

  it('falls back to seq when the device omits first_seq', () => {
    let state = emptyTimeline();
    state = mergeHistory(state, [block('a', 3), block('b', 7), block('c', 40)]);
    expect(state.order).toEqual(['a', 'b', 'c']);
    expect(state.items.c?.seq).toBe(40);
  });

  it('moves a row back when a replacement reveals an earlier first_seq', () => {
    let state = emptyTimeline();
    // Joined mid-turn: the first event seen for this block has no first_seq.
    state = applyEvent(state, block('joined', 30, { kind: 'tool_call' }));
    state = applyEvent(state, block('later', 31));
    expect(state.order).toEqual(['joined', 'later']);

    state = applyEvent(state, block('joined', 32, { kind: 'tool_call', firstSeq: 2 }));
    expect(state.order).toEqual(['joined', 'later']);
    expect(state.items.joined?.seq).toBe(2);

    // A block that started before it now sorts ahead of it.
    state = mergeHistory(state, [block('oldest', 1)]);
    expect(state.order).toEqual(['oldest', 'joined', 'later']);
  });

  it('never lets first_seq push a block past one that started earlier', () => {
    let state = emptyTimeline();
    state = applyEvent(state, block('a', 10, { firstSeq: 10 }));
    state = applyEvent(state, block('b', 11, { firstSeq: 4 }));
    state = applyEvent(state, block('c', 12, { firstSeq: 40 }));
    expect(state.order).toEqual(['b', 'a', 'c']);
  });

  it('interleaves a prepended page with existing rows by ordering seq', () => {
    let state = emptyTimeline();
    state = applyEvent(state, block('live-1', 50, { firstSeq: 50 }));
    state = applyEvent(state, block('live-2', 51, { firstSeq: 51 }));
    state = mergeHistory(state, [
      block('h1', 10),
      block('slow', 49, { kind: 'tool_call', firstSeq: 20 }),
      block('h2', 30),
    ]);
    expect(state.order).toEqual(['h1', 'slow', 'h2', 'live-1', 'live-2']);
    expect(state.oldestSeq).toBe(10);
  });

  it('ignores first_seq on a block the timeline does not render', () => {
    let state = emptyTimeline();
    state = applyEvent(state, {
      seq: 9,
      ts: 9,
      kind: 'todos',
      first_seq: 1,
      items: [],
    } as SessionEvent);
    expect(state.order).toEqual([]);
    expect(state.lastSeq).toBe(9);
  });
});

interface TimelineFixture {
  name: string;
  frames: { type: string; session_id: string; event: SessionEvent }[];
  session_final: { last_seq: number; state: string };
}

describe.runIf(fixturesAvailable())('protocol timeline fixtures', () => {
  for (const file of ['timelines/claude.json', 'timelines/codex.json']) {
    it(`replays ${file} into a coherent timeline`, () => {
      const fixture = readFixture<TimelineFixture>(file);
      let state = emptyTimeline();
      for (const frame of fixture.frames) {
        expect(frame.type).toBe('session.event');
        state = applyEvent(state, frame.event);
      }

      expect(state.lastSeq).toBe(fixture.session_final.last_seq);
      expect(state.order.length).toBeGreaterThan(0);

      // Every rendered block resolves to an item, and nothing streams a delta.
      for (const key of state.order) {
        const item = state.items[key];
        expect(item).toBeDefined();
        const event = item?.event;
        if (event && (event.kind === 'assistant_text' || event.kind === 'thinking')) {
          expect(event.delta).toBeUndefined();
          expect(typeof event.text).toBe('string');
        }
      }

      // Streaming text was concatenated, not duplicated per delta.
      const deltas = fixture.frames.filter(
        (f) => 'delta' in f.event && f.event.delta !== undefined,
      );
      const textBlocks = new Set(
        deltas.map((f) => ('block_id' in f.event ? f.event.block_id : '')),
      );
      for (const blockId of textBlocks) {
        if (!blockId) continue;
        expect(state.items[blockId], `block ${blockId} missing`).toBeDefined();
      }

      // Sub-agent children hang off their parent tool row.
      const view = selectView(state);
      const parents = view.roots.filter(
        (i) => i.event.kind === 'tool_call' && i.event.tool_kind === 'subagent',
      );
      for (const parent of parents) {
        const children = view.children[parent.key] ?? [];
        for (const child of children) expect(child.parentBlockId).toBe(parent.key);
      }
    });
  }

  it('merges the history page fixture ahead of the live tail', () => {
    const page = readFixture<{ result: { events: SessionEvent[]; has_more: boolean } }>(
      'history/page.json',
    );
    const events = page.result.events;
    expect(events.length).toBeGreaterThan(0);

    const newest = events[events.length - 1];
    let state = emptyTimeline();
    state = applyEvent(state, {
      seq: (newest?.seq ?? 0) + 100,
      ts: Date.now(),
      kind: 'assistant_text',
      block_id: 'live-tail',
      text: 'live',
      done: true,
    } as SessionEvent);
    state = mergeHistory(state, events);

    expect(state.order.at(-1)).toBe('live-tail');
    expect(state.oldestSeq).toBe(events[0]?.seq);
    // History never contains streaming deltas or state-only kinds.
    for (const event of events) {
      expect(['status', 'meta', 'queue']).not.toContain(event.kind);
      expect('delta' in event ? event.delta : undefined).toBeUndefined();
    }
  });
});
