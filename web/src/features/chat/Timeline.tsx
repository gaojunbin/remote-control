import { memo, useCallback, useMemo, type ReactNode } from 'react';
import { ArrowDown } from 'lucide-react';
import { strings } from '../../strings';
import { selectView, type TimelineItem, type TimelineState } from '../../stores/timeline';
import type {
  ApprovalEvent,
  AssistantTextEvent,
  ErrorEvent,
  NoticeEvent,
  QuestionAnswers,
  QuestionEvent,
  ThinkingEvent,
  ToolCallEvent,
  TurnCompletedEvent,
  UserMessageEvent,
} from '../../protocol/types';
import { MarkdownText } from './MarkdownText';
import { ApprovalCard } from './blocks/ApprovalCard';
import { ErrorRow, NoticeRow, TurnEndRow } from './blocks/NoticeRow';
import { QuestionCard } from './blocks/QuestionCard';
import { ThinkingRow } from './blocks/ThinkingRow';
import { ToolRow } from './blocks/ToolRow';
import { UserMessageRow } from './blocks/UserMessageRow';
import { useScrollFollow } from './useScrollFollow';

export interface TimelineHandlers {
  onOpenFull: (blockId: string) => Promise<void>;
  onApprove: (requestId: string, optionId: string) => Promise<void>;
  onAnswer: (requestId: string, answers: QuestionAnswers) => Promise<void>;
  onLoadOlder: () => void;
}

interface Props extends TimelineHandlers {
  timeline: TimelineState;
  historyLoading: boolean;
  historyHasMore: boolean;
  footer?: ReactNode;
}

export function Timeline({
  timeline,
  historyLoading,
  historyHasMore,
  footer,
  onOpenFull,
  onApprove,
  onAnswer,
  onLoadOlder,
}: Props) {
  const view = useMemo(() => selectView(timeline), [timeline]);

  const onReachTop = useCallback(() => {
    if (historyHasMore && !historyLoading) onLoadOlder();
  }, [historyHasMore, historyLoading, onLoadOlder]);

  const { ref, following, missed, scrollToBottom, onScroll } = useScrollFollow({
    revision: timeline.lastSeq,
    firstKey: timeline.order[0] ?? null,
    lastKey: timeline.order.at(-1) ?? null,
    onReachTop,
  });

  // Stable so memoised rows do not re-render on every streaming delta.
  const handlers = useMemo<TimelineHandlers>(
    () => ({ onOpenFull, onApprove, onAnswer, onLoadOlder }),
    [onOpenFull, onApprove, onAnswer, onLoadOlder],
  );

  return (
    <div className="timeline-wrap">
      <div className="timeline scroll-thin" ref={ref} onScroll={onScroll}>
        <div className="timeline-inner">
          {historyLoading ? (
            <p className="timeline-note">{strings.chat.loadingHistory}</p>
          ) : !historyHasMore && timeline.order.length > 0 ? (
            <p className="timeline-note">{strings.chat.historyStart}</p>
          ) : null}

          {timeline.order.length === 0 && !historyLoading ? (
            <p className="timeline-empty hint">{strings.chat.emptyTimeline}</p>
          ) : null}

          {view.roots.map((item) => (
            <ItemView
              key={item.key}
              item={item}
              handlers={handlers}
              nested={view.children[item.key]}
            />
          ))}
          {footer}
        </div>
      </div>

      {!following && missed > 0 ? (
        <button type="button" className="back-to-latest" onClick={() => scrollToBottom('smooth')}>
          <ArrowDown size={13} aria-hidden />
          {strings.chat.backToLatest} · {strings.chat.newUpdates(missed)}
        </button>
      ) : null}
    </div>
  );
}

interface ItemProps {
  item: TimelineItem;
  handlers: TimelineHandlers;
  nested?: TimelineItem[];
}

/**
 * Rows are memoised on the item identity the reducer preserves for untouched
 * blocks, so a delta re-renders only the block it belongs to.
 */
const ItemView = memo(function ItemView({ item, handlers, nested }: ItemProps) {
  const event = item.event;
  switch (event.kind) {
    case 'user_message':
      return <UserMessageRow event={event as UserMessageEvent} />;
    case 'assistant_text': {
      const text = (event as AssistantTextEvent).text ?? '';
      return text ? <MarkdownText text={text} /> : null;
    }
    case 'thinking':
      return <ThinkingRow event={event as ThinkingEvent} />;
    case 'tool_call':
      return (
        <ToolRow event={event as ToolCallEvent} onOpenFull={handlers.onOpenFull}>
          {nested?.map((child) => (
            <ItemView key={child.key} item={child} handlers={handlers} />
          ))}
        </ToolRow>
      );
    case 'approval':
      return <ApprovalCard event={event as ApprovalEvent} onDecide={handlers.onApprove} />;
    case 'question':
      return <QuestionCard event={event as QuestionEvent} onAnswer={handlers.onAnswer} />;
    case 'notice':
      return <NoticeRow event={event as NoticeEvent} />;
    case 'error':
      return <ErrorRow event={event as ErrorEvent} />;
    case 'turn_completed':
      return <TurnEndRow event={event as TurnCompletedEvent} />;
    default:
      return null;
  }
});
