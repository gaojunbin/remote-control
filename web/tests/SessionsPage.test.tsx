/**
 * The Sessions page renders the grouping rule: one collapsible group per
 * device, that device's active rows, then its own collapsed Archive, with an
 * agent filter shared with the chat sidebar.
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { SessionsPage } from '../src/features/sessions/SessionsPage';
import { useDevices } from '../src/stores/devices';
import { keyOf, useSessions } from '../src/stores/sessions';
import { useSettings } from '../src/stores/settings';
import { strings } from '../src/strings';
import { devices, sessions } from '../mock/fixtures';
import type { Device, Session } from '../src/protocol/types';

/** A paired device with nothing on it at all. */
const quietDevice: Device = {
  device_id: 'dev-quiet',
  name: 'quiet-box',
  platform: 'linux',
  hostname: 'quiet-box',
  arch: 'x86_64',
  client_version: '0.1.0',
  online: true,
  last_seen: Date.now(),
  created_at: 0,
  latency_ms: 30,
  agents: [],
};

const MAC = 'mac-studio-office';
const CI = 'ci-runner-01';

const index = (list: Session[]): Record<string, Session> =>
  Object.fromEntries(list.map((s) => [keyOf(s), s]));

beforeEach(() => {
  useDevices.setState({ devices: [...devices, quietDevice], loaded: true, error: null });
  useSessions.setState({ sessions: index(sessions), loaded: true, agentFilter: null });
  useSettings.setState({ collapsedDevices: [], archiveExpanded: [] });
});

const renderPage = () =>
  render(
    <MemoryRouter>
      <SessionsPage />
    </MemoryRouter>,
  );

const deviceHeader = (name: string) => screen.getByRole('button', { name: new RegExp(name) });

const rowTitles = (): string[] =>
  screen
    .getAllByRole('button', { name: strings.sessions.open })
    .map((row) => row.querySelector('.session-title')?.textContent ?? '');

/** Row titles in a device's Active list, and in its own Archive. */
const titlesIn = (selector: string): string[] =>
  [...document.querySelectorAll(`${selector} .session-title`)].map((el) => el.textContent ?? '');

const activeTitles = (): string[] => titlesIn('.session-group > .session-list');
const archiveTitles = (): string[] => titlesIn('.session-archive-group .session-list');

/** The tone class on one row's status dot, by the row's title. */
const dotTone = (title: string): string => {
  const row = screen
    .getAllByRole('button', { name: strings.sessions.open })
    .find((r) => r.querySelector('.session-title')?.textContent === title);
  return (row?.querySelector('.dot')?.className ?? '').replace('dot ', '');
};

describe('SessionsPage grouping', () => {
  it('prints each device name exactly as the device reports it', () => {
    renderPage();

    expect(screen.getByText(MAC)).toBeInTheDocument();
    expect(screen.getByText(CI)).toBeInTheDocument();
  });

  it('renders no group for a device with nothing on it', () => {
    renderPage();

    expect(screen.queryByText(quietDevice.name)).not.toBeInTheDocument();
  });

  it('offers no global archive toggle', () => {
    renderPage();

    expect(screen.queryByRole('button', { name: /archived/i })).not.toBeInTheDocument();
  });

  it('collapses one device without touching the other, and remembers it', async () => {
    renderPage();

    expect(screen.getByText('Fix flaky auth test')).toBeInTheDocument();

    await userEvent.click(deviceHeader(MAC));

    expect(screen.queryByText('Fix flaky auth test')).not.toBeInTheDocument();
    expect(screen.getByText('Add OTLP traces')).toBeInTheDocument();
    expect(useSettings.getState().collapsedDevices).toEqual(['dev-mac']);

    await userEvent.click(deviceHeader(MAC));

    expect(screen.getByText('Fix flaky auth test')).toBeInTheDocument();
    expect(useSettings.getState().collapsedDevices).toEqual([]);
  });

  it('keeps a device expanded by default even after another one was folded', () => {
    useSettings.setState({ collapsedDevices: ['dev-ci'] });
    renderPage();

    expect(screen.getByText('Fix flaky auth test')).toBeInTheDocument();
    expect(screen.queryByText('Add OTLP traces')).not.toBeInTheDocument();
  });

  it('gives every device its own collapsed Archive', async () => {
    renderPage();

    // dev-mac: two exited sessions and one archived by hand; dev-ci: one each.
    const macArchive = screen.getByRole('button', { name: strings.sessions.archiveGroup(3) });
    const ciArchive = screen.getByRole('button', { name: strings.sessions.archiveGroup(2) });
    expect(macArchive).toHaveAttribute('aria-expanded', 'false');
    expect(ciArchive).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByText('Rewrite the pairing docs')).not.toBeInTheDocument();

    await userEvent.click(macArchive);

    expect(screen.getByText('Rewrite the pairing docs')).toBeInTheDocument();
    expect(screen.getByText('Sketch the pairing QR flow')).toBeInTheDocument();
    // The other device's Archive stays shut.
    expect(screen.queryByText('Drop the legacy ingest path')).not.toBeInTheDocument();
    expect(useSettings.getState().archiveExpanded).toEqual(['dev-mac']);
  });

  it('opens the Archive that a search matched, leaving the stored ids alone', async () => {
    renderPage();

    await userEvent.type(
      screen.getByLabelText(strings.sessions.searchPlaceholder),
      'pairing docs',
    );

    expect(screen.getByText('Rewrite the pairing docs')).toBeInTheDocument();
    expect(useSettings.getState().archiveExpanded).toEqual([]);
  });

  it('opens a folded device while a search matches inside it', async () => {
    useSettings.setState({ collapsedDevices: ['dev-mac'] });
    renderPage();

    expect(screen.queryByText('Fix flaky auth test')).not.toBeInTheDocument();

    const search = screen.getByLabelText(strings.sessions.searchPlaceholder);
    await userEvent.type(search, 'flaky');

    expect(screen.getByText('Fix flaky auth test')).toBeInTheDocument();
    expect(useSettings.getState().collapsedDevices).toEqual(['dev-mac']);

    await userEvent.clear(search);

    expect(screen.queryByText('Fix flaky auth test')).not.toBeInTheDocument();
  });

  it('marks a hand-archived row as Archived', async () => {
    renderPage();

    await userEvent.click(screen.getByRole('button', { name: strings.sessions.archiveGroup(2) }));

    expect(screen.getByText(strings.sessions.archived)).toBeInTheDocument();
  });

  it('tags every row with its agent', () => {
    renderPage();

    expect(screen.getAllByText('Claude Code').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Codex').length).toBeGreaterThan(0);
  });

  it('filters by agent and keeps the choice in the sessions store', async () => {
    renderPage();

    await userEvent.click(screen.getByRole('button', { name: 'Codex' }));

    expect(useSessions.getState().agentFilter).toBe('codex');
    expect(rowTitles()).not.toContain('Fix flaky auth test');
    expect(rowTitles()).toContain('Add OTLP traces');

    await userEvent.click(screen.getByRole('button', { name: strings.sessions.allAgents }));

    expect(useSessions.getState().agentFilter).toBeNull();
    expect(rowTitles()).toContain('Fix flaky auth test');
  });

  it('drops a device whose sessions the agent filter removed', async () => {
    useSessions.setState({
      sessions: index(sessions.filter((s) => s.device_id !== 'dev-ci' || s.agent === 'codex')),
    });
    renderPage();

    await userEvent.click(screen.getByRole('button', { name: 'Claude Code' }));

    expect(screen.getByText(MAC)).toBeInTheDocument();
    expect(screen.queryByText(CI)).not.toBeInTheDocument();
  });

  it('puts the sessions that need an answer at the top of their device', () => {
    renderPage();

    expect(rowTitles()[0]).toBe('Migrate web to Vite 6');
  });

  it('tones every dot from the state and the control together', async () => {
    renderPage();

    expect(dotTone('Fix flaky auth test')).toBe('working');
    expect(dotTone('Migrate web to Vite 6')).toBe('waiting');
    expect(dotTone('Split the ingest migration')).toBe('waiting');
    // Idle, but a terminal still holds it: alive rather than exited.
    expect(dotTone('Wire the channel shim')).toBe('live');
    expect(dotTone('iOS push tokens')).toBe('live');
    expect(dotTone('Regenerate the API client')).toBe('failed');

    await userEvent.click(screen.getByRole('button', { name: strings.sessions.archiveGroup(3) }));

    expect(dotTone('Rewrite the pairing docs')).toBe('off');
  });

  /**
   * A15: the device clears `archived` as a turn starts, and the row has to
   * leave the Archive on that one `session.updated` alone — no reload, and no
   * second frame. `upsert` is exactly what the socket handler calls.
   */
  it('moves a resumed session from the Archive to the Active rows (A15)', async () => {
    renderPage();
    await userEvent.click(screen.getByRole('button', { name: strings.sessions.archiveGroup(3) }));

    expect(archiveTitles()).toContain('Sketch the pairing QR flow');
    expect(activeTitles()).not.toContain('Sketch the pairing QR flow');

    const revived = useSessions.getState().sessions['dev-mac/ses-archived-mac']!;
    act(() =>
      useSessions
        .getState()
        .upsert({ ...revived, archived: false, control: 'terminal', state: 'running' }),
    );

    expect(activeTitles()).toContain('Sketch the pairing QR flow');
    expect(archiveTitles()).not.toContain('Sketch the pairing QR flow');
    // Only this device's Archive is open, and it is one row shorter.
    expect(archiveTitles()).toHaveLength(2);
  });
});

/**
 * `docs/DESIGN.md` § "Session lists": the archive action is offered on exactly
 * one kind of row, and there is no unarchive anywhere.
 */
describe('the archive action', () => {
  const rowFor = (title: string): HTMLElement => {
    const row = screen.getByText(title).closest('.session-row');
    if (!row) throw new Error(`no row for ${title}`);
    return row as HTMLElement;
  };

  const archiveIn = (title: string) =>
    within(rowFor(title)).queryByRole('button', { name: strings.sessions.archive });

  it('offers it on a session the device drives', () => {
    renderPage();
    expect(archiveIn('iOS push tokens')).toBeInTheDocument();
  });

  it('offers nothing on a row a terminal holds', () => {
    renderPage();
    // `terminal` and `shared`: the terminal owns the row until it exits.
    expect(archiveIn('Refactor relay routing')).toBeNull();
    expect(archiveIn('Wire the channel shim')).toBeNull();
  });

  it('offers nothing on a row already in the Archive', async () => {
    renderPage();
    await userEvent.click(screen.getByRole('button', { name: strings.sessions.archiveGroup(3) }));

    // Hand-archived, and an exited session the CLI let go of.
    expect(archiveIn('Sketch the pairing QR flow')).toBeNull();
    expect(archiveIn('Rewrite the pairing docs')).toBeNull();
    expect(screen.queryByRole('button', { name: /unarchive/i })).not.toBeInTheDocument();
  });

  it('archives without asking what to toggle', async () => {
    const calls: [string, boolean][] = [];
    const request = useSessions.getState().setArchived;
    useSessions.setState({
      setArchived: async (session, archived) => void calls.push([session.session_id, archived]),
    });
    try {
      renderPage();
      const button = archiveIn('iOS push tokens');
      if (!button) throw new Error('the action should be offered');
      await userEvent.click(button);
    } finally {
      useSessions.setState({ setArchived: request });
    }

    expect(calls).toEqual([['ses-push', true]]);
  });
});

/**
 * `docs/DESIGN.md` § "Session lists": a thread the agent has not named yet
 * arrives with an empty `title`, and the row still carries a name in the
 * title's own type — including under a search for those words.
 */
describe('a session with no title', () => {
  const untitled: Session = {
    ...sessions[0]!,
    session_id: 'ses-untitled',
    device_id: 'dev-mac',
    title: '',
    cwd: '/Users/me/dev/remote-control/web',
    state: 'idle',
    turn: null,
    todos: null,
    usage: null,
  };

  const withUntitled = () =>
    useSessions.setState({ sessions: index([...sessions, untitled]) });

  it('names the row "Untitled session"', () => {
    withUntitled();
    renderPage();

    expect(activeTitles()).toContain(strings.sessions.untitled);
  });

  it('leaves every titled row as the device reported it', () => {
    withUntitled();
    renderPage();

    expect(activeTitles()).toContain('Fix flaky auth test');
    expect(rowTitles().filter((t) => t === strings.sessions.untitled)).toHaveLength(1);
  });

  it('is found by a search for those words', async () => {
    withUntitled();
    renderPage();

    await userEvent.type(screen.getByLabelText(strings.sessions.searchPlaceholder), 'untitled');

    expect(rowTitles()).toEqual([strings.sessions.untitled]);
  });
});
