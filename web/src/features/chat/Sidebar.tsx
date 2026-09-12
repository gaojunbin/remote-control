import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { Plus, Search } from 'lucide-react';
import { Mark } from '../../layout/Mark';
import { ArchiveGroupHeader, DeviceGroupHeader } from '../../components/GroupHeader';
import { StatusDot } from '../../components/StatusDot';
import { cx } from '../../lib/cx';
import { baseName, relativeTime } from '../../lib/format';
import { sessionStateLabel, sessionTitle, strings } from '../../strings';
import { useDevices } from '../../stores/devices';
import { countWaiting, selectSessionLayout, selectSessionList, useSessions } from '../../stores/sessions';
import { useSettings } from '../../stores/settings';
import type { Session } from '../../protocol/types';

interface Props {
  activeKey: string;
  onNewSession: () => void;
}

export function Sidebar({ activeKey, onNewSession }: Props) {
  const sessionsRecord = useSessions((s) => s.sessions);
  const agentFilter = useSessions((s) => s.agentFilter);
  const devices = useDevices((s) => s.devices);
  const collapsedDevices = useSettings((s) => s.collapsedDevices);
  const archiveExpanded = useSettings((s) => s.archiveExpanded);
  const toggleDeviceCollapsed = useSettings((s) => s.toggleDeviceCollapsed);
  const toggleArchiveExpanded = useSettings((s) => s.toggleArchiveExpanded);
  const [query, setQuery] = useState('');
  const navigate = useNavigate();

  const needle = query.trim();
  const groups = useMemo(
    () =>
      selectSessionLayout(sessionsRecord, devices, {
        agentFilter,
        query: needle,
        collapsedDevices,
        archiveExpanded,
      }),
    [sessionsRecord, devices, agentFilter, needle, collapsedDevices, archiveExpanded],
  );

  const waiting = useMemo(
    () => countWaiting(selectSessionList(sessionsRecord)),
    [sessionsRecord],
  );

  const item = (session: Session, online: boolean) => {
    const key = `${session.device_id}/${session.session_id}`;
    const attention = session.state === 'needs_approval' || session.state === 'needs_input';
    // A10: a shared session keeps the terminal visible in the list.
    const terminal = session.control === 'terminal' || session.control === 'shared';
    return (
      <li key={key}>
        <button
          type="button"
          className={cx('sidebar-item', key === activeKey && 'active')}
          onClick={() => navigate(`/sessions/${key}`)}
        >
          <StatusDot state={session.state} control={session.control} online={online} />
          <span className="sidebar-item-text">
            <span className="sidebar-title">{sessionTitle(session)}</span>
            <span className={cx('sidebar-sub', attention && 'attention')}>
              {attention || terminal
                ? sessionStateLabel(session)
                : `${baseName(session.cwd)} · ${relativeTime(session.updated_at)}`}
            </span>
          </span>
        </button>
      </li>
    );
  };

  return (
    <aside className="sidebar">
      <div className="sidebar-head">
        <Link to="/sessions" className="brand sidebar-brand">
          <Mark size={18} />
          <span>{strings.productName}</span>
        </Link>
        <button
          type="button"
          className="icon-btn"
          aria-label={strings.chat.newSession}
          onClick={onNewSession}
        >
          <Plus size={16} />
        </button>
      </div>

      <label className="search-field sidebar-search">
        <Search size={14} aria-hidden />
        <input
          type="search"
          value={query}
          placeholder={strings.chat.searchPlaceholder}
          aria-label={strings.chat.searchPlaceholder}
          onChange={(e) => setQuery(e.target.value)}
        />
      </label>

      <nav className="sidebar-list scroll-thin" aria-label={strings.nav.sessions}>
        {groups.map((group) => (
          <section key={group.device.device_id}>
            <DeviceGroupHeader
              name={group.device.name}
              online={group.device.online}
              expanded={!group.collapsed}
              onToggle={() => toggleDeviceCollapsed(group.device.device_id)}
            />
            {group.collapsed ? null : (
              <>
                {group.active.length > 0 ? (
                  <ul>{group.active.map((session) => item(session, group.device.online))}</ul>
                ) : null}
                {group.archive.length > 0 ? (
                  <>
                    <ArchiveGroupHeader
                      count={group.archive.length}
                      expanded={group.archiveExpanded}
                      onToggle={() => toggleArchiveExpanded(group.device.device_id)}
                    />
                    {group.archiveExpanded ? (
                      <ul>{group.archive.map((session) => item(session, group.device.online))}</ul>
                    ) : null}
                  </>
                ) : null}
              </>
            )}
          </section>
        ))}
      </nav>

      <footer className="sidebar-foot hint">
        {strings.chat.sidebarFooter(devices.length, waiting)}
      </footer>
    </aside>
  );
}
