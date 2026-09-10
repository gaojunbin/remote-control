import { agentLabel, strings } from '../../strings';
import type { AgentInfo, Session } from '../../protocol/types';

interface Props {
  session: Session;
  agent: AgentInfo | null;
  deviceOnline: boolean;
  onTakeover: () => void;
}

/** The single line under the timeline that explains what happens next. */
export function StatusLine({ session, agent, deviceOnline, onTakeover }: Props) {
  const name = agentLabel(session.agent);
  const canSteer = agent?.capabilities.includes('steer') ?? false;
  const canTakeover = agent?.capabilities.includes('takeover') ?? false;

  if (!deviceOnline) return <Line tone="muted" text={strings.status.offline} />;

  if (session.control === 'terminal') {
    const busy = session.state === 'running' || session.state === 'starting';
    return (
      <Line
        tone={busy ? 'running' : 'muted'}
        text={
          busy
            ? `${strings.status.terminalControlled} · ${strings.status.terminalBusy}`
            : strings.status.terminalControlled
        }
      >
        {canTakeover ? (
          <button type="button" className="link-btn" onClick={onTakeover}>
            {strings.chat.takeOver}
          </button>
        ) : null}
      </Line>
    );
  }

  switch (session.state) {
    case 'running':
      return (
        <Line
          tone="running"
          text={canSteer ? strings.status.workingSteer(name) : strings.status.workingQueued(name)}
        />
      );
    case 'starting':
      return <Line tone="running" text={strings.status.starting} />;
    case 'needs_approval':
      return <Line tone="attention" text={strings.status.needsApproval} />;
    case 'needs_input':
      return <Line tone="attention" text={strings.status.needsInput} />;
    case 'error':
      return <Line tone="error" text={session.state_detail ?? strings.status.errored} />;
    case 'stopped':
      return <Line tone="muted" text={strings.status.stopped} />;
    default:
      return null;
  }
}

function Line({
  tone,
  text,
  children,
}: {
  tone: 'running' | 'attention' | 'error' | 'muted';
  text: string;
  children?: React.ReactNode;
}) {
  return (
    <p className={`status-line ${tone}`} aria-live="polite">
      <span className={`dot ${tone === 'muted' ? '' : tone}${tone === 'running' ? ' pulse' : ''}`} aria-hidden />
      {text}
      {children}
    </p>
  );
}
