import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react';
import { ArrowUp, ChevronRight, Mic, Paperclip, X, Zap } from 'lucide-react';
import { Menu, Popover } from '../../components/Popover';
import { bytes } from '../../lib/format';
import { cx } from '../../lib/cx';
import { agentLabel, languageLabel, strings } from '../../strings';
import { useSettings } from '../../stores/settings';
import type { SendMode } from '../../protocol/frames';
import type {
  AgentInfo,
  Choice,
  QuestionAnswers,
  QuestionEvent,
  QueuedMessage,
  Session,
} from '../../protocol/types';
import { draftOf, useAnswers } from '../../stores/answers';
import { VoiceControls } from '../voice/VoiceControls';
import { mergeDraft } from '../voice/draft';
import { useVoice } from '../voice/useVoice';
import { composeAnswer } from './answering';
import { attachHint, canAttachShared, canInterruptShared, canSetShared } from './attach';
import { readAttachments, textTooLong, type AttachmentDraft } from './attachments';

interface Props {
  session: Session;
  agent: AgentInfo | null;
  deviceOnline: boolean;
  queue: QueuedMessage[];
  /** A20: the question the session is waiting on, when there is one. */
  question: QuestionEvent | null;
  sttEnabled: boolean;
  sttLanguages: string[];
  onSend: (text: string, attachments: AttachmentDraft[], mode: SendMode) => Promise<void>;
  onAnswer: (requestId: string, answers: QuestionAnswers) => Promise<void>;
  onSetOption: (patch: SessionOptions) => void;
  onRemoveQueued: (queuedId: string) => void;
  onTakeover: () => void;
}

export function Composer({
  session,
  agent,
  deviceOnline,
  queue,
  question,
  sttEnabled,
  sttLanguages,
  onSend,
  onAnswer,
  onSetOption,
  onRemoveQueued,
  onTakeover,
}: Props) {
  const [text, setText] = useState('');
  const [attachments, setAttachments] = useState<AttachmentDraft[]>([]);
  const [errors, setErrors] = useState<string[]>([]);
  const composing = useRef(false);
  const textarea = useRef<HTMLTextAreaElement>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const textRef = useRef('');
  /**
   * The draft dictation started from, and the value it last wrote. A transcript
   * arrives between renders, so the field is read from this ref rather than
   * from state, and `applied` is how a keystroke that landed in between is told
   * apart from our own write.
   */
  const dictation = useRef<{ base: string; applied: string } | null>(null);

  /** Keeps `textRef` in step within the tick, which `useEffect` cannot. */
  const setDraft = useCallback((value: string) => {
    textRef.current = value;
    setText(value);
  }, []);

  useEffect(() => {
    textRef.current = text;
  }, [text]);

  const language = useSettings((s) => s.sttLanguage);
  const setLanguage = useSettings((s) => s.setSttLanguage);
  // A20: what the card on screen already holds, so the draft completes it
  // rather than competing with it.
  const answerDraft = useAnswers(draftOf(question?.request_id ?? ''));
  const clearAnswer = useAnswers((s) => s.clear);

  const terminalControlled = session.control === 'terminal';
  // A10: a shared session is a live CLI the device is attached to. Everything
  // the composer does works, except what has to go through the terminal.
  const shared = session.control === 'shared';
  const running =
    session.state === 'running' ||
    session.state === 'needs_approval' ||
    session.state === 'needs_input' ||
    session.state === 'starting';
  const canSteer = agent?.capabilities.includes('steer') ?? false;
  const canTakeover = agent?.capabilities.includes('takeover') ?? false;
  // A7: the composer is gated on `control`, never on `state` — a mirrored
  // session reports `running` while the terminal drives the turn.
  const disabled = terminalControlled || !deviceOnline;
  // A10: the channel cannot interrupt a running turn, so neither can we.
  const canInterrupt = shared ? canInterruptShared(agent) : true;
  // A11: the model, permission mode, effort and attachments belong to the
  // terminal unless the device reports that the attachment carries them. A
  // control the attachment cannot drive is hidden, never disabled with a
  // caption, so nothing in the composer explains an absence.
  //
  // A17: where the pickers cannot go — a terminal session, which refuses
  // `session.set` outright, and a shared one whose agent does not carry the
  // settings — the values the device read from the transcript are shown
  // instead, so the person can at least see what the terminal chose.
  const showOptions = !terminalControlled && (!shared || canSetShared(agent));
  const showAttach = !shared || canAttachShared(agent);

  // A20: while a question is pending the field is that question's free-text
  // answer, so nothing is sent and nothing is queued behind it.
  const answering = question !== null && !disabled;
  const answer = answering ? composeAnswer(question, answerDraft, text) : null;

  /**
   * A20: submit the draft as the free-text answer of the first question with
   * no selection, alongside whatever the card holds. There is no optimistic
   * row: an answer is not a message, and the card resolving is the receipt.
   */
  const submitAnswer = useCallback(() => {
    if (!question || answer === null) return;
    const requestId = question.request_id;
    const value = text;
    setDraft('');
    setErrors([]);
    onAnswer(requestId, answer)
      .then(() => clearAnswer(requestId))
      // The page reports the failure; a newer draft wins, as it does for a send.
      .catch(() => {
        if (textRef.current.length === 0) setDraft(value);
      });
  }, [question, answer, text, onAnswer, clearAnswer, setDraft]);

  /**
   * A12: the field is cleared and the message is put in the timeline in this
   * tick, before the request leaves. Only a refusal the gateway is certain
   * about comes back, and it hands the draft back if nothing was typed since.
   */
  const submit = useCallback(
    (mode: SendMode, source?: string) => {
      const value = (source ?? text).trim();
      if (disabled || (value.length === 0 && attachments.length === 0)) return;
      if (textTooLong(value)) {
        setErrors([strings.composer.textTooLong]);
        return;
      }
      const files = attachments;
      setDraft('');
      setAttachments([]);
      setErrors([]);
      onSend(value, files, mode).catch((err: unknown) => {
        setErrors([err instanceof Error ? err.message : strings.composer.sendFailed]);
        // A newer draft wins: a refusal only ever refills a field left empty.
        if (textRef.current.length === 0) setDraft(value);
        setAttachments((current) => (current.length === 0 ? files : current));
      });
    },
    [text, attachments, disabled, onSend, setDraft],
  );

  /**
   * Dictation writes into this field and nothing else: no utterance is sent by
   * the act of stopping the recording, and a failure keeps the words it did
   * recognise rather than dropping them.
   */
  const voice = useVoice({
    enabled: sttEnabled && !disabled,
    language,
    onTranscript: (transcript, isFinal) => {
      const run = dictation.current;
      if (!run || textRef.current !== run.applied) return;
      const next = mergeDraft(run.base, transcript);
      run.applied = next;
      if (isFinal) dictation.current = null;
      setDraft(next);
    },
  });

  const voiceBusy =
    voice.state === 'starting' || voice.state === 'listening' || voice.state === 'finishing';

  const startVoice = () => {
    dictation.current = { base: textRef.current, applied: textRef.current };
    voice.start();
  };

  /** A keystroke takes the field back: the words so far stay, dictation stops. */
  const stopDictationForTyping = () => {
    if (!voiceBusy) return;
    dictation.current = null;
    voice.cancel();
  };

  useEffect(() => {
    const el = textarea.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, 220)}px`;
  }, [text]);

  // PROTOCOL-FROZEN §5: `auto` is "send now if idle; if running, steer or queue".
  // Forcing `queue` here would make the `steer` capability unreachable.
  const primaryMode: SendMode = 'auto';
  const primaryLabel = answering
    ? strings.composer.answer
    : running
      ? canSteer
        ? strings.composer.send
        : strings.composer.queue
      : strings.composer.send;
  const primarySubmit = () => (answering ? submitAnswer() : submit(primaryMode));
  const primaryDisabled = answering
    ? answer === null
    : disabled || (text.trim().length === 0 && attachments.length === 0);

  // A10 §8: only a `terminal` session can be missing its attachment.
  const hint = terminalControlled ? attachHint(agent) : null;

  const placeholder = terminalControlled
    ? strings.composer.placeholderTerminal
    : !deviceOnline
      ? strings.composer.placeholderOffline
      : answering
        ? strings.composer.placeholderAnswer
        : running
          ? canSteer
            ? strings.composer.placeholderSteer
            : strings.composer.placeholderQueued
          : strings.composer.placeholder;

  return (
    <div className="composer-wrap">
      {queue.length > 0 ? (
        <ul className="queue-list">
          {queue.map((item) => (
            <li key={item.id}>
              <span className="badge">{strings.chat.queuedLabel}</span>
              <span className="queue-text">{item.text}</span>
              <button
                type="button"
                className="icon-btn"
                aria-label={strings.chat.queuedRemove}
                onClick={() => onRemoveQueued(item.id)}
              >
                <X size={14} />
              </button>
            </li>
          ))}
        </ul>
      ) : null}

      {attachments.length > 0 ? (
        <ul className="attachment-list">
          {attachments.map((file, index) => (
            <li key={`${file.name}-${index}`}>
              <Paperclip size={12} aria-hidden />
              <span className="mono">{file.name}</span>
              <span className="hint">{bytes(file.size)}</span>
              <button
                type="button"
                className="icon-btn"
                aria-label={strings.common.remove}
                onClick={() => setAttachments((list) => list.filter((_, i) => i !== index))}
              >
                <X size={13} />
              </button>
            </li>
          ))}
        </ul>
      ) : null}

      {errors.length > 0 ? (
        <div className="composer-errors" role="alert">
          {errors.map((message) => (
            <p key={message}>{message}</p>
          ))}
        </div>
      ) : null}

      {voice.state === 'error' && voice.error ? (
        <div className="composer-errors voice-error" role="alert">
          <p>{voice.error}</p>
          <button type="button" className="link-btn" onClick={voice.dismissError}>
            {strings.common.dismiss}
          </button>
        </div>
      ) : null}

      {terminalControlled ? (
        <div className="takeover-bar">
          <span className="takeover-text">
            {strings.status.terminalControlled}
            {hint ? <span className="takeover-hint">{hint}</span> : null}
          </span>
          {canTakeover ? (
            <button type="button" className="btn small" onClick={onTakeover}>
              {strings.chat.takeOver}
            </button>
          ) : null}
        </div>
      ) : null}

      {voiceBusy ? (
        <p className="voice-status hint">
          {voice.state === 'starting' ? strings.voice.connecting : strings.voice.transcribing}
        </p>
      ) : null}

      <div className={cx('composer', disabled && 'disabled', voiceBusy && 'listening')}>
        <textarea
          ref={textarea}
          className="composer-input"
          rows={1}
          value={text}
          placeholder={placeholder}
          disabled={disabled}
          aria-label={strings.composer.placeholder}
          onCompositionStart={() => (composing.current = true)}
          onCompositionEnd={() => (composing.current = false)}
          onChange={(e) => {
            stopDictationForTyping();
            setDraft(e.target.value);
          }}
          onPaste={(e) => {
            const files = showAttach ? [...e.clipboardData.files] : [];
            if (files.length > 0) {
              e.preventDefault();
              void attach(files);
            }
          }}
          onKeyDown={(e) => {
            if (e.key !== 'Enter' || e.shiftKey || composing.current || e.nativeEvent.isComposing) return;
            e.preventDefault();
            primarySubmit();
          }}
        />
        {voiceBusy ? (
          <VoiceControls voice={voice} onDone={voice.done} />
        ) : (
          <div className="composer-buttons">
          {showAttach ? (
            <>
              <input
                ref={fileInput}
                type="file"
                multiple
                className="sr-only"
                aria-label={strings.composer.attach}
                onChange={(e) => {
                  void attach([...(e.target.files ?? [])]);
                  e.target.value = '';
                }}
              />
              <button
                type="button"
                className="icon-btn"
                aria-label={strings.composer.attach}
                disabled={disabled}
                onClick={() => fileInput.current?.click()}
              >
                <Paperclip size={16} />
              </button>
            </>
          ) : null}
          {sttEnabled ? (
            <button
              type="button"
              className="icon-btn"
              aria-label={strings.composer.micStart}
              disabled={disabled}
              onClick={startVoice}
            >
              <Mic size={16} />
            </button>
          ) : null}
          {running && canInterrupt && !answering ? (
            <Popover
              align="end"
              side="top"
              chevron={false}
              ariaLabel={strings.composer.sendOptions}
              triggerClassName="send-alt"
              label={<span aria-hidden>⋯</span>}
            >
              {(close) => (
                <ul className="menu">
                  <li>
                    <button
                      type="button"
                      className="menu-item"
                      onClick={() => {
                        close();
                        submit('interrupt');
                      }}
                    >
                      <span className="menu-label">{strings.composer.interruptAndSend}</span>
                    </button>
                  </li>
                </ul>
              )}
            </Popover>
          ) : null}
          <button
            type="button"
            className="btn primary small send-btn"
            disabled={primaryDisabled}
            onClick={primarySubmit}
          >
              {running ? primaryLabel : <ArrowUp size={15} aria-hidden />}
              {running ? null : <span className="sr-only">{strings.composer.send}</span>}
            </button>
          </div>
        )}
      </div>

      <ComposerBottomRow
        agent={agent}
        session={session}
        showOptions={showOptions}
        language={language}
        sttEnabled={sttEnabled}
        sttLanguages={sttLanguages}
        onSetOption={onSetOption}
        onSetLanguage={setLanguage}
      />
    </div>
  );

  async function attach(files: File[]): Promise<void> {
    const result = await readAttachments(files, attachments.length);
    setAttachments((list) => [...list, ...result.attachments]);
    setErrors(result.errors);
  }
}

/** What `session.set` can carry from the composer. A21 adds the speed tier. */
export interface SessionOptions {
  model?: string;
  permission_mode?: string;
  effort?: string;
  speed?: string | null;
}

/**
 * The label an agent's list gives an id, or the id itself. The agent's own ids
 * need not appear in its lists — `auto` is a real Claude permission mode the
 * device does not advertise — so an unknown one is shown as it arrived (A17).
 */
function labelOf(options: Choice[], value: string | null | undefined): string | null {
  if (!value) return null;
  return options.find((option) => option.id === value)?.label ?? value;
}

function ComposerBottomRow({
  agent,
  session,
  showOptions,
  language,
  sttEnabled,
  sttLanguages,
  onSetOption,
  onSetLanguage,
}: {
  agent: AgentInfo | null;
  session: Session;
  /**
   * A10/A11/A17: false when the model, permission mode and effort belong to a
   * terminal — a `terminal` session, or a shared one whose agent does not
   * report `shared_settings`. The card is then replaced by what the device read
   * from the transcript, drawn as chips that open nothing.
   */
  showOptions: boolean;
  language: string;
  sttEnabled: boolean;
  sttLanguages: string[];
  onSetOption: (patch: SessionOptions) => void;
  onSetLanguage: (code: string) => void;
}) {
  const models = agent?.models ?? [];
  const modes = agent?.permission_modes ?? [];
  const efforts = agent?.efforts ?? [];
  const speeds = agent?.speeds ?? [];

  const modelText = labelOf(models, session.model);
  const effortText = labelOf(efforts, session.effort);
  const speedText = labelOf(speeds, session.speed);
  // "Opus 4.6 High": what runs, and how hard, in one line of the composer row.
  const cardText = [modelText, effortText].filter((part) => part !== null).join(' ');
  const hasCard = models.length > 0 || efforts.length > 0 || speeds.length > 0;

  return (
    <div className="composer-bottom">
      {showOptions ? (
        hasCard ? (
          <Popover
            side="top"
            align="start"
            chevron={false}
            ariaLabel={strings.composer.modelCard}
            triggerClassName="model-card-chip"
            label={
              <>
                {speedText ? <Zap size={12} aria-hidden className="speed-glyph" /> : null}
                <span>{cardText || agentLabel(session.agent)}</span>
              </>
            }
          >
            {() => (
              <ModelCard
                session={session}
                models={models}
                efforts={efforts}
                speeds={speeds}
                onSetOption={onSetOption}
              />
            )}
          </Popover>
        ) : null
      ) : (
        <TerminalSetting
          name={strings.composer.modelCard}
          text={cardText}
          speed={speedText}
          glyph={speedText !== null}
        />
      )}
      {showOptions ? (
        modes.length > 0 ? (
          <Menu
            side="top"
            ariaLabel={strings.composer.permissionMode}
            value={session.permission_mode}
            options={modes.map((m) => ({ id: m.id, label: m.label }))}
            onSelect={(permission_mode) => onSetOption({ permission_mode })}
            label={labelOf(modes, session.permission_mode) ?? strings.composer.permissionMode}
          />
        ) : null
      ) : (
        <TerminalSetting
          name={strings.composer.permissionMode}
          text={labelOf(modes, session.permission_mode) ?? ''}
          speed={null}
          glyph={false}
        />
      )}
      {sttEnabled ? (
        <Menu
          side="top"
          ariaLabel={strings.composer.language}
          value={language}
          options={sttLanguages.map((code) => ({ id: code, label: languageLabel(code) }))}
          onSelect={onSetLanguage}
          label={languageLabel(language)}
        />
      ) : null}
    </div>
  );
}

/**
 * A21 — the card the model chip opens: the speed tier and the model on one row,
 * the effort slider under it. It stays open until it is dismissed, so several
 * changes can be made in one visit, and the model list takes the card over
 * rather than stacking a second popover on top of it.
 */
function ModelCard({
  session,
  models,
  efforts,
  speeds,
  onSetOption,
}: {
  session: Session;
  models: Choice[];
  efforts: Choice[];
  speeds: Choice[];
  onSetOption: (patch: SessionOptions) => void;
}) {
  const [picking, setPicking] = useState(false);
  const modelText = labelOf(models, session.model) ?? agentLabel(session.agent);

  if (picking) {
    return (
      <ul className="menu" role="listbox" aria-label={strings.composer.model}>
        {models.map((model) => (
          <li key={model.id}>
            <button
              type="button"
              role="option"
              aria-selected={session.model === model.id}
              className={cx('menu-item', session.model === model.id && 'selected')}
              onClick={() => {
                setPicking(false);
                if (session.model !== model.id) onSetOption({ model: model.id });
              }}
            >
              <span className="menu-label">{model.label}</span>
            </button>
          </li>
        ))}
      </ul>
    );
  }

  return (
    <div className="model-card">
      <div className="model-card-top">
        {speeds.length > 0 ? (
          <SpeedToggle session={session} speeds={speeds} onSetOption={onSetOption} />
        ) : null}
        {models.length > 0 ? (
          <button
            type="button"
            className="model-card-name"
            aria-label={strings.composer.option(strings.composer.model, modelText)}
            onClick={() => setPicking(true)}
          >
            <span>{modelText}</span>
            <EffortWord session={session} efforts={efforts} />
            <ChevronRight size={14} aria-hidden className="model-card-chevron" />
          </button>
        ) : (
          <span className="model-card-name static">
            <span>{modelText}</span>
            <EffortWord session={session} efforts={efforts} />
          </span>
        )}
      </div>
      {efforts.length > 0 ? (
        <EffortSlider session={session} efforts={efforts} onSetOption={onSetOption} />
      ) : null}
    </div>
  );
}

/**
 * The effort word beside the model name. It follows the slider's thumb while it
 * moves, which is why the slider publishes its stop on the element rather than
 * through state the card would have to thread back down.
 */
function EffortWord({ session, efforts }: { session: Session; efforts: Choice[] }) {
  const text = labelOf(efforts, session.effort);
  if (!text) return null;
  return (
    <span className="model-card-effort" data-effort-word>
      {text}
    </span>
  );
}

/**
 * One stop per effort level, in the order the agent lists them. Moving it
 * rewrites the word above at once; the value is only sent when the thumb is
 * released, which is the DOM's own `change` rather than React's (which fires on
 * every step).
 */
function EffortSlider({
  session,
  efforts,
  onSetOption,
}: {
  session: Session;
  efforts: Choice[];
  onSetOption: (patch: SessionOptions) => void;
}) {
  const stop = Math.max(
    0,
    efforts.findIndex((effort) => effort.id === session.effort),
  );
  const [index, setIndex] = useState(stop);
  const [drawnStop, setDrawnStop] = useState(stop);
  const input = useRef<HTMLInputElement>(null);

  // A change from anywhere else — a `/model` in the terminal, another tab —
  // wins over the position the thumb was left in.
  if (drawnStop !== stop) {
    setDrawnStop(stop);
    setIndex(stop);
  }

  useEffect(() => {
    const el = input.current;
    if (!el) return;
    const commit = () => {
      const picked = efforts[Number(el.value)];
      if (picked && picked.id !== session.effort) onSetOption({ effort: picked.id });
    };
    el.addEventListener('change', commit);
    return () => el.removeEventListener('change', commit);
  }, [efforts, session.effort, onSetOption]);

  const word = efforts[index]?.label ?? '';
  return (
    <input
      ref={input}
      type="range"
      className="effort-slider"
      min={0}
      max={efforts.length - 1}
      step={1}
      value={index}
      aria-label={strings.composer.effort}
      aria-valuetext={word}
      style={{ '--fill': `${(index / Math.max(1, efforts.length - 1)) * 100}%` } as CSSProperties}
      onChange={(e) => setIndex(Number(e.target.value))}
    />
  );
}

/** A21: standard → each tier the agent lists → standard, one tap at a time. */
function SpeedToggle({
  session,
  speeds,
  onSetOption,
}: {
  session: Session;
  speeds: Choice[];
  onSetOption: (patch: SessionOptions) => void;
}) {
  const tier = speeds.find((speed) => speed.id === session.speed) ?? null;
  return (
    <button
      type="button"
      className={cx('speed-toggle', tier && 'on')}
      aria-pressed={tier !== null}
      aria-label={strings.composer.speed(tier?.label ?? strings.composer.speedStandard)}
      onClick={() => {
        const next = speeds[speeds.findIndex((speed) => speed.id === session.speed) + 1] ?? null;
        onSetOption({ speed: next?.id ?? null });
      }}
    >
      <Zap size={15} aria-hidden />
    </button>
  );
}

/**
 * A17: one value a terminal chose, where its picker would be. It is the shape
 * of the trigger beside it, opens nothing, and carries the whole sentence for
 * assistive technology, because on its own "auto" says nothing about who set
 * it. A21 folds the model, the effort and the tier into the first of them, the
 * same one chip the card would open from. A value the device has not seen draws
 * no chip at all.
 */
function TerminalSetting({
  name,
  text,
  speed,
  glyph,
}: {
  name: string;
  text: string;
  speed: string | null;
  glyph: boolean;
}) {
  if (text.length === 0) return null;
  const shown = speed === null ? text : `${text} · ${speed}`;
  const sentence = strings.composer.setInTerminal(name, shown);
  return (
    <span className="composer-chip readonly" role="note" title={sentence} aria-label={sentence}>
      {glyph ? <Zap size={12} aria-hidden className="speed-glyph" /> : null}
      {text}
    </span>
  );
}
