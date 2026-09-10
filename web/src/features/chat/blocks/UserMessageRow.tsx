import { Paperclip } from 'lucide-react';
import { strings } from '../../../strings';
import { deliveryLabel } from '../attach';
import type { UserMessageEvent } from '../../../protocol/types';

export function UserMessageRow({ event }: { event: UserMessageEvent }) {
  // A10: on a shared session the device may still be holding this message.
  const delivery = deliveryLabel(event.delivery);
  return (
    <div className="user-row">
      <div className="user-bubble">
        {event.source === 'terminal' ? <span className="user-origin">terminal</span> : null}
        <p>{event.text}</p>
        {event.attachments && event.attachments.length > 0 ? (
          <p className="user-attachments">
            <Paperclip size={12} aria-hidden />
            {strings.chat.attachments(event.attachments.length)}
          </p>
        ) : null}
        {delivery ? <span className="delivery-chip">{delivery}</span> : null}
      </div>
    </div>
  );
}
