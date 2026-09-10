import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { Plus, Search } from 'lucide-react';
import { Button } from '../../components/Button';
import { Menu } from '../../components/Popover';
import { strings } from '../../strings';
import { useDevices } from '../../stores/devices';
import { selectSessionList, useSessions } from '../../stores/sessions';
import { useSettings } from '../../stores/settings';
import { NewSessionDrawer } from './NewSessionDrawer';
import { SessionRow } from './SessionRow';
import './sessions.css';

export function SessionsPage() {
  const sessionsRecord = useSessions((s) => s.sessions);
  const sessionsLoaded = useSessions((s) => s.loaded);
  const loadSessions = useSessions((s) => s.load);
  const devices = useDevices((s) => s.devices);
  const showArchived = useSettings((s) => s.showArchived);
  const setShowArchived = useSettings((s) => s.setShowArchived);
  const [query, setQuery] = useState('');
  const [deviceFilter, setDeviceFilter] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const navigate = useNavigate();

  useEffect(() => {
    if (!sessionsLoaded) void loadSessions().catch(() => undefined);
  }, [sessionsLoaded, loadSessions]);

  const deviceNames = useMemo(
    () => Object.fromEntries(devices.map((d) => [d.device_id, d.name])),
    [devices],
  );

  const all = selectSessionList(sessionsRecord, {
    includeArchived: showArchived,
    ...(deviceFilter ? { deviceId: deviceFilter } : {}),
  });

  const needle = query.trim().toLowerCase();
  const visible = needle
    ? all.filter((s) =>
        `${s.title} ${s.cwd} ${deviceNames[s.device_id] ?? ''}`.toLowerCase().includes(needle),
      )
    : all;

  const grouped = useMemo(() => {
    const groups = new Map<string, typeof visible>();
    for (const session of visible) {
      const list = groups.get(session.device_id) ?? [];
      list.push(session);
      groups.set(session.device_id, list);
    }
    return [...groups.entries()];
  }, [visible]);

  return (
    <>
      <div className="page-head">
        <h1>{strings.sessions.title}</h1>
        <Button variant="primary" onClick={() => setCreating(true)}>
          <Plus size={15} aria-hidden />
          {strings.sessions.new}
        </Button>
      </div>

      <div className="sessions-toolbar">
        <label className="search-field">
          <Search size={15} aria-hidden />
          <input
            type="search"
            placeholder={strings.sessions.searchPlaceholder}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            aria-label={strings.sessions.searchPlaceholder}
          />
        </label>
        <Menu
          ariaLabel={strings.sessions.allDevices}
          align="end"
          label={deviceFilter ? (deviceNames[deviceFilter] ?? deviceFilter) : strings.sessions.allDevices}
          value={deviceFilter}
          onSelect={(id) => setDeviceFilter(id === '__all__' ? null : id)}
          options={[
            { id: '__all__', label: strings.sessions.allDevices },
            ...devices.map((d) => ({ id: d.device_id, label: d.name })),
          ]}
        />
        <button
          type="button"
          className="pill"
          aria-pressed={showArchived}
          onClick={() => setShowArchived(!showArchived)}
        >
          {showArchived ? strings.sessions.hideArchived : strings.sessions.showArchived}
        </button>
      </div>

      {visible.length === 0 ? (
        <div className="card empty">
          <strong>{needle ? strings.sessions.noMatches : strings.sessions.empty}</strong>
          {needle ? null : strings.sessions.emptyHint}
        </div>
      ) : (
        grouped.map(([deviceId, list]) => (
          <section className="session-group" key={deviceId}>
            {grouped.length > 1 ? (
              <h2 className="group-title">{deviceNames[deviceId] ?? deviceId}</h2>
            ) : null}
            <ul className="session-list card">
              {list.map((session) => (
                <SessionRow
                  key={`${session.device_id}/${session.session_id}`}
                  session={session}
                  deviceName={deviceNames[session.device_id] ?? session.device_id}
                  online={devices.find((d) => d.device_id === session.device_id)?.online ?? false}
                  onOpen={() => navigate(`/sessions/${session.device_id}/${session.session_id}`)}
                />
              ))}
            </ul>
          </section>
        ))
      )}

      <NewSessionDrawer open={creating} onClose={() => setCreating(false)} />
    </>
  );
}
