import { cx } from '../lib/cx';
import { stateLabel } from '../strings';
import type { SessionState } from '../protocol/types';

const TONE: Record<SessionState, string> = {
  starting: 'running',
  idle: '',
  running: 'running',
  needs_approval: 'attention',
  needs_input: 'attention',
  error: 'error',
  stopped: '',
  readonly: '',
};

export function StatusDot({
  state,
  online = true,
  className,
}: {
  state: SessionState;
  online?: boolean;
  className?: string;
}) {
  const tone = online ? TONE[state] : 'offline';
  const animate = online && (state === 'running' || state === 'starting');
  return (
    <span
      className={cx('dot', tone, animate && 'pulse', className)}
      role="img"
      aria-label={stateLabel(state)}
    />
  );
}

export function OnlineDot({ online }: { online: boolean }) {
  return (
    <span
      className={cx('dot', online ? 'running' : 'offline')}
      role="img"
      aria-label={online ? 'online' : 'offline'}
    />
  );
}
