/**
 * A25 — three more agents, and the two shapes the app had not met: one with no
 * permission system (pi) and one with no effort levels (Cursor).
 * `docs/DESIGN.md` § "Agents": every agent is named, each has a mark, and what
 * an agent does not have is not drawn.
 */
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { Composer } from '../src/features/chat/Composer';
import { StatusLine } from '../src/features/chat/StatusLine';
import { NewSessionDrawer } from '../src/features/sessions/NewSessionDrawer';
import { useDevices } from '../src/stores/devices';
import { useSessions } from '../src/stores/sessions';
import { agentLabel, agentMark, strings } from '../src/strings';
import { rpc } from '../src/lib/gateway';
import { claudeAgent, cursorAgent, devices, grokAgent, piAgent, sessions } from '../mock/fixtures';
import { fixturesAvailable, readFixture } from './fixtures';
import type { AgentInfo, Session } from '../src/protocol/types';

vi.mock('../src/lib/gateway', () => ({
  rpc: vi.fn(async (method: string, params: Record<string, unknown>) => {
    if (method === 'device.dirs') return { path: '/Users/me', parent: null, entries: [], recent: [] };
    if (method === 'device.git') return { git: null };
    if (method === 'session.create') return { session: { ...params, session_id: 'ses-new' } };
    throw new Error(`unexpected ${method}`);
  }),
}));

const sessionOf = (id: string): Session => {
  const found = sessions.find((s) => s.session_id === id);
  if (!found) throw new Error(`no such mock session ${id}`);
  return found;
};

describe('agent names and marks', () => {
  it('names every agent the device can report', () => {
    expect(agentLabel('claude')).toBe('Claude Code');
    expect(agentLabel('codex')).toBe('Codex');
    expect(agentLabel('grok')).toBe('Grok Build');
    expect(agentLabel('cursor')).toBe('Cursor');
    expect(agentLabel('pi')).toBe('pi');
  });

  it('marks each of them in one or two letters', () => {
    expect(agentMark('claude')).toBe('C');
    expect(agentMark('codex')).toBe('X');
    expect(agentMark('grok')).toBe('G');
    expect(agentMark('cursor')).toBe('Cu');
    expect(agentMark('pi')).toBe('π');
  });

  it('renders an agent nobody knows as itself', () => {
    expect(agentLabel('aider')).toBe('aider');
    expect(agentMark('aider')).toBe('A');
  });

  it('names the working agent in the status line', () => {
    render(
      <StatusLine
        session={{ ...sessionOf('ses-pi'), state: 'running' }}
        agent={piAgent}
        deviceOnline
        onTakeover={vi.fn()}
      />,
    );
    expect(screen.getByText(strings.status.workingSteer('pi'))).toBeInTheDocument();
  });
});

describe.runIf(fixturesAvailable())('the mock advertises what the contract does', () => {
  it.each([
    ['grok', grokAgent],
    ['cursor', cursorAgent],
    ['pi', piAgent],
  ])('matches objects/agent.%s.json', (id, mocked) => {
    expect(mocked).toEqual(readFixture<AgentInfo>(`objects/agent.${id}.json`));
  });
});

const renderDrawer = () => {
  useDevices.setState({ devices, loaded: true, error: null });
  useSessions.setState({ sessions: {}, loaded: true, agentFilter: null });
  render(
    <MemoryRouter>
      <NewSessionDrawer open onClose={vi.fn()} />
    </MemoryRouter>,
  );
};

const rowLabels = () => [...document.querySelectorAll('.label')].map((el) => el.textContent);

describe('the new-session form draws what the agent has', () => {
  it('offers all five agents, each named though it draws a mark', () => {
    renderDrawer();

    const control = screen.getByRole('group', { name: strings.newSession.agent });
    const marks = [...control.querySelectorAll('.agent-mark')].map((el) => el.textContent);
    expect(marks).toEqual(['C', 'X', 'G', 'Cu', 'π']);
    for (const name of ['Claude Code', 'Codex', 'Grok Build', 'Cursor', 'pi']) {
      expect(screen.getByRole('button', { name })).toBeInTheDocument();
    }
  });

  it('names the chosen agent under the row', async () => {
    renderDrawer();
    await userEvent.click(screen.getByRole('button', { name: 'Grok Build' }));

    expect(document.querySelector('.agent-line')?.textContent).toContain('Grok Build');
    expect(document.querySelector('.agent-line')?.textContent).toContain('grok-4.6');
  });

  it('draws no permission row for pi, which has no permission system', async () => {
    renderDrawer();
    await userEvent.click(screen.getByRole('button', { name: 'pi' }));

    expect(piAgent.permission_modes).toHaveLength(0);
    expect(rowLabels()).not.toContain(strings.newSession.permissions);
    expect(
      screen.queryByRole('button', { name: strings.newSession.permissions }),
    ).not.toBeInTheDocument();
    // What it does have is still offered.
    expect(rowLabels()).toContain(strings.newSession.model);
    expect(rowLabels()).toContain(strings.newSession.effort);
  });

  it('starts a pi session without a permission mode', async () => {
    renderDrawer();
    await userEvent.click(screen.getByRole('button', { name: 'pi' }));
    await userEvent.click(screen.getByRole('button', { name: new RegExp(strings.newSession.start) }));

    const call = vi.mocked(rpc).mock.calls.find(([method]) => method === 'session.create');
    expect(call?.[1]).toMatchObject({ agent: 'pi', effort: 'medium' });
    expect(call?.[1]).not.toHaveProperty('permission_mode');
  });

  it('draws no effort row for Cursor, which has no effort levels', async () => {
    renderDrawer();
    await userEvent.click(screen.getByRole('button', { name: 'Cursor' }));

    expect(cursorAgent.efforts).toHaveLength(0);
    expect(rowLabels()).not.toContain(strings.newSession.effort);
    expect(rowLabels()).toContain(strings.newSession.model);
    expect(rowLabels()).toContain(strings.newSession.permissions);
  });
});

const renderComposer = (session: Session, agent: AgentInfo) =>
  render(
    <Composer
      session={session}
      agent={agent}
      deviceOnline
      queue={[]}
      sttEnabled={false}
      sttLanguages={['auto']}
      question={null}
      onAnswer={vi.fn().mockResolvedValue(undefined)}
      onSend={vi.fn().mockResolvedValue(undefined)}
      onSetOption={vi.fn()}
      onRemoveQueued={vi.fn()}
      onTakeover={vi.fn()}
    />,
  );

describe('the composer draws what the agent has', () => {
  it('offers no permission picker on a pi session', () => {
    renderComposer(sessionOf('ses-pi'), piAgent);

    expect(
      screen.queryByRole('button', { name: strings.composer.permissionMode }),
    ).not.toBeInTheDocument();
    expect(document.querySelector('.composer-chip.readonly')).toBeNull();
    // The model card is still there, because pi has models and efforts.
    expect(screen.getByRole('button', { name: strings.composer.modelCard })).toHaveTextContent(
      'Claude Sonnet 4.5 High',
    );
  });

  it('reads the model alone on a Cursor session, and opens a card with no slider', async () => {
    renderComposer(sessionOf('ses-cursor'), cursorAgent);

    const chip = screen.getByRole('button', { name: strings.composer.modelCard });
    expect(chip).toHaveTextContent('Sonnet 4 Thinking');
    expect(document.querySelector('.sized-box-shown [data-effort-word]')).toBeNull();

    await userEvent.click(chip);
    expect(screen.queryByRole('slider', { name: strings.composer.effort })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: strings.composer.permissionMode })).toBeInTheDocument();
  });

  it('writes nothing on a terminal-held Grok session', () => {
    renderComposer(sessionOf('ses-grok-terminal'), grokAgent);

    expect(screen.getByLabelText(strings.composer.placeholder)).toBeDisabled();
    // A25: Grok cannot be taken over, so the bar only says who holds it.
    expect(grokAgent.capabilities).not.toContain('takeover');
    expect(screen.queryByRole('button', { name: strings.chat.takeOver })).not.toBeInTheDocument();
  });
});

/**
 * `docs/DESIGN.md` § "The composer": a held session's field only says who holds
 * it. Taking it over is the status line's clause, drawn where the button is.
 */
describe('taking over is offered where the agent can be taken over', () => {
  const placeholderOf = () =>
    screen.getByLabelText(strings.composer.placeholder).getAttribute('placeholder');

  const renderStatus = (id: string, agent: AgentInfo) => {
    render(<StatusLine session={sessionOf(id)} agent={agent} deviceOnline onTakeover={vi.fn()} />);
    return document.querySelector('.status-line')?.textContent ?? '';
  };

  it('keeps the field to who holds it on an agent that can be taken over', () => {
    expect(claudeAgent.capabilities).toContain('takeover');
    renderComposer(sessionOf('ses-terminal'), claudeAgent);

    expect(placeholderOf()).toBe(strings.composer.placeholderTerminal);
  });

  it('keeps the field to who holds it on one that cannot', () => {
    expect(grokAgent.capabilities).not.toContain('takeover');
    renderComposer(sessionOf('ses-grok-terminal'), grokAgent);

    expect(placeholderOf()).toBe(strings.composer.placeholderTerminal);
  });

  it('adds the clause to the status line where the agent can be taken over', () => {
    const line = renderStatus('ses-terminal', claudeAgent);

    expect(line).toContain(strings.status.terminalControlled);
    expect(line).toContain(strings.status.takeOverToSend);
    expect(screen.getByRole('button', { name: strings.chat.takeOver })).toBeInTheDocument();
  });

  it('leaves the clause off where it cannot', () => {
    const line = renderStatus('ses-grok-terminal', grokAgent);

    expect(line).toBe(strings.status.terminalControlled);
    expect(screen.queryByRole('button', { name: strings.chat.takeOver })).not.toBeInTheDocument();
  });
});
