import { Paperclip } from 'lucide-react';
import { cx } from '../../../lib/cx';
import { useNow } from '../../../lib/useNow';
import { strings } from '../../../strings';
import { deliveryLabel } from '../attach';
import { isUnconfirmed, type OptimisticBlock } from '../../../stores/timeline';
import type { UserMessageEvent } from '../../../protocol/types';

export function UserMessageRow({
  event,
  pending,
}: {
  event: UserMessageEvent;
  /** A12: set while this app is still waiting for the device to echo the send. */
  pending?: OptimisticBlock;
}) {
  // A10: on a shared session the device may still be holding this message.
  const delivery = deliveryLabel(event.delivery);
  return (
    <div className="user-row">
      <div className={cx('user-bubble', pending !== undefined && 'pending')}>
        {event.source === 'terminal' ? <span className="user-origin">terminal</span> : null}
        <p>{event.text}</p>
        {event.attachments && event.attachments.length > 0 ? (
          <p className="user-attachments">
            <Paperclip size={12} aria-hidden />
            {strings.chat.attachments(event.attachments.length)}
          </p>
        ) : null}
        {pending ? <PendingChip block={pending} /> : null}
        {!pending && delivery ? <span className="delivery-chip">{delivery}</span> : null}
      </div>
    </div>
  );
}

/** The chip on a send the device has not confirmed, re-read on a slow clock. */
function PendingChip({ block }: { block: OptimisticBlock }) {
  const now = useNow(5_000);
  return <span className="delivery-chip">{pendingLabel(block, now)}</span>;
}

/**
 * A14: a steered message is accepted at once but only reaches the agent at its
 * next step, so it says so instead of counting the seconds towards a delivery
 * problem it does not have.
 */
function pendingLabel(block: OptimisticBlock, now: number): string {
  if (block.accepted === 'steered') return strings.chat.steering;
  if (isUnconfirmed(block, now)) return strings.composer.deliveryUnconfirmed;
  return strings.chat.sending;
}
