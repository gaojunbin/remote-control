import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { Plus, Search } from 'lucide-react';
import { Mark } from '../../layout/Mark';
import { ArchiveHeader } from '../../components/ArchiveHeader';
import { StatusDot } from '../../components/StatusDot';
import { cx } from '../../lib/cx';
import { baseName, relativeTime } from '../../lib/format';
import { sessionStateLabel, strings } from '../../strings';
import { useDevices } from '../../stores/devices';
import { countWaiting, selectSessionList, selectSessionSections, useSessions } from '../../stores/sessions';
import { useSettings } from '../../stores/settings';
import type { Session } from '../../protocol/types';

interface Props {
  activeKey: string;
  onNewSession: () => void;
}

export function Sidebar({ activeKey, onNewSession }: Props) {
  const sessionsRecord = useSessions((s) => s.sessions);
  const devices = useDevices((s) => s.devices);
  const showArchived = useSettings((s) => s.showArchived);
  const archiveExpanded = useSettings((s) => s.archiveExpanded);
  const setArchiveExpanded = useSettings((s) => s.setArchiveExpanded);
  const [query, setQuery] = useState('');
  const navigate = useNavigate();

  const deviceNames = useMemo(
    () => Object.fromEntries(devices.map((d) => [d.device_id, d.name])),
    [devices],
  );
  const deviceIds = useMemo(() => devices.map((d) => d.device_id), [devices]);

  const needle = query.trim();
  const { active, archive } = useMemo(
    () =>
      selectSessionSections(sessionsRecord, {
        includeArchived: showArchived,
        deviceIds,
        deviceNames,
        query: needle,
      }),
    [sessionsRecord, showArchived, deviceIds, deviceNames, needle],
  );

  // A search that matches something inside the Archive opens it on its own.
  const openArchive = archiveExpanded || (needle.length > 0 && archive.length > 0);
  const waiting = useMemo(
    () => countWaiting(selectSessionList(sessionsRecord)),
    [sessionsRecord],
  );

  const deviceOnline = (id: string) => devices.find((d) => d.device_id === id)?.online ?? false;

  const item = (session: Session, showDevice: boolean) => {
    const key = `${session.device_id}/${session.session_id}`;
    const attention = session.state === 'needs_approval' || session.state === 'needs_input';
    // A10: a shared session keeps the terminal visible in the list.
    const terminal = session.control === 'terminal' || session.control === 'shared';
    const place = showDevice
      ? (deviceNames[session.device_id] ?? session.device_id)
      : baseName(session.cwd);
    return (
      <li key={key}>
        <button
          type="button"
          className={cx('sidebar-item', key === activeKey && 'active')}
          onClick={() => navigate(`/sessions/${key}`)}
        >
          <StatusDot state={session.state} online={deviceOnline(session.device_id)} />
          <span className="sidebar-item-text">
            <span className="sidebar-title">{session.title}</span>
            <span className={cx('sidebar-sub', attention && 'attention')}>
              {attention || terminal
                ? sessionStateLabel(session)
                : `${place} · ${relativeTime(session.updated_at)}`}
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
        {active.map((group) => (
          <section key={group.deviceId}>
            <h2 className="group-title">{deviceNames[group.deviceId] ?? group.deviceId}</h2>
            {group.sessions.length === 0 ? (
              <p className="group-empty">{strings.sessions.noOpenSessions}</p>
            ) : (
              <ul>{group.sessions.map((session) => item(session, false))}</ul>
            )}
          </section>
        ))}

        {archive.length > 0 ? (
          <section>
            <ArchiveHeader
              count={archive.length}
              expanded={openArchive}
              onToggle={() => setArchiveExpanded(!openArchive)}
            />
            {openArchive ? <ul>{archive.map((session) => item(session, true))}</ul> : null}
          </section>
        ) : null}
      </nav>

      <footer className="sidebar-foot hint">
        {strings.chat.sidebarFooter(devices.length, waiting)}
      </footer>
    </aside>
  );
}
