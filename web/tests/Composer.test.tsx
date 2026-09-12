import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Composer } from '../src/features/chat/Composer';
import { foldSession } from '../src/stores/chat';
import { useSettings } from '../src/stores/settings';
import { strings } from '../src/strings';
import { claudeAgent, codexAgent } from '../mock/fixtures';
import type { AgentInfo, Session, SessionEvent, SessionState } from '../src/protocol/types';

const baseSession: Session = {
  session_id: 'ses-1',
  device_id: 'dev-1',
  agent: 'claude',
  title: 'Fix flaky auth test',
  cwd: '/Users/me/dev/gateway',
  git: null,
  state: 'idle',
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
  todos: null,
  usage: null,
  queued: 0,
};

function setup(
  overrides: {
    state?: SessionState;
    control?: Session['control'];
    agent?: AgentInfo;
    deviceOnline?: boolean;
  } = {},
) {
  const onSend = vi.fn().mockResolvedValue(undefined);
  const props = {
    session: {
      ...baseSession,
      ...(overrides.state ? { state: overrides.state } : {}),
      ...(overrides.control ? { control: overrides.control } : {}),
      ...(overrides.agent ? { agent: overrides.agent.agent } : {}),
    },
    agent: overrides.agent ?? claudeAgent,
    deviceOnline: overrides.deviceOnline ?? true,
    queue: [],
    sttEnabled: false,
    sttLanguages: ['auto'],
    onSend,
    onSetOption: vi.fn(),
    onRemoveQueued: vi.fn(),
    onTakeover: vi.fn(),
  };
  render(<Composer {...props} />);
  return { onSend };
}

const typeAndSend = async (text: string, buttonName: RegExp | string) => {
  const user = userEvent.setup();
  await user.click(screen.getByLabelText('Message the agent…'));
  await user.keyboard(text);
  await user.click(screen.getByRole('button', { name: buttonName }));
};

beforeEach(() => {
  useSettings.setState({ sttLanguage: 'auto' });
});

describe('Composer send mode', () => {
  it('sends mode "auto" when the session is idle', async () => {
    const { onSend } = setup();
    await typeAndSend('run the tests', 'Send');
    await waitFor(() => expect(onSend).toHaveBeenCalledTimes(1));
    expect(onSend).toHaveBeenCalledWith('run the tests', [], 'auto');
  });

  it('still sends mode "auto" while the turn is running, so the device can queue', async () => {
    const { onSend } = setup({ state: 'running' });
    // Without the steer capability the button reads "Queue"…
    await typeAndSend('and then lint', 'Queue');
    await waitFor(() => expect(onSend).toHaveBeenCalledTimes(1));
    // …but the wire mode stays "auto": forcing "queue" would bypass steering.
    expect(onSend).toHaveBeenCalledWith('and then lint', [], 'auto');
  });

  it('sends mode "auto" for a steer-capable agent and labels the button Send', async () => {
    expect(codexAgent.capabilities).toContain('steer');
    const { onSend } = setup({ state: 'running', agent: codexAgent });
    await typeAndSend('actually target the CI runner', 'Send');
    await waitFor(() => expect(onSend).toHaveBeenCalledTimes(1));
    expect(onSend).toHaveBeenCalledWith('actually target the CI runner', [], 'auto');
  });

  it('offers "Interrupt & send" as an explicit alternative while running', async () => {
    const user = userEvent.setup();
    const { onSend } = setup({ state: 'needs_approval' });
    await user.click(screen.getByLabelText('Message the agent…'));
    await user.keyboard('stop and do this instead');

    await user.click(screen.getByRole('button', { name: 'Send options' }));
    await user.click(screen.getByRole('button', { name: 'Interrupt & send' }));

    await waitFor(() => expect(onSend).toHaveBeenCalledTimes(1));
    expect(onSend).toHaveBeenCalledWith('stop and do this instead', [], 'interrupt');
  });

  it('does not offer the interrupt alternative when the session is idle', () => {
    setup();
    expect(screen.queryByRole('button', { name: 'Send options' })).not.toBeInTheDocument();
  });

  it('sends on Enter and inserts a newline on Shift+Enter', async () => {
    const user = userEvent.setup();
    const { onSend } = setup();
    const input = screen.getByLabelText('Message the agent…');

    await user.click(input);
    await user.keyboard('first{Shift>}{Enter}{/Shift}second');
    expect(onSend).not.toHaveBeenCalled();
    expect(input).toHaveValue('first\nsecond');

    await user.keyboard('{Enter}');
    await waitFor(() => expect(onSend).toHaveBeenCalledTimes(1));
    expect(onSend).toHaveBeenCalledWith('first\nsecond', [], 'auto');
  });

  it('refuses to send an empty message', async () => {
    const { onSend } = setup();
    expect(screen.getByRole('button', { name: 'Send' })).toBeDisabled();
    await typeAndSend('   ', 'Send');
    expect(onSend).not.toHaveBeenCalled();
  });

  it('clears the field in the same tick as the send (A12)', async () => {
    const user = userEvent.setup();
    // A send that never settles: the field must not wait for it.
    const onSend = vi.fn().mockReturnValue(new Promise<void>(() => {}));
    render(
      <Composer
        session={baseSession}
        agent={claudeAgent}
        deviceOnline
        queue={[]}
        sttEnabled={false}
        sttLanguages={['auto']}
        onSend={onSend}
        onSetOption={vi.fn()}
        onRemoveQueued={vi.fn()}
        onTakeover={vi.fn()}
      />,
    );
    const input = screen.getByLabelText('Message the agent…');
    await user.click(input);
    await user.keyboard('run the tests');
    await user.click(screen.getByRole('button', { name: 'Send' }));

    expect(onSend).toHaveBeenCalledWith('run the tests', [], 'auto');
    expect(input).toHaveValue('');
    // Empty again, so the button is ready for the next message rather than busy.
    expect(screen.getByRole('button', { name: 'Send' })).toBeDisabled();
    await user.click(input);
    await user.keyboard('and then lint');
    expect(screen.getByRole('button', { name: 'Send' })).toBeEnabled();
  });

  it('hands the draft back when the gateway refuses the message', async () => {
    const user = userEvent.setup();
    const onSend = vi.fn().mockRejectedValue(new Error('That device is offline.'));
    render(
      <Composer
        session={baseSession}
        agent={claudeAgent}
        deviceOnline
        queue={[]}
        sttEnabled={false}
        sttLanguages={['auto']}
        onSend={onSend}
        onSetOption={vi.fn()}
        onRemoveQueued={vi.fn()}
        onTakeover={vi.fn()}
      />,
    );
    await user.click(screen.getByLabelText('Message the agent…'));
    await user.keyboard('keep me');
    await user.click(screen.getByRole('button', { name: 'Send' }));

    await screen.findByText('That device is offline.');
    expect(screen.getByLabelText('Message the agent…')).toHaveValue('keep me');
  });

  it('leaves a newer draft alone when an older send is refused', async () => {
    const user = userEvent.setup();
    let refuse = (_: unknown) => {};
    const onSend = vi.fn().mockReturnValue(new Promise((_resolve, reject) => (refuse = reject)));
    render(
      <Composer
        session={baseSession}
        agent={claudeAgent}
        deviceOnline
        queue={[]}
        sttEnabled={false}
        sttLanguages={['auto']}
        onSend={onSend}
        onSetOption={vi.fn()}
        onRemoveQueued={vi.fn()}
        onTakeover={vi.fn()}
      />,
    );
    const input = screen.getByLabelText('Message the agent…');
    await user.click(input);
    await user.keyboard('first message');
    await user.click(screen.getByRole('button', { name: 'Send' }));
    await user.click(input);
    await user.keyboard('second message');

    refuse(new Error('That device is offline.'));
    await screen.findByText('That device is offline.');
    expect(input).toHaveValue('second message');
  });
});

describe('Composer disabled states', () => {
  it('disables input and offers takeover for a terminal-controlled session', async () => {
    const user = userEvent.setup();
    const onTakeover = vi.fn();
    render(
      <Composer
        session={{ ...baseSession, control: 'terminal', state: 'readonly' }}
        agent={claudeAgent}
        deviceOnline
        queue={[]}
        sttEnabled={false}
        sttLanguages={['auto']}
        onSend={vi.fn()}
        onSetOption={vi.fn()}
        onRemoveQueued={vi.fn()}
        onTakeover={onTakeover}
      />,
    );
    expect(screen.getByLabelText('Message the agent…')).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Take over' }));
    expect(onTakeover).toHaveBeenCalled();
  });

  it('stays disabled for a terminal session that reports running (A7)', () => {
    const onTakeover = vi.fn();
    render(
      <Composer
        session={{ ...baseSession, control: 'terminal', state: 'running' }}
        agent={claudeAgent}
        deviceOnline
        queue={[]}
        sttEnabled={false}
        sttLanguages={['auto']}
        onSend={vi.fn()}
        onSetOption={vi.fn()}
        onRemoveQueued={vi.fn()}
        onTakeover={onTakeover}
      />,
    );
    const input = screen.getByLabelText('Message the agent…');
    expect(input).toBeDisabled();
    expect(input).toHaveAttribute('placeholder', 'Controlled by the terminal · take over to send');
    expect(screen.getByRole('button', { name: 'Take over' })).toBeInTheDocument();
  });

  it('disables the composer while the device is offline', () => {
    setup({ deviceOnline: false });
    const input = screen.getByLabelText('Message the agent…');
    expect(input).toBeDisabled();
    expect(input).toHaveAttribute('placeholder', 'Device is offline');
  });

  it('lists queued messages with a remove control', async () => {
    const user = userEvent.setup();
    const onRemoveQueued = vi.fn();
    render(
      <Composer
        session={{ ...baseSession, state: 'running', queued: 1 }}
        agent={claudeAgent}
        deviceOnline
        queue={[{ id: 'q1', text: 'also update the changelog', ts: 1 }]}
        sttEnabled={false}
        sttLanguages={['auto']}
        onSend={vi.fn()}
        onSetOption={vi.fn()}
        onRemoveQueued={onRemoveQueued}
        onTakeover={vi.fn()}
      />,
    );
    expect(screen.getByText('also update the changelog')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Remove from queue' }));
    expect(onRemoveQueued).toHaveBeenCalledWith('q1');
  });

  it('hides the microphone when the gateway has speech-to-text disabled', () => {
    setup();
    expect(screen.queryByRole('button', { name: 'Start voice input' })).not.toBeInTheDocument();
  });
});

/**
 * A17 — what the terminal chose is shown, not offered. `docs/DESIGN.md`
 * § "The composer": a session a terminal holds draws the model, permission mode
 * and effort as chips that open nothing.
 */
describe('Composer settings a terminal holds', () => {
  const renderComposer = (session: Partial<Session>, agent: AgentInfo | null = claudeAgent) =>
    render(
      <Composer
        session={{ ...baseSession, ...session }}
        agent={agent}
        deviceOnline
        queue={[]}
        sttEnabled={false}
        sttLanguages={['auto']}
        onSend={vi.fn().mockResolvedValue(undefined)}
        onSetOption={vi.fn()}
        onRemoveQueued={vi.fn()}
        onTakeover={vi.fn()}
      />,
    );

  const chip = (name: string, value: string) =>
    screen.queryByLabelText(strings.composer.setInTerminal(name, value));

  it('shows the three values on a shared Claude session', () => {
    renderComposer({ control: 'shared' });

    expect(chip(strings.composer.model, 'Sonnet 4.5')).toBeInTheDocument();
    expect(chip(strings.composer.permissionMode, 'Ask before edits')).toBeInTheDocument();
    expect(chip(strings.composer.effort, 'High')).toBeInTheDocument();
    // Chips, not pickers: nothing in the row opens a menu.
    expect(screen.queryByRole('button', { name: strings.composer.model })).not.toBeInTheDocument();
    expect(document.querySelectorAll('.composer-chip.readonly')).toHaveLength(3);
  });

  it('shows them on a terminal session too, whose composer is disabled', () => {
    renderComposer({ control: 'terminal' });

    expect(chip(strings.composer.model, 'Sonnet 4.5')).toBeInTheDocument();
    expect(screen.getByLabelText(strings.composer.placeholder)).toBeDisabled();
  });

  it('draws nothing for a value the device has not seen', () => {
    renderComposer({ control: 'shared', model: null, effort: null });

    expect(document.querySelectorAll('.composer-chip.readonly')).toHaveLength(1);
    expect(chip(strings.composer.permissionMode, 'Ask before edits')).toBeInTheDocument();
  });

  it('shows an id the agent does not advertise by its id', () => {
    // `auto` is a real Claude permission mode the device does not list.
    renderComposer({ control: 'shared', permission_mode: 'auto', model: 'claude-opus-5[1m]' });

    expect(chip(strings.composer.permissionMode, 'auto')).toBeInTheDocument();
    expect(chip(strings.composer.model, 'claude-opus-5[1m]')).toBeInTheDocument();
  });

  it('shows them with no agent at all, by their ids', () => {
    renderComposer({ control: 'terminal' }, null);

    expect(chip(strings.composer.model, 'claude-sonnet-4-5')).toBeInTheDocument();
    expect(chip(strings.composer.effort, 'high')).toBeInTheDocument();
  });

  it('keeps the pickers on a session the device drives', () => {
    renderComposer({ control: 'remote' });

    expect(screen.getByRole('button', { name: strings.composer.model })).toBeInTheDocument();
    expect(document.querySelectorAll('.composer-chip.readonly')).toHaveLength(0);
  });

  it('keeps the pickers on a shared agent that carries the settings', () => {
    renderComposer({ control: 'shared', agent: 'codex', model: 'gpt-5.4' }, codexAgent);

    expect(screen.getByRole('button', { name: strings.composer.model })).toBeInTheDocument();
    expect(document.querySelectorAll('.composer-chip.readonly')).toHaveLength(0);
  });

  it('follows a meta event that changes the model', () => {
    const session = { ...baseSession, control: 'shared' as const };
    const { rerender } = renderComposer(session);
    expect(chip(strings.composer.model, 'Sonnet 4.5')).toBeInTheDocument();

    // What the socket does with a `/model` typed in the terminal.
    const next = foldSession(session, [
      { seq: 9, ts: 9, kind: 'meta', model: 'claude-haiku-4-5' } as SessionEvent,
    ]);
    rerender(
      <Composer
        session={next}
        agent={claudeAgent}
        deviceOnline
        queue={[]}
        sttEnabled={false}
        sttLanguages={['auto']}
        onSend={vi.fn().mockResolvedValue(undefined)}
        onSetOption={vi.fn()}
        onRemoveQueued={vi.fn()}
        onTakeover={vi.fn()}
      />,
    );

    expect(chip(strings.composer.model, 'Haiku 4.5')).toBeInTheDocument();
    expect(chip(strings.composer.model, 'Sonnet 4.5')).toBeNull();
  });
});
