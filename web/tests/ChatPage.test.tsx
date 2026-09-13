/**
 * `docs/DESIGN.md` § "The composer": every change made from the model card is
 * drawn the moment it is made, and the device's reply confirms it or a refusal
 * puts the previous value back. The card never waits for the round trip.
 */
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { ChatPage } from '../src/features/chat/ChatPage';
import { useChat } from '../src/stores/chat';
import { useConnection } from '../src/stores/connection';
import { useDevices } from '../src/stores/devices';
import { useOutbox } from '../src/stores/outbox';
import { keyOf, useSessions } from '../src/stores/sessions';
import { strings } from '../src/strings';
import { codexAgent, devices as deviceFixtures } from '../mock/fixtures';
import type { Session } from '../src/protocol/types';

const { rpcMock } = vi.hoisted(() => ({ rpcMock: vi.fn() }));

// jsdom has no scrolling, and the timeline scrolls itself to the latest row on
// mount. Stubbed the way `Timeline.test.tsx` stubs it.
const nativeScrollTo = HTMLElement.prototype.scrollTo;

beforeAll(() => {
  HTMLElement.prototype.scrollTo = function scrollToStub(this: HTMLElement) {
    // Nothing to scroll: this suite is about the composer, not the timeline.
  } as HTMLElement['scrollTo'];
});

afterAll(() => {
  HTMLElement.prototype.scrollTo = nativeScrollTo;
});

vi.mock('../src/lib/gateway', () => ({
  rpc: rpcMock,
  getSocket: () => null,
  setSocket: () => undefined,
}));

const device = { ...deviceFixtures[0]!, device_id: 'dev-mac', agents: [codexAgent] };

const session: Session = {
  session_id: 'ses-card',
  device_id: 'dev-mac',
  agent: 'codex',
  title: 'Tidy the parser',
  cwd: '/Users/me/dev/gateway',
  git: null,
  state: 'idle',
  state_detail: null,
  origin: 'remote',
  control: 'remote',
  model: 'gpt-5.4-codex',
  permission_mode: 'on-request',
  effort: 'medium',
  speed: null,
  created_at: 1,
  updated_at: 2,
  last_seq: 0,
  archived: false,
  turn: null,
  todos: null,
  usage: null,
  queued: 0,
};

const key = keyOf(session);
const stored = (): Session | undefined => useSessions.getState().sessions[key];

function renderChat() {
  render(
    <MemoryRouter initialEntries={[`/sessions/${session.device_id}/${session.session_id}`]}>
      <Routes>
        <Route path="/sessions/:deviceId/:sessionId" element={<ChatPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

/** Opens the model card and presses the speed toggle inside it. */
async function toggleSpeed() {
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: strings.composer.modelCard }));
  await user.click(
    screen.getByRole('button', {
      name: strings.composer.speed(strings.composer.speedStandard),
    }),
  );
}

beforeEach(() => {
  rpcMock.mockReset();
  useSessions.setState({ sessions: { [key]: session }, loaded: true, agentFilter: null });
  useDevices.setState({ devices: [device], loaded: true, updateErrors: {} });
  useChat.setState({ sessions: {} });
  useOutbox.setState({ pending: {} });
  // Never 'open', so the page does not try to subscribe over the mocked socket.
  useConnection.setState({ status: 'closed' });
});

describe('the model card writes before the device answers', () => {
  it('lights the tier before the request resolves', async () => {
    rpcMock.mockReturnValue(new Promise(() => undefined));
    renderChat();
    await toggleSpeed();

    expect(rpcMock).toHaveBeenCalledWith('session.set', {
      session_id: session.session_id,
      speed: 'priority',
    });
    expect(stored()?.speed).toBe('priority');
  });

  it('puts the previous value back when the device refuses', async () => {
    rpcMock.mockRejectedValue(new Error('the agent is busy'));
    renderChat();
    await toggleSpeed();

    await waitFor(() => expect(stored()?.speed).toBeNull());
    expect(await screen.findByText('the agent is busy')).toBeInTheDocument();
  });

  it('does not overwrite a session.updated that landed in between', async () => {
    let refuse: (error: Error) => void = () => undefined;
    rpcMock.mockReturnValue(
      new Promise((_resolve, reject) => {
        refuse = reject;
      }),
    );
    renderChat();
    await toggleSpeed();

    // What the socket does with a `/model` typed in the terminal meanwhile.
    useSessions.getState().upsert({ ...session, speed: 'priority', model: 'gpt-5.4' });
    refuse(new Error('too late'));

    await waitFor(() => expect(screen.getByText('too late')).toBeInTheDocument());
    expect(stored()?.model).toBe('gpt-5.4');
    expect(stored()?.speed).toBe('priority');
  });
});
