import { AlertTriangle, CircleAlert, Info } from 'lucide-react';
import { strings } from '../../../strings';
import type { ErrorEvent, NoticeEvent, TurnCompletedEvent } from '../../../protocol/types';

export function NoticeRow({ event }: { event: NoticeEvent }) {
  const Icon = event.level === 'error' ? CircleAlert : event.level === 'warn' ? AlertTriangle : Info;
  return (
    <p className={`notice ${event.level}`}>
      <Icon size={13} aria-hidden />
      {event.text}
    </p>
  );
}

export function ErrorRow({ event }: { event: ErrorEvent }) {
  return (
    <p className="notice error" role="alert">
      <CircleAlert size={13} aria-hidden />
      {event.message}
      {event.code ? <span className="notice-code mono">{event.code}</span> : null}
    </p>
  );
}

export function TurnEndRow({ event }: { event: TurnCompletedEvent }) {
  const label =
    event.stop_reason === 'interrupted' ? strings.chat.turnInterrupted : strings.chat.turnFailed;
  return (
    <p className={`notice ${event.stop_reason === 'interrupted' ? 'info' : 'error'}`}>
      <AlertTriangle size={13} aria-hidden />
      {label}
    </p>
  );
}
