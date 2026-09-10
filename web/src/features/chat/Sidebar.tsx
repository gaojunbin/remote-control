import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { Plus, Search } from 'lucide-react';
import { Mark } from '../../layout/Mark';
import { StatusDot } from '../../components/StatusDot';
import { cx } from '../../lib/cx';
import { baseName, relativeTime } from '../../lib/format';
import { sessionStateLabel, strings } from '../../strings';
import { useDevices } from '../../stores/devices';
import { countWaiting, selectSessionList, useSessions } from '../../stores/sessions';

interface Props {
  activeKey: string;
  onNewSession: () => void;
}

export function Sidebar({ activeKey, onNewSession }: Props) {
  const sessionsRecord = useSessions((s) => s.sessions);
  const devices = useDevices((s) => s.devices);
  const [query, setQuery] = useState('');
  const navigate = useNavigate();

  const sessions = useMemo(() => selectSessionList(sessionsRecord), [sessionsRecord]);
  const needle = query.trim().toLowerCase();
  const visible = needle
    ? sessions.filter((s) => `${s.title} ${s.cwd}`.toLowerCase().includes(needle))
    : sessions;

  const groups = useMemo(() => {
    const map = new Map<string, typeof visible>();
    for (const session of visible) {
      const list = map.get(session.device_id) ?? [];
      list.push(session);
      map.set(session.device_id, list);
    }
    return [...map.entries()];
  }, [visible]);

  const deviceName = (id: string) => devices.find((d) => d.device_id === id)?.name ?? id;
  const deviceOnline = (id: string) => devices.find((d) => d.device_id === id)?.online ?? false;

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
        {groups.map(([deviceId, list]) => (
          <section key={deviceId}>
            <h2 className="group-title">{deviceName(deviceId)}</h2>
            <ul>
              {list.map((session) => {
                const key = `${session.device_id}/${session.session_id}`;
                const attention =
                  session.state === 'needs_approval' || session.state === 'needs_input';
                // A10: a shared session keeps the terminal visible in the list.
                const terminal = session.control === 'terminal' || session.control === 'shared';
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
                            : `${baseName(session.cwd)} · ${relativeTime(session.updated_at)}`}
                        </span>
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          </section>
        ))}
      </nav>

      <footer className="sidebar-foot hint">
        {strings.chat.sidebarFooter(devices.length, countWaiting(sessions))}
      </footer>
    </aside>
  );
}
