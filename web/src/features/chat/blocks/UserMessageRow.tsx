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
  return (
    <span className="delivery-chip">
      {isUnconfirmed(block, now) ? strings.composer.deliveryUnconfirmed : strings.chat.sending}
    </span>
  );
}
