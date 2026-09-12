import { useCallback, useEffect, useRef, useState } from 'react';
import { ArrowUp, Mic, Paperclip, X } from 'lucide-react';
import { Menu, Popover } from '../../components/Popover';
import { bytes } from '../../lib/format';
import { cx } from '../../lib/cx';
import { agentLabel, languageLabel, strings } from '../../strings';
import { useSettings } from '../../stores/settings';
import type { SendMode } from '../../protocol/frames';
import type {
  AgentInfo,
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
  onSetOption: (patch: { model?: string; permission_mode?: string; effort?: string }) => void;
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
   * report `shared_settings`. The pickers are then replaced by what the device
   * read from the transcript, drawn as chips that open nothing.
   */
  showOptions: boolean;
  language: string;
  sttEnabled: boolean;
  sttLanguages: string[];
  onSetOption: (patch: { model?: string; permission_mode?: string; effort?: string }) => void;
  onSetLanguage: (code: string) => void;
}) {
  const models = showOptions ? (agent?.models ?? []) : [];
  const modes = showOptions ? (agent?.permission_modes ?? []) : [];
  const efforts = showOptions ? (agent?.efforts ?? []) : [];
  const labelOf = (list: { id: string; label: string }[], value: string | null, fallback: string) =>
    list.find((item) => item.id === value)?.label ?? fallback;

  return (
    <div className="composer-bottom">
      {showOptions ? null : (
        <>
          <TerminalSetting
            name={strings.composer.model}
            value={session.model}
            options={agent?.models ?? []}
          />
          <TerminalSetting
            name={strings.composer.permissionMode}
            value={session.permission_mode}
            options={agent?.permission_modes ?? []}
          />
          <TerminalSetting
            name={strings.composer.effort}
            value={session.effort}
            options={agent?.efforts ?? []}
          />
        </>
      )}
      {models.length > 0 ? (
        <Menu
          side="top"
          ariaLabel={strings.composer.model}
          value={session.model}
          options={models.map((m) => ({ id: m.id, label: m.label }))}
          onSelect={(model) => onSetOption({ model })}
          label={labelOf(models, session.model, agentLabel(session.agent))}
        />
      ) : null}
      {modes.length > 0 ? (
        <Menu
          side="top"
          ariaLabel={strings.composer.permissionMode}
          value={session.permission_mode}
          options={modes.map((m) => ({ id: m.id, label: m.label }))}
          onSelect={(permission_mode) => onSetOption({ permission_mode })}
          label={labelOf(modes, session.permission_mode, strings.composer.permissionMode)}
        />
      ) : null}
      {efforts.length > 0 ? (
        <Menu
          side="top"
          ariaLabel={strings.composer.effort}
          value={session.effort}
          options={efforts.map((e) => ({ id: e.id, label: e.label }))}
          onSelect={(effort) => onSetOption({ effort })}
          label={labelOf(efforts, session.effort, strings.composer.effort)}
        />
      ) : null}
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
 * A17: one value a terminal chose, where its picker would be. It is the shape
 * of the trigger beside it, opens nothing, and carries the whole sentence for
 * assistive technology, because on its own "auto" says nothing about who set
 * it. A value the device has not seen draws no chip at all.
 */
function TerminalSetting({
  name,
  value,
  options,
}: {
  name: string;
  value: string | null;
  options: { id: string; label: string }[];
}) {
  if (!value) return null;
  // The agent's own ids need not appear in its lists: `auto` is a real Claude
  // permission mode that the device does not advertise, so it is shown as it is.
  const shown = options.find((option) => option.id === value)?.label ?? value;
  const sentence = strings.composer.setInTerminal(name, shown);
  return (
    <span className="composer-chip readonly" role="note" title={sentence} aria-label={sentence}>
      {shown}
    </span>
  );
}
