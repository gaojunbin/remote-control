/**
 * The Sessions page renders the Active / Archive rule: one group per device,
 * one collapsed Archive at the bottom, and a search that opens it on a match.
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

/** A paired device with nothing open on it. */
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

const index = (list: Session[]): Record<string, Session> =>
  Object.fromEntries(list.map((s) => [keyOf(s), s]));

beforeEach(() => {
  useDevices.setState({ devices: [...devices, quietDevice], loaded: true, error: null });
  useSessions.setState({ sessions: index(sessions), loaded: true });
  useSettings.setState({ showArchived: false, archiveExpanded: false });
});

const renderPage = () =>
  render(
    <MemoryRouter>
      <SessionsPage />
    </MemoryRouter>,
  );

describe('SessionsPage grouping', () => {
  it('collapses the Archive and opens it on a click', async () => {
    renderPage();

    // `ses-exited` is the one session whose CLI is gone.
    const header = screen.getByRole('button', { name: strings.sessions.archiveGroup(1) });
    expect(header).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByText('Rewrite the pairing docs')).not.toBeInTheDocument();

    await userEvent.click(header);

    expect(screen.getByText('Rewrite the pairing docs')).toBeInTheDocument();
    expect(useSettings.getState().archiveExpanded).toBe(true);
  });

  it('opens the Archive on its own when a search matches inside it', async () => {
    renderPage();

    await userEvent.type(
      screen.getByLabelText(strings.sessions.searchPlaceholder),
      'pairing docs',
    );

    expect(screen.getByText('Rewrite the pairing docs')).toBeInTheDocument();
    expect(useSettings.getState().archiveExpanded).toBe(false);
  });

  it('lists a manually archived session only while archived ones are shown', async () => {
    renderPage();
    useSettings.setState({ archiveExpanded: true });

    expect(screen.queryByText('Drop the legacy ingest path')).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: strings.sessions.showArchived }));

    expect(screen.getByText('Drop the legacy ingest path')).toBeInTheDocument();
  });

  it('shows a device with nothing open instead of an empty box', () => {
    renderPage();

    expect(screen.getByRole('heading', { name: quietDevice.name })).toBeInTheDocument();
    expect(screen.getByText(strings.sessions.noOpenSessions)).toBeInTheDocument();
  });

  it('puts the sessions that need an answer at the top of their device', () => {
    renderPage();

    const titles = screen
      .getAllByRole('button', { name: strings.sessions.open })
      .map((row) => row.querySelector('.session-title')?.textContent);

    expect(titles[0]).toBe('Migrate web to Vite 6');
  });
});
