import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { Button } from '../../components/Button';
import { Drawer } from '../../components/Modal';
import { Menu } from '../../components/Popover';
import { Segmented } from '../../components/Segmented';
import { Switch } from '../../components/Switch';
import { OnlineDot } from '../../components/StatusDot';
import { latency, relativeAgo, tildePath } from '../../lib/format';
import { rpc } from '../../lib/gateway';
import { agentLabel, agentMark, strings } from '../../strings';
import { useDevices } from '../../stores/devices';
import { useSessions } from '../../stores/sessions';
import type { AgentInfo, Device } from '../../protocol/types';
import { DirectoryPicker } from './DirectoryPicker';
import { useDirectoryProbe, type DirStatus } from './useDirectoryProbe';

interface Props {
  open: boolean;
  onClose: () => void;
  presetDeviceId?: string;
}

/**
 * Mounted only while open so every field starts from a fresh, derived default
 * instead of being reset from an effect.
 */
export function NewSessionDrawer({ open, onClose, presetDeviceId }: Props) {
  const devices = useDevices((s) => s.devices);
  const online = useMemo(() => devices.filter((d) => d.online), [devices]);
  if (!open) return null;
  return (
    <NewSessionForm
      devices={online}
      onClose={onClose}
      {...(presetDeviceId ? { presetDeviceId } : {})}
    />
  );
}

interface FormProps {
  devices: Device[];
  presetDeviceId?: string;
  onClose: () => void;
}

function NewSessionForm({ devices, presetDeviceId, onClose }: FormProps) {
  const createSession = useSessions((s) => s.create);
  const navigate = useNavigate();

  const [deviceId, setDeviceId] = useState<string | null>(() => {
    if (presetDeviceId && devices.some((d) => d.device_id === presetDeviceId)) return presetDeviceId;
    return devices[0]?.device_id ?? null;
  });

  const device = useMemo(
    () => devices.find((d) => d.device_id === deviceId) ?? null,
    [devices, deviceId],
  );
  const agents = useMemo(() => device?.agents ?? [], [device]);

  const [pickedAgent, setPickedAgent] = useState<string | null>(null);
  const agent: AgentInfo | null = useMemo(() => {
    const explicit = agents.find((a) => a.agent === pickedAgent);
    return explicit ?? agents.find((a) => a.available) ?? agents[0] ?? null;
  }, [agents, pickedAgent]);

  const [cwd, setCwd] = useState('');
  const [cwdTouched, setCwdTouched] = useState(false);
  const [worktree, setWorktree] = useState(false);
  const [firstMessage, setFirstMessage] = useState('');
  const [recent, setRecent] = useState<{ path: string; last_used: number }[]>([]);
  const [browsing, setBrowsing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Load the device's home listing to seed the recent list and the default cwd.
  useEffect(() => {
    if (!deviceId) return;
    let cancelled = false;
    void rpc('device.dirs', { device_id: deviceId })
      .then((result) => {
        if (cancelled) return;
        setRecent(result.recent);
        setCwd((current) => (current.length > 0 ? current : (result.recent[0]?.path ?? result.path)));
      })
      .catch(() => {
        if (!cancelled) setRecent([]);
      });
    return () => {
      cancelled = true;
    };
  }, [deviceId]);

  const probe = useDirectoryProbe(deviceId, cwd);
  const canWorktree = agent?.capabilities.includes('worktree') ?? false;
  const canStart =
    !busy && device !== null && agent !== null && agent.available && cwd.trim().length > 0;

  const start = useCallback(async () => {
    if (!canStart || !device || !agent) return;
    setBusy(true);
    setError(null);
    try {
      const session = await createSession({
        device_id: device.device_id,
        agent: agent.agent,
        cwd: cwd.trim(),
        ...(agent.default_model ? { model: agent.default_model } : {}),
        ...(agent.default_permission_mode
          ? { permission_mode: agent.default_permission_mode }
          : {}),
        ...(agent.default_effort ? { effort: agent.default_effort } : {}),
        ...(canWorktree && worktree ? { worktree: true } : {}),
        ...(firstMessage.trim() ? { first_message: firstMessage.trim() } : {}),
      });
      onClose();
      navigate(`/sessions/${session.device_id}/${session.session_id}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : strings.newSession.startFailed);
    } finally {
      setBusy(false);
    }
  }, [canStart, device, agent, cwd, canWorktree, worktree, firstMessage, createSession, onClose, navigate]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
        e.preventDefault();
        void start();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [start]);

  const git = probe.git;

  return (
    <Drawer
      open
      onClose={onClose}
      title={strings.newSession.title}
      subtitle={device ? strings.newSession.continuingOn(device.name) : strings.newSession.pickDevice}
      footer={
        <>
          {error ? <p className="login-error">{error}</p> : null}
          <Button variant="primary" block busy={busy} disabled={!canStart} onClick={() => void start()}>
            {busy ? strings.newSession.starting : strings.newSession.start}
            <kbd className="kbd-hint">⌘↵</kbd>
          </Button>
        </>
      }
    >
      <section>
        <span className="label">{strings.newSession.device}</span>
        <Menu
          ariaLabel={strings.newSession.device}
          align="start"
          disabled={devices.length === 0}
          value={deviceId}
          onSelect={(id) => {
            setDeviceId(id);
            setPickedAgent(null);
            if (!cwdTouched) setCwd('');
          }}
          options={devices.map((d) => ({
            id: d.device_id,
            label: d.name,
            description: latency(d.latency_ms),
          }))}
          label={
            <span className="select-row">
              <OnlineDot online={device?.online ?? false} />
              <span className="select-name">{device?.name ?? strings.newSession.noDevices}</span>
              <span className="mono select-aux">{device ? latency(device.latency_ms) : ''}</span>
            </span>
          }
        />
      </section>

      <section>
        <span className="label">{strings.newSession.agent}</span>
        <Segmented<string>
          ariaLabel={strings.newSession.agent}
          value={agent?.agent ?? ''}
          onChange={setPickedAgent}
          options={agents.map((a) => ({
            value: a.agent,
            disabled: !a.available,
            ...(a.available ? {} : { title: strings.newSession.agentUnavailable }),
            label: (
              <span className="agent-option">
                <span className="agent-mark" aria-hidden>
                  {agentMark(a.agent)}
                </span>
                {agentLabel(a.agent)}
              </span>
            ),
          }))}
        />
        {agent ? (
          <p className="mono agent-line">
            {agent.agent}
            {agent.version ? ` ${agent.version}` : ''}
            {agent.default_model ? ` · ${agent.default_model}` : ''}
          </p>
        ) : null}
      </section>

      <section>
        <div className="label-row">
          <span className="label">{strings.newSession.workingDirectory}</span>
          <button
            type="button"
            className="link-btn"
            onClick={() => setBrowsing(true)}
            disabled={!deviceId}
          >
            {strings.newSession.browse}
          </button>
        </div>
        <div className="dir-field">
          <input
            className="field mono"
            value={cwd}
            spellCheck={false}
            aria-label={strings.newSession.workingDirectory}
            onChange={(e) => {
              setCwd(e.target.value);
              setCwdTouched(true);
            }}
          />
          <span className={`dir-status ${probe.status}`}>{dirStatusLabel(probe.status)}</span>
        </div>
        {recent.length > 0 ? (
          <ul className="recent-list">
            {recent.slice(0, 4).map((entry) => (
              <li key={entry.path}>
                <button
                  type="button"
                  onClick={() => {
                    setCwd(entry.path);
                    setCwdTouched(true);
                  }}
                >
                  <span className="mono">{tildePath(entry.path)}</span>
                  <span className="hint">{relativeAgo(entry.last_used)}</span>
                </button>
              </li>
            ))}
          </ul>
        ) : null}
      </section>

      <section>
        <span className="label">{strings.newSession.git}</span>
        <div className="git-row">
          {git?.is_repo ? (
            <>
              <span className="mono git-branch">{git.branch}</span>
              <span className="hint">
                {git.dirty ? strings.newSession.gitDirty : strings.newSession.gitClean}
                {typeof git.ahead === 'number' ? ` · ${strings.newSession.gitAhead(git.ahead)}` : ''}
              </span>
            </>
          ) : (
            <span className="hint">{strings.newSession.notARepo}</span>
          )}
          {canWorktree && git?.is_repo ? (
            <div className="git-worktree">
              <span>{strings.newSession.isolateWorktree}</span>
              <Switch
                checked={worktree}
                onChange={setWorktree}
                label={strings.newSession.isolateWorktree}
              />
            </div>
          ) : null}
        </div>
      </section>

      <section>
        <span className="label">
          {strings.newSession.firstMessage} <em>{strings.common.optional}</em>
        </span>
        <textarea
          className="field first-message"
          rows={4}
          placeholder={strings.newSession.firstMessagePlaceholder}
          value={firstMessage}
          aria-label={strings.newSession.firstMessage}
          onChange={(e) => setFirstMessage(e.target.value)}
        />
      </section>

      <DirectoryPicker
        open={browsing}
        deviceId={deviceId}
        {...(cwd.trim() ? { startPath: cwd.trim() } : {})}
        onClose={() => setBrowsing(false)}
        onPick={(path) => {
          setCwd(path);
          setCwdTouched(true);
        }}
      />
    </Drawer>
  );
}

function dirStatusLabel(status: DirStatus): string {
  switch (status) {
    case 'exists':
      return strings.newSession.dirExists;
    case 'missing':
      return strings.newSession.dirMissing;
    case 'checking':
      return strings.newSession.dirChecking;
    default:
      return '';
  }
}
