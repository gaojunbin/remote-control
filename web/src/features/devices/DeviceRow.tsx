/**
 * One registered device, as `docs/DESIGN.md` § "The device row" rules it: a
 * computer glyph at the leading edge, the name once, a status line of dot,
 * online word and platform word, and the agents as logos alone. The hostname
 * and the architecture live on the device's own page, not here.
 */
import { Monitor, MoreHorizontal } from 'lucide-react';
import { Link } from 'react-router';
import { AgentLogo } from '../../components/AgentLogo';
import { Popover } from '../../components/Popover';
import { OnlineDot } from '../../components/StatusDot';
import { cx } from '../../lib/cx';
import { latency, relativeTime } from '../../lib/format';
import { agentLabel, platformLabel, strings } from '../../strings';
import { updateNotice } from '../../stores/devices';
import type { ClientBuildInfo } from '../../lib/api';
import type { Device } from '../../protocol/types';

interface Props {
  device: Device;
  sessionCount: number;
  /** A22: the client the gateway serves — its version and build — when it serves one. */
  served: ClientBuildInfo | undefined;
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
  served,
  updateError,
  onRename,
  onUpdate,
  onRevoke,
}: Props) {
  const agents = device.agents.filter((a) => a.available);
  const reach = device.online
    ? latency(device.latency_ms)
    : strings.devices.lastSeen(relativeTime(device.last_seen));

  const notice = updateNotice(device, updateError, served);
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
      : served === undefined
        ? strings.devices.updateNoBuild
        : device.client_build === served.build
          ? strings.devices.updateCurrent
          : null;

  return (
    <li className="device-row">
      {/* The same glyph on every device, whatever its platform: the app cannot
          tell a laptop from a desktop, and it is what keeps two rows apart now
          that nothing is drawn between them. */}
      <Monitor className="device-glyph" strokeWidth={1.5} aria-hidden />

      <div className="device-main">
        <div className="device-name">
          {/* A33: the row itself opens the device. The link is stretched over
              the whole row in CSS, and the menu is lifted above it, so the
              three actions keep working and none of them navigates. */}
          <Link className="device-open" to={`/devices/${device.device_id}`}>
            {device.name}
          </Link>
        </div>
        <div className="device-meta">
          <span className="device-status">
            <OnlineDot online={device.online} pulse={updating} />
            {device.online ? strings.devices.online : strings.devices.offline} ·{' '}
            {platformLabel(device.platform)}
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
            // The logo alone; its name is what a screen reader and a hover get,
            // and the version lives on the device page's agent card.
            <span
              className="device-agent"
              key={agent.agent}
              role="img"
              aria-label={agentLabel(agent.agent)}
              title={agentLabel(agent.agent)}
            >
              <AgentLogo agent={agent.agent} />
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
