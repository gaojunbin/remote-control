import { MoreHorizontal } from 'lucide-react';
import { Popover } from '../../components/Popover';
import { OnlineDot } from '../../components/StatusDot';
import { latency, relativeTime } from '../../lib/format';
import { agentLabel, strings } from '../../strings';
import type { Device } from '../../protocol/types';

interface Props {
  device: Device;
  sessionCount: number;
  onRename: () => void;
  onRevoke: () => void;
}

export function DeviceRow({ device, sessionCount, onRename, onRevoke }: Props) {
  const agents = device.agents.filter((a) => a.available);
  const reach = device.online
    ? latency(device.latency_ms)
    : strings.devices.lastSeen(relativeTime(device.last_seen));

  return (
    <li className="device-row">
      <div className="device-main">
        <div className="device-name">
          <OnlineDot online={device.online} />
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
