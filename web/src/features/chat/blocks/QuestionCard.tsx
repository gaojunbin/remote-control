import { useState } from 'react';
import { cx } from '../../../lib/cx';
import { strings } from '../../../strings';
import type { QuestionAnswers, QuestionEvent, QuestionSpec } from '../../../protocol/types';

interface Props {
  event: QuestionEvent;
  onAnswer: (requestId: string, answers: QuestionAnswers) => Promise<void>;
}

export function QuestionCard({ event, onAnswer }: Props) {
  const [selection, setSelection] = useState<Record<string, string[]>>({});
  const [text, setText] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const pending = event.status === 'pending';

  function toggle(question: QuestionSpec, optionId: string): void {
    setSelection((prev) => {
      const current = prev[question.id] ?? [];
      if (!question.multi) return { ...prev, [question.id]: [optionId] };
      return {
        ...prev,
        [question.id]: current.includes(optionId)
          ? current.filter((id) => id !== optionId)
          : [...current, optionId],
      };
    });
  }

  const ready = event.questions.every((q) => {
    const picked = selection[q.id]?.length ?? 0;
    const typed = (text[q.id] ?? '').trim().length > 0;
    return picked > 0 || (q.allow_text && typed);
  });

  async function submit(): Promise<void> {
    setBusy(true);
    try {
      const answers: QuestionAnswers = {};
      for (const question of event.questions) {
        const picked = selection[question.id];
        const typed = (text[question.id] ?? '').trim();
        if (picked && picked.length > 0) answers[question.id] = picked;
        else if (typed) answers[question.id] = typed;
      }
      await onAnswer(event.request_id, answers);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className={cx('question', !pending && 'resolved')}>
      {event.questions.map((question) => (
        <div className="question-item" key={question.id}>
          <p className="question-prompt">{question.prompt}</p>
          {question.options.length > 0 ? (
            <div className="question-options">
              {question.options.map((option) => {
                const active = (selection[question.id] ?? []).includes(option.id);
                const answered = answerIncludes(event.answers?.[question.id], option.id);
                return (
                  <button
                    key={option.id}
                    type="button"
                    className={cx('question-option', (pending ? active : answered) && 'selected')}
                    disabled={!pending}
                    aria-pressed={pending ? active : answered}
                    onClick={() => toggle(question, option.id)}
                  >
                    <span>{option.label}</span>
                    {option.description ? (
                      <span className="hint">{option.description}</span>
                    ) : null}
                  </button>
                );
              })}
            </div>
          ) : null}
          {question.allow_text ? (
            <input
              className="field"
              type={question.secret ? 'password' : 'text'}
              disabled={!pending}
              placeholder={
                question.secret ? strings.chat.secretPlaceholder : strings.chat.freeTextPlaceholder
              }
              value={text[question.id] ?? ''}
              aria-label={question.prompt}
              onChange={(e) => setText((prev) => ({ ...prev, [question.id]: e.target.value }))}
            />
          ) : null}
        </div>
      ))}
      {pending ? (
        <div className="approval-actions">
          <button
            type="button"
            className="btn small primary"
            disabled={!ready || busy}
            onClick={() => void submit()}
          >
            {strings.chat.submitAnswer}
          </button>
        </div>
      ) : (
        <p className="approval-result hint">
          {event.status === 'expired' ? strings.chat.questionExpired : strings.chat.questionResolved}
        </p>
      )}
    </section>
  );
}

function answerIncludes(answer: string[] | string | undefined, optionId: string): boolean {
  if (Array.isArray(answer)) return answer.includes(optionId);
  return false;
}
