import { useCallback, useEffect, useRef, useState } from 'react';
import { ArrowUp, Mic, Paperclip, X } from 'lucide-react';
import { Menu, Popover } from '../../components/Popover';
import { bytes } from '../../lib/format';
import { cx } from '../../lib/cx';
import { agentLabel, languageLabel, strings } from '../../strings';
import { useSettings } from '../../stores/settings';
import type { SendMode } from '../../protocol/frames';
import type { AgentInfo, QueuedMessage, Session } from '../../protocol/types';
import { VoicePanel } from '../voice/VoicePanel';
import { useVoice } from '../voice/useVoice';
import {
  attachHint,
  attachedLabel,
  canAttachShared,
  canInterruptShared,
  canSetShared,
} from './attach';
import { readAttachments, textTooLong, type AttachmentDraft } from './attachments';

interface Props {
  session: Session;
  agent: AgentInfo | null;
  deviceOnline: boolean;
  queue: QueuedMessage[];
  sttEnabled: boolean;
  sttLanguages: string[];
  onSend: (text: string, attachments: AttachmentDraft[], mode: SendMode) => Promise<void>;
  onSetOption: (patch: { model?: string; permission_mode?: string; effort?: string }) => void;
  onRemoveQueued: (queuedId: string) => void;
  onTakeover: () => void;
}

export function Composer({
  session,
  agent,
  deviceOnline,
  queue,
  sttEnabled,
  sttLanguages,
  onSend,
  onSetOption,
  onRemoveQueued,
  onTakeover,
}: Props) {
  const [text, setText] = useState('');
  const [attachments, setAttachments] = useState<AttachmentDraft[]>([]);
  const [errors, setErrors] = useState<string[]>([]);
  const [sending, setSending] = useState(false);
  const composing = useRef(false);
  const textarea = useRef<HTMLTextAreaElement>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const textRef = useRef('');

  useEffect(() => {
    textRef.current = text;
  }, [text]);

  const language = useSettings((s) => s.sttLanguage);
  const setLanguage = useSettings((s) => s.setSttLanguage);
  const pushToTalk = useSettings((s) => s.pushToTalk);

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
  // terminal unless the device reports that the attachment carries them.
  const optionsLocked = shared && !canSetShared(agent);
  const attachmentsBlocked = shared && !canAttachShared(agent);

  const submit = useCallback(
    async (mode: SendMode, source?: string) => {
      const value = (source ?? text).trim();
      if (sending || disabled || (value.length === 0 && attachments.length === 0)) return;
      if (textTooLong(value)) {
        setErrors([strings.composer.textTooLong]);
        return;
      }
      setSending(true);
      setErrors([]);
      try {
        await onSend(value, attachments, mode);
        setText('');
        setAttachments([]);
      } catch (err) {
        setErrors([err instanceof Error ? err.message : strings.composer.sendFailed]);
      } finally {
        setSending(false);
      }
    },
    [text, attachments, sending, disabled, onSend],
  );

  const voice = useVoice({
    enabled: sttEnabled && !disabled,
    language,
    onFinal: (final) => {
      if (final.trim().length === 0) return;
      // Merge here and hand the result to submit: the callback stored by
      // useVoice closes over an older `text`, so it must not read state.
      const merged = textRef.current ? `${textRef.current} ${final}` : final;
      setText(merged);
      void submit('auto', merged);
    },
  });

  // Push to talk: hold Option+Space anywhere on the page.
  useEffect(() => {
    if (!pushToTalk || !sttEnabled || disabled) return;
    const down = (e: KeyboardEvent) => {
      if (e.code !== 'Space' || !e.altKey || e.repeat) return;
      e.preventDefault();
      if (voice.state === 'idle') voice.start();
    };
    const up = (e: KeyboardEvent) => {
      if (e.code !== 'Space' && e.key !== 'Alt') return;
      if (voice.state === 'recording') voice.stopAndSend();
    };
    window.addEventListener('keydown', down);
    window.addEventListener('keyup', up);
    return () => {
      window.removeEventListener('keydown', down);
      window.removeEventListener('keyup', up);
    };
  }, [pushToTalk, sttEnabled, disabled, voice]);

  useEffect(() => {
    const el = textarea.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, 220)}px`;
  }, [text]);

  // PROTOCOL-FROZEN §5: `auto` is "send now if idle; if running, steer or queue".
  // Forcing `queue` here would make the `steer` capability unreachable.
  const primaryMode: SendMode = 'auto';
  const primaryLabel = running
    ? canSteer
      ? strings.composer.send
      : strings.composer.queue
    : strings.composer.send;

  // A10 §8: only a `terminal` session can be missing its attachment.
  const hint = terminalControlled ? attachHint(agent) : null;

  const placeholder = terminalControlled
    ? strings.composer.placeholderTerminal
    : !deviceOnline
      ? strings.composer.placeholderOffline
      : running
        ? canSteer
          ? strings.composer.placeholderSteer
          : strings.composer.placeholderQueued
        : strings.composer.placeholder;

  if (voice.state === 'recording' || voice.state === 'starting' || voice.state === 'finishing') {
    return (
      <div className="composer-wrap">
        <VoicePanel voice={voice} />
        <ComposerBottomRow
          agent={agent}
          session={session}
          locked={optionsLocked}
          language={language}
          sttEnabled={sttEnabled}
          sttLanguages={sttLanguages}
          onSetOption={onSetOption}
          onSetLanguage={setLanguage}
        />
      </div>
    );
  }

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
          <button type="button" className="link-btn" onClick={voice.cancel}>
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

      {shared ? (
        <div className="takeover-bar">
          <span className="takeover-text">{attachedLabel(agent)}</span>
        </div>
      ) : null}

      <div className={cx('composer', disabled && 'disabled')}>
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
          onChange={(e) => setText(e.target.value)}
          onPaste={(e) => {
            const files = attachmentsBlocked ? [] : [...e.clipboardData.files];
            if (files.length > 0) {
              e.preventDefault();
              void attach(files);
            }
          }}
          onKeyDown={(e) => {
            if (e.key !== 'Enter' || e.shiftKey || composing.current || e.nativeEvent.isComposing) return;
            e.preventDefault();
            void submit(primaryMode);
          }}
        />
        <div className="composer-buttons">
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
          <span
            className="tip"
            {...(attachmentsBlocked ? { title: strings.composer.attachSharedUnsupported } : {})}
          >
            <button
              type="button"
              className="icon-btn"
              aria-label={strings.composer.attach}
              disabled={disabled || attachmentsBlocked}
              onClick={() => fileInput.current?.click()}
            >
              <Paperclip size={16} />
            </button>
          </span>
          {sttEnabled ? (
            <button
              type="button"
              className="icon-btn"
              aria-label={strings.composer.micStart}
              disabled={disabled}
              onClick={voice.start}
            >
              <Mic size={16} />
            </button>
          ) : null}
          {running && canInterrupt ? (
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
                        void submit('interrupt');
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
            disabled={disabled || sending || (text.trim().length === 0 && attachments.length === 0)}
            onClick={() => void submit(primaryMode)}
          >
            {running ? primaryLabel : <ArrowUp size={15} aria-hidden />}
            {running ? null : <span className="sr-only">{strings.composer.send}</span>}
          </button>
        </div>
      </div>

      <ComposerBottomRow
        agent={agent}
        session={session}
        locked={optionsLocked}
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
  locked,
  language,
  sttEnabled,
  sttLanguages,
  onSetOption,
  onSetLanguage,
}: {
  agent: AgentInfo | null;
  session: Session;
  /**
   * A10/A11: true when `session.set` for the model, permission mode and
   * effort has to happen in the terminal, i.e. a shared session whose agent
   * does not report `shared_settings`.
   */
  locked: boolean;
  language: string;
  sttEnabled: boolean;
  sttLanguages: string[];
  onSetOption: (patch: { model?: string; permission_mode?: string; effort?: string }) => void;
  onSetLanguage: (code: string) => void;
}) {
  const models = agent?.models ?? [];
  const modes = agent?.permission_modes ?? [];
  const efforts = agent?.efforts ?? [];
  const labelOf = (list: { id: string; label: string }[], value: string | null, fallback: string) =>
    list.find((item) => item.id === value)?.label ?? fallback;
  const lock = locked ? { title: strings.composer.lockedToTerminal } : {};

  return (
    <div className="composer-bottom">
      {models.length > 0 ? (
        <span className="tip" {...lock}>
          <Menu
            side="top"
            ariaLabel={strings.composer.model}
            value={session.model}
            disabled={locked}
            options={models.map((m) => ({ id: m.id, label: m.label }))}
            onSelect={(model) => onSetOption({ model })}
            label={labelOf(models, session.model, agentLabel(session.agent))}
          />
        </span>
      ) : null}
      {modes.length > 0 ? (
        <span className="tip" {...lock}>
          <Menu
            side="top"
            ariaLabel={strings.composer.permissionMode}
            value={session.permission_mode}
            disabled={locked}
            options={modes.map((m) => ({ id: m.id, label: m.label }))}
            onSelect={(permission_mode) => onSetOption({ permission_mode })}
            label={labelOf(modes, session.permission_mode, strings.composer.permissionMode)}
          />
        </span>
      ) : null}
      {efforts.length > 0 ? (
        <span className="tip" {...lock}>
          <Menu
            side="top"
            ariaLabel={strings.composer.effort}
            value={session.effort}
            disabled={locked}
            options={efforts.map((e) => ({ id: e.id, label: e.label }))}
            onSelect={(effort) => onSetOption({ effort })}
            label={labelOf(efforts, session.effort, strings.composer.effort)}
          />
        </span>
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
      <span className="talk-hint mono">{strings.composer.talkHint}</span>
    </div>
  );
}
