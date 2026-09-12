import { Archive } from 'lucide-react';
import { StatusDot } from '../../components/StatusDot';
import { cx } from '../../lib/cx';
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
      <button type="button" className="session-open" onClick={onOpen} aria-label={strings.sessions.open}>
        <StatusDot state={session.state} control={session.control} online={online} />
        <span className="session-text">
          <span className="session-title">{sessionTitle(session)}</span>
          <span className="session-meta">
            <span className={cx('agent-chip', session.agent)}>{agentLabel(session.agent)}</span>
            <span className="session-sub mono">{tildePath(session.cwd)}</span>
          </span>
        </span>
        <span className={`session-state${attention ? ' attention' : ''}`}>
          {state}
          <span className="session-time"> · {relativeTime(session.updated_at)}</span>
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
