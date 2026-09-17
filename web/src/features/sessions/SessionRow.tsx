import { Archive, Folder } from 'lucide-react';
import { AgentLogo } from '../../components/AgentLogo';
import { StatusDot } from '../../components/StatusDot';
import { relativeTime, tildePath } from '../../lib/format';
import { agentLabel, sessionStateLabel, sessionTitle, strings } from '../../strings';
import { useSessions } from '../../stores/sessions';
import type { Session } from '../../protocol/types';

interface Props {
  session: Session;
  online: boolean;
  onOpen: () => void;
}

export function SessionRow({ session, online, onOpen }: Props) {
  const setArchived = useSessions((s) => s.setArchived);
  // Only a session the device drives can be archived. A terminal holds its own
  // row until it exits, and a row in the Archive comes back by being written to.
  const offersArchive = session.control === 'remote' && !session.archived;
  const attention = session.state === 'needs_approval' || session.state === 'needs_input';
  const state = session.archived
    ? strings.sessions.archived
    : online
      ? sessionStateLabel(session)
      : strings.sessions.deviceOffline;

  return (
    <li className="session-row">
      {/* Three lines (docs/DESIGN.md § "The session row"): title and time,
          agent and status, then the working directory alone after a folder,
          so the path has the whole width and no line says three things. */}
      <button type="button" className="session-open" onClick={onOpen} aria-label={strings.sessions.open}>
        <span className="session-line">
          <span className="session-title">{sessionTitle(session)}</span>
          <span className="session-time">{relativeTime(session.updated_at)}</span>
        </span>
        <span className="session-line">
          <span className="agent-chip">
            <AgentLogo agent={session.agent} />
            {agentLabel(session.agent)}
          </span>
          <span className={`session-state${attention ? ' attention' : ''}`}>
            <StatusDot state={session.state} control={session.control} online={online} />
            {state}
          </span>
        </span>
        <span className="session-cwd">
          <Folder className="session-folder" strokeWidth={1.5} aria-hidden />
          {/* Right-to-left on the outside and an isolated left-to-right run
              inside: the text reads as written and the ellipsis lands at the
              head, so the folder the path ends in is what survives. */}
          <span className="session-path mono">
            <bdi>{tildePath(session.cwd)}</bdi>
          </span>
        </span>
      </button>
      {offersArchive ? (
        <button
          type="button"
          className="icon-btn session-archive"
          title={strings.sessions.archive}
          aria-label={strings.sessions.archive}
          onClick={() => void setArchived(session, true).catch(() => undefined)}
        >
          <Archive size={15} />
        </button>
      ) : null}
    </li>
  );
}
