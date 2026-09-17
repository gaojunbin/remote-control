/**
 * A33 — a device has a page: the machine's own facts, then one card per coding
 * agent it found, how each is signed in and what is left of its quota.
 * `docs/DESIGN.md` § "A device has a page".
 *
 * The hostname, the architecture and the client version live here alone: the
 * row dropped all three — a device keeps itself current (A36) — and this line
 * under the title is where someone goes to check them.
 *
 * The actions the row offers — Rename, Revoke, and Retry update on a device
 * whose update failed — are deliberately not repeated here.
 */
import { useEffect } from 'react';
import { ChevronLeft, RefreshCw } from 'lucide-react';
import { Link, useParams } from 'react-router';
import { Button } from '../../components/Button';
import { OnlineDot } from '../../components/StatusDot';
import { strings } from '../../strings';
import { selectDevice, useDevices } from '../../stores/devices';
import { AgentCard } from './AgentCard';
import { useDeviceQuota } from './useDeviceQuota';
import './device-page.css';

/** A build is a SHA-256; eight characters name it without filling the line. */
const shortBuild = (build: string): string => build.slice(0, 8);

export function DevicePage() {
  const { deviceId } = useParams();
  const device = useDevices(selectDevice(deviceId));
  const loaded = useDevices((s) => s.loaded);
  const load = useDevices((s) => s.load);
  const quota = useDeviceQuota(deviceId, device?.online ?? false);

  useEffect(() => {
    if (!loaded) void load().catch(() => undefined);
  }, [loaded, load]);

  const back = (
    <Link to="/devices" className="device-page-back">
      <ChevronLeft size={15} aria-hidden />
      {strings.devicePage.back}
    </Link>
  );

  if (!device) {
    return (
      <>
        {back}
        {loaded ? (
          <div className="card empty">
            <strong>{strings.devicePage.gone}</strong>
            {strings.devicePage.goneHint}
          </div>
        ) : null}
      </>
    );
  }

  const agents = device.agents.filter((agent) => agent.available);
  const clientText = device.client_build
    ? strings.devices.clientBuild(device.client_version, shortBuild(device.client_build))
    : strings.devices.clientVersion(device.client_version);

  return (
    <>
      {back}
      <div className="page-head">
        <div className="device-page-head">
          <h1 className="device-page-title">
            <OnlineDot online={device.online} />
            <span>{device.name}</span>
          </h1>
          <p className="hint mono">
            {device.hostname} · {device.platform} · {device.arch}
          </p>
          <p className="hint mono">{clientText}</p>
        </div>
        <Button onClick={quota.refresh} disabled={quota.status === 'checking'}>
          <RefreshCw size={15} aria-hidden />
          {strings.devicePage.refresh}
        </Button>
      </div>

      {agents.length === 0 ? (
        <p className="hint device-page-none">{strings.devices.noAgents}</p>
      ) : (
        <ul className="device-page-agents">
          {agents.map((agent) => (
            <AgentCard
              key={agent.agent}
              agent={agent}
              accounts={quota.accounts[agent.agent] ?? agent.accounts}
              status={quota.status}
              error={quota.error}
            />
          ))}
        </ul>
      )}
    </>
  );
}
