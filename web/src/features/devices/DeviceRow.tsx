import { MoreHorizontal } from 'lucide-react';
import { Popover } from '../../components/Popover';
import { OnlineDot } from '../../components/StatusDot';
import { cx } from '../../lib/cx';
import { latency, relativeTime } from '../../lib/format';
import { agentLabel, strings } from '../../strings';
import { updateNotice } from '../../stores/devices';
import type { Device } from '../../protocol/types';

interface Props {
  device: Device;
  sessionCount: number;
  /** A22: the build the gateway serves, when it serves one. */
  gatewayBuild: string | undefined;
  /** A22: why this device's last `device.update` was refused outright. */
  updateError: string | undefined;
  onRename: () => void;
  onUpdate: () => void;
  onRevoke: () => void;
}

/** A build is a SHA-256; eight characters name it without filling the row. */
const shortBuild = (build: string): string => build.slice(0, 8);

export function DeviceRow({
  device,
  sessionCount,
  gatewayBuild,
  updateError,
  onRename,
  onUpdate,
  onRevoke,
}: Props) {
  const agents = device.agents.filter((a) => a.available);
  const reach = device.online
    ? latency(device.latency_ms)
    : strings.devices.lastSeen(relativeTime(device.last_seen));

  const notice = updateNotice(device, updateError, gatewayBuild);
  const updating = device.update_state === 'updating';
  // The build is worth showing only while nothing louder replaces it.
  const clientText =
    notice === null && device.client_build
      ? strings.devices.clientBuild(device.client_version, shortBuild(device.client_build))
      : strings.devices.clientVersion(device.client_version);

  const blocked = !device.online
    ? strings.devices.updateOffline
    : updating
      ? strings.devices.updateInFlight
      : gatewayBuild === undefined
        ? strings.devices.updateNoBuild
        : device.client_build === gatewayBuild
          ? strings.devices.updateCurrent
          : null;

  return (
    <li className="device-row">
      <div className="device-main">
        <div className="device-name">
          <OnlineDot online={device.online} pulse={updating} />
          <span>{device.name}</span>
        </div>
        <div className="device-meta">
          <span className="mono">
            {device.hostname} · {device.platform} · {device.arch}
          </span>
          <span className="device-reach">
            {sessionCount > 0
              ? strings.devices.sessionsCount(sessionCount)
              : strings.devices.noSessions}{' '}
            · {reach}
          </span>
        </div>
        <div className="device-client">
          <span className="mono">{clientText}</span>
          {notice ? <span className={cx('device-update', notice.tone)}>{notice.text}</span> : null}
        </div>
      </div>

      <div className="device-agents">
        {agents.length === 0 ? (
          <span className="hint">{strings.devices.noAgents}</span>
        ) : (
          agents.map((agent) => (
            <span className="badge" key={agent.agent}>
              {agentLabel(agent.agent)}
              {agent.version ? ` ${agent.version}` : ''}
            </span>
          ))
        )}
      </div>

      <Popover
        chevron={false}
        ariaLabel={strings.a11y.openMenu}
        align="end"
        triggerClassName="device-menu-trigger"
        label={<MoreHorizontal size={16} aria-hidden />}
      >
        {(close) => (
          <ul className="menu" role="menu">
            <li>
              <button
                type="button"
                role="menuitem"
                className="menu-item"
                onClick={() => {
                  close();
                  onRename();
                }}
              >
                <span className="menu-label">{strings.common.rename}</span>
              </button>
            </li>
            <li>
              <button
                type="button"
                role="menuitem"
                className="menu-item"
                disabled={blocked !== null}
                title={blocked ?? undefined}
                onClick={() => {
                  close();
                  onUpdate();
                }}
              >
                <span className="menu-label">{strings.devices.update}</span>
              </button>
            </li>
            <li>
              <button
                type="button"
                role="menuitem"
                className="menu-item danger-text"
                onClick={() => {
                  close();
                  onRevoke();
                }}
              >
                <span className="menu-label">{strings.common.revoke}</span>
              </button>
            </li>
          </ul>
        )}
      </Popover>
    </li>
  );
}
