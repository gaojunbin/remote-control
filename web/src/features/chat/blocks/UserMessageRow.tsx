import { Paperclip } from 'lucide-react';
import { strings } from '../../../strings';
import type { UserMessageEvent } from '../../../protocol/types';

export function UserMessageRow({ event }: { event: UserMessageEvent }) {
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
      </div>
    </div>
  );
}
