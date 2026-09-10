import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { Plus, Search } from 'lucide-react';
import { ArchiveHeader } from '../../components/ArchiveHeader';
import { Button } from '../../components/Button';
import { Menu } from '../../components/Popover';
import { strings } from '../../strings';
import { useDevices } from '../../stores/devices';
import { selectSessionSections, useSessions } from '../../stores/sessions';
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
  const archiveExpanded = useSettings((s) => s.archiveExpanded);
  const setArchiveExpanded = useSettings((s) => s.setArchiveExpanded);
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
  const deviceIds = useMemo(() => devices.map((d) => d.device_id), [devices]);

  const needle = query.trim();
  const { active, archive, count } = useMemo(
    () =>
      selectSessionSections(sessionsRecord, {
        includeArchived: showArchived,
        deviceId: deviceFilter,
        deviceIds,
        deviceNames,
        query: needle,
      }),
    [sessionsRecord, showArchived, deviceFilter, deviceIds, deviceNames, needle],
  );

  // A search that matches something inside the Archive opens it on its own.
  const openArchive = archiveExpanded || (needle.length > 0 && archive.length > 0);

  const openSession = (deviceId: string, sessionId: string) =>
    navigate(`/sessions/${deviceId}/${sessionId}`);
  const isOnline = (deviceId: string) =>
    devices.find((d) => d.device_id === deviceId)?.online ?? false;

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

      {count === 0 ? (
        <div className="card empty">
          <strong>{needle ? strings.sessions.noMatches : strings.sessions.empty}</strong>
          {needle ? null : strings.sessions.emptyHint}
        </div>
      ) : (
        <>
          {active.map((group) => (
            <section className="session-group" key={group.deviceId}>
              <h2 className="group-title">{deviceNames[group.deviceId] ?? group.deviceId}</h2>
              {group.sessions.length === 0 ? (
                <p className="group-empty">{strings.sessions.noOpenSessions}</p>
              ) : (
                <ul className="session-list surface">
                  {group.sessions.map((session) => (
                    <SessionRow
                      key={`${session.device_id}/${session.session_id}`}
                      session={session}
                      deviceName={deviceNames[session.device_id] ?? session.device_id}
                      online={isOnline(session.device_id)}
                      onOpen={() => openSession(session.device_id, session.session_id)}
                    />
                  ))}
                </ul>
              )}
            </section>
          ))}

          {archive.length > 0 ? (
            <section className="session-group">
              <ArchiveHeader
                count={archive.length}
                expanded={openArchive}
                onToggle={() => setArchiveExpanded(!openArchive)}
              />
              {openArchive ? (
                <ul className="session-list surface">
                  {archive.map((session) => (
                    <SessionRow
                      key={`${session.device_id}/${session.session_id}`}
                      session={session}
                      deviceName={deviceNames[session.device_id] ?? session.device_id}
                      online={isOnline(session.device_id)}
                      showDevice
                      onOpen={() => openSession(session.device_id, session.session_id)}
                    />
                  ))}
                </ul>
              ) : null}
            </section>
          ) : null}
        </>
      )}

      <NewSessionDrawer open={creating} onClose={() => setCreating(false)} />
    </>
  );
}
