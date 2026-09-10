import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { StatusLine } from '../src/features/chat/StatusLine';
import { claudeAgent, codexAgent } from '../mock/fixtures';
import type { Session, SessionState } from '../src/protocol/types';

const base: Session = {
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
  model: null,
  permission_mode: null,
  effort: null,
  created_at: 1,
  updated_at: 2,
  last_seq: 0,
  archived: false,
  turn: null,
  todos: null,
  usage: null,
  queued: 0,
};

const show = (session: Partial<Session>, agent = claudeAgent, deviceOnline = true) =>
  render(
    <StatusLine
      session={{ ...base, ...session }}
      agent={agent}
      deviceOnline={deviceOnline}
      onTakeover={vi.fn()}
    />,
  );

describe('StatusLine', () => {
  it('says the terminal is in control while its turn runs (A7)', () => {
    show({ control: 'terminal', state: 'running' } as { control: Session['control']; state: SessionState });
    expect(screen.getByText(/Controlled by the terminal/)).toBeInTheDocument();
    expect(screen.getByText(/a turn is running there/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Take over' })).toBeInTheDocument();
  });

  it('says the terminal is in control when it is idle', () => {
    show({ control: 'terminal', state: 'readonly' });
    expect(screen.getByText('Controlled by the terminal')).toBeInTheDocument();
  });

  it('offers no takeover when the agent lacks the capability', () => {
    show({ control: 'terminal', state: 'readonly' }, codexAgent);
    expect(screen.getByText('Controlled by the terminal')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Take over' })).not.toBeInTheDocument();
  });

  it('warns that a message will be queued for a running remote turn', () => {
    show({ state: 'running' });
    expect(screen.getByText('Claude Code is working · your message will be queued')).toBeInTheDocument();
  });

  it('warns that a message will steer a steer-capable agent', () => {
    show({ state: 'running', agent: 'codex' }, codexAgent);
    expect(screen.getByText('Codex is working · your message will steer the turn')).toBeInTheDocument();
  });

  it('reports approvals, questions and errors', () => {
    const { unmount } = show({ state: 'needs_approval' });
    expect(screen.getByText('Needs your approval')).toBeInTheDocument();
    unmount();

    const q = show({ state: 'needs_input' });
    expect(screen.getByText('Waiting for your answer')).toBeInTheDocument();
    q.unmount();

    show({ state: 'error', state_detail: 'the CLI exited with 1' });
    expect(screen.getByText('the CLI exited with 1')).toBeInTheDocument();
  });

  it('reports an offline device before anything else', () => {
    show({ control: 'terminal', state: 'running' }, claudeAgent, false);
    expect(screen.getByText('Device offline')).toBeInTheDocument();
  });
});
