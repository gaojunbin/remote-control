/**
 * The Sessions page renders the grouping rule: one collapsible group per
 * device, that device's active rows, then its own collapsed Archive, with an
 * agent filter shared with the chat sidebar.
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
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
});
