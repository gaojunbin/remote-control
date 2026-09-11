import { cx } from '../lib/cx';
import { dotTone } from './dotTone';
import { dotToneLabel, stateLabel } from '../strings';
import type { ControlOwner, SessionState } from '../protocol/types';

/**
 * A session's dot. The tone comes from `dotTone`, which reads the state, the
 * control owner and the device together; the accessibility label stays the raw
 * state and the tooltip names the tone.
 */
export function StatusDot({
  state,
  control,
  online = true,
  className,
}: {
  state: SessionState;
  control: ControlOwner;
  online?: boolean;
  className?: string;
}) {
  const tone = dotTone(state, control, online);
  return (
    <span
      className={cx('dot', tone, className)}
      role="img"
      aria-label={stateLabel(state)}
      title={dotToneLabel(tone)}
    />
  );
}

/** A device's own dot. Online or not, nothing else. */
export function OnlineDot({ online }: { online: boolean }) {
  return (
    <span
      className={cx('dot', online ? 'running' : 'offline')}
      role="img"
      aria-label={online ? 'online' : 'offline'}
    />
  );
}
