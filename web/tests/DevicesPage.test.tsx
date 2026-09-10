/**
 * The device row menu. The list is one soft surface that clips its rounded
 * corners, so the panel has to leave that subtree entirely: it is rendered in
 * a portal on `document.body` and placed in viewport coordinates.
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { DevicesPage } from '../src/features/devices/DevicesPage';
import { useDevices } from '../src/stores/devices';
import { useSessions } from '../src/stores/sessions';
import { strings } from '../src/strings';
import { devices } from '../mock/fixtures';

beforeEach(() => {
  useDevices.setState({ devices: [...devices], loaded: true, error: null });
  useSessions.setState({ sessions: {}, loaded: true });
});

const renderPage = () =>
  render(
    <MemoryRouter>
      <DevicesPage />
    </MemoryRouter>,
  );

const openFirstMenu = async () => {
  const triggers = screen.getAllByRole('button', { name: strings.a11y.openMenu });
  await userEvent.click(triggers[0]!);
  return screen.getByRole('menu');
};

describe('device row menu', () => {
  it('renders the panel on the body, outside the clipping list surface', async () => {
    const { container } = renderPage();

    const panel = (await openFirstMenu()).closest('.popover-panel');

    expect(panel).not.toBeNull();
    expect(panel?.parentElement).toBe(document.body);
    expect(container.querySelector('.device-list')?.contains(panel!)).toBe(false);
  });

  it('places the panel against the viewport rather than against its trigger', async () => {
    renderPage();

    const panel = (await openFirstMenu()).closest('.popover-panel') as HTMLElement;

    // Written by the layout effect from the trigger's viewport rect. Anchoring
    // to the trigger instead is what let a list surface clip the panel.
    expect(panel.style.left).not.toBe('');
    expect(panel.style.top === '' && panel.style.bottom === '').toBe(false);
  });

  it('still routes a click inside the portal to the item it landed on', async () => {
    renderPage();
    await openFirstMenu();

    await userEvent.click(screen.getByRole('menuitem', { name: strings.common.rename }));

    expect(screen.getByText(strings.devices.renameTitle)).toBeInTheDocument();
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('closes on Escape', async () => {
    renderPage();
    await openFirstMenu();

    await userEvent.keyboard('{Escape}');

    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });
});
