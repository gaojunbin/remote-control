import { Archive, ArchiveRestore } from 'lucide-react';
import { StatusDot } from '../../components/StatusDot';
import { cx } from '../../lib/cx';
import { relativeTime, tildePath } from '../../lib/format';
import { agentLabel, sessionStateLabel, strings } from '../../strings';
import { useSessions } from '../../stores/sessions';
import type { Session } from '../../protocol/types';

interface Props {
  session: Session;
  online: boolean;
  onOpen: () => void;
}

export function SessionRow({ session, online, onOpen }: Props) {
  const setArchived = useSessions((s) => s.setArchived);
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
          <span className="session-title">{session.title}</span>
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
      <button
        type="button"
        className="icon-btn session-archive"
        title={session.archived ? strings.sessions.unarchive : strings.sessions.archive}
        aria-label={session.archived ? strings.sessions.unarchive : strings.sessions.archive}
        onClick={() => void setArchived(session, !session.archived).catch(() => undefined)}
      >
        {session.archived ? <ArchiveRestore size={15} /> : <Archive size={15} />}
      </button>
    </li>
  );
}
