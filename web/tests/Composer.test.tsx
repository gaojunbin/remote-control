import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Composer } from '../src/features/chat/Composer';
import { useSettings } from '../src/stores/settings';
import { claudeAgent, codexAgent } from '../mock/fixtures';
import type { AgentInfo, Session, SessionState } from '../src/protocol/types';

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
  useSettings.setState({ sttLanguage: 'auto', pushToTalk: false, showArchived: false });
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

  it('clears the box only after a successful send', async () => {
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
