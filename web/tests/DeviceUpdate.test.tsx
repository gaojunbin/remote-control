/**
 * A22 in the device list: the build a device runs, the three states an update
 * can be in, and the request the Update item sends.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { DevicesPage } from '../src/features/devices/DevicesPage';
import { useAuth } from '../src/stores/auth';
import { useDevices } from '../src/stores/devices';
import { useSessions } from '../src/stores/sessions';
import { setSocket } from '../src/lib/gateway';
import { RequestError } from '../src/lib/ws';
import { strings } from '../src/strings';
import { CLIENT_BUILD, OLD_CLIENT_BUILD, devices } from '../mock/fixtures';
import type { AppSocket } from '../src/lib/ws';
import type { Device } from '../src/protocol/types';

const config = {
  public_origin: 'https://rc.example.com',
  stt: { enabled: false, languages: ['auto'] },
  push: { web_enabled: false, apns_enabled: false },
  version: '0.1.0',
  client: { version: '0.1.0', build: CLIENT_BUILD, url: '/dist/rc_client-latest.whl' },
};

const request = vi.fn(async () => ({ accepted: true, from: OLD_CLIENT_BUILD }));

/** `mac` runs the gateway's build; `ci` runs the one before it. */
function setDevices(patch: { mac?: Partial<Device>; ci?: Partial<Device> } = {}): void {
  const next = devices.map((device) =>
    device.device_id === 'dev-mac' ? { ...device, ...patch.mac } : { ...device, ...patch.ci },
  );
  useDevices.setState({ devices: next, loaded: true, error: null, updateErrors: {} });
}

beforeEach(() => {
  request.mockClear();
  request.mockResolvedValue({ accepted: true, from: OLD_CLIENT_BUILD });
  setSocket({ request } as unknown as AppSocket);
  useAuth.setState({ status: 'signed-in', config });
  useSessions.setState({ sessions: {}, loaded: true });
  setDevices();
});

afterEach(() => {
  setSocket(null);
});

const renderPage = () =>
  render(
    <MemoryRouter>
      <DevicesPage />
    </MemoryRouter>,
  );

const openMenuFor = async (name: string): Promise<HTMLElement> => {
  const row = screen.getByText(name).closest('li');
  if (!row) throw new Error(`no row for ${name}`);
  await userEvent.click(within(row).getByRole('button', { name: strings.a11y.openMenu }));
  return screen.getByRole('menu');
};

const updateItem = () => screen.getByRole('menuitem', { name: strings.devices.update });

describe('the client build on a device row', () => {
  it('names the version and the first eight characters of the build', () => {
    renderPage();
    expect(screen.getByText(`client 0.1.0 · ${CLIENT_BUILD.slice(0, 8)}`)).toBeInTheDocument();
  });

  it('says an update is available when the build differs from the gateway’s', () => {
    renderPage();
    expect(screen.getByText(strings.devices.updateAvailable)).toBeInTheDocument();
    // The build itself gives way to the notice on that row.
    expect(screen.queryByText(new RegExp(OLD_CLIENT_BUILD.slice(0, 8)))).not.toBeInTheDocument();
    expect(screen.getByText('client 0.0.9')).toBeInTheDocument();
  });

  it('says nothing when the gateway serves no build', () => {
    useAuth.setState({ config: { ...config, client: undefined } });
    renderPage();
    expect(screen.queryByText(strings.devices.updateAvailable)).not.toBeInTheDocument();
  });

  it('draws "Updating…" and a pulsing dot while an update runs', () => {
    setDevices({ ci: { update_state: 'updating' } });
    const { container } = renderPage();

    expect(screen.getByText(strings.devices.updating)).toBeInTheDocument();
    expect(container.querySelectorAll('.dot.pulse')).toHaveLength(1);
  });

  it('draws the reason an update failed', () => {
    setDevices({ ci: { update_state: 'failed', update_message: 'the device did not come back' } });
    renderPage();
    expect(
      screen.getByText(strings.devices.updateFailed('the device did not come back')),
    ).toBeInTheDocument();
  });
});

describe('the Update item', () => {
  it('confirms, then asks the device for the build the gateway serves', async () => {
    renderPage();
    await openMenuFor('ci-runner-01');
    await userEvent.click(updateItem());

    expect(screen.getByText(strings.devices.updateBody('ci-runner-01'))).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: strings.devices.updateConfirm }));

    await waitFor(() =>
      expect(request).toHaveBeenCalledWith(
        'device.update',
        { device_id: 'dev-ci', build: CLIENT_BUILD },
        undefined,
      ),
    );
  });

  it('sits between Rename and Remove', async () => {
    renderPage();
    const menu = await openMenuFor('ci-runner-01');
    const labels = [...menu.querySelectorAll('.menu-label')].map((n) => n.textContent);
    expect(labels).toEqual([strings.common.rename, strings.devices.update, strings.common.revoke]);
  });

  it('is disabled on a device already running that build', async () => {
    renderPage();
    await openMenuFor('mac-studio-office');
    expect(updateItem()).toBeDisabled();
    expect(updateItem()).toHaveAttribute('title', strings.devices.updateCurrent);
  });

  it('is disabled while the device is offline, and says why', async () => {
    setDevices({ ci: { online: false } });
    renderPage();
    await openMenuFor('ci-runner-01');
    expect(updateItem()).toBeDisabled();
    expect(updateItem()).toHaveAttribute('title', strings.devices.updateOffline);
  });

  it('is disabled while an update is in flight and enabled again once it failed', async () => {
    setDevices({ ci: { update_state: 'updating' } });
    renderPage();
    await openMenuFor('ci-runner-01');
    expect(updateItem()).toBeDisabled();

    await userEvent.keyboard('{Escape}');
    setDevices({ ci: { update_state: 'failed', update_message: 'no' } });
    await openMenuFor('ci-runner-01');
    expect(updateItem()).toBeEnabled();
  });

  it('shows the device’s own words when the request is refused', async () => {
    request.mockRejectedValue(new RequestError({ code: 'conflict', message: '2 sessions running' }));
    renderPage();
    await openMenuFor('ci-runner-01');
    await userEvent.click(updateItem());
    await userEvent.click(screen.getByRole('button', { name: strings.devices.updateConfirm }));

    await waitFor(() =>
      expect(
        screen.getByText(strings.devices.updateFailed('2 sessions running')),
      ).toBeInTheDocument(),
    );
  });
});
