/**
 * The New session drawer. The device picker is a Popover, which renders its
 * panel in a portal on `document.body` — a sibling of the drawer overlay rather
 * than a descendant. It has to open, stay open, and not take the drawer down
 * with it when the user clicks inside it.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { NewSessionDrawer } from '../src/features/sessions/NewSessionDrawer';
import { useDevices } from '../src/stores/devices';
import { useSessions } from '../src/stores/sessions';
import { strings } from '../src/strings';
import { devices } from '../mock/fixtures';

vi.mock('../src/lib/gateway', () => ({
  rpc: vi.fn(async (method: string) => {
    if (method === 'device.dirs') return { path: '/Users/me', parent: null, entries: [], recent: [] };
    throw new Error(`unexpected ${method}`);
  }),
}));

const onClose = vi.fn();

beforeEach(() => {
  onClose.mockReset();
  useDevices.setState({ devices, loaded: true, error: null });
  useSessions.setState({ sessions: {}, loaded: true, agentFilter: null });
});

const renderDrawer = () =>
  render(
    <MemoryRouter>
      <NewSessionDrawer open onClose={onClose} />
    </MemoryRouter>,
  );

describe('NewSessionDrawer', () => {
  it('opens the device menu above the drawer and selects from it', async () => {
    renderDrawer();

    const trigger = screen.getByRole('button', { name: strings.newSession.device });
    expect(trigger).toHaveAttribute('aria-expanded', 'false');

    await userEvent.click(trigger);

    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    const menu = screen.getByRole('listbox', { name: strings.newSession.device });
    // The panel is portalled, so it is outside the drawer's own subtree.
    expect(screen.getByRole('dialog').contains(menu)).toBe(false);

    const second = devices[1]!;
    await userEvent.click(screen.getByRole('option', { name: new RegExp(second.name) }));

    // The drawer survived the click inside the portalled panel.
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getByText(second.name)).toBeInTheDocument();
  });

  it('labels its fields in sentence case', () => {
    renderDrawer();

    for (const label of [
      strings.newSession.device,
      strings.newSession.agent,
      strings.newSession.workingDirectory,
      strings.newSession.git,
    ]) {
      expect(label).toBe(label.charAt(0) + label.slice(1).toLowerCase());
    }
    expect(screen.getByText(strings.newSession.workingDirectory)).toBeInTheDocument();
  });

  it('offers no first-message field', () => {
    renderDrawer();

    expect(screen.queryByRole('textbox', { name: /message/i })).not.toBeInTheDocument();
    expect(document.querySelector('textarea')).toBeNull();
  });
});
