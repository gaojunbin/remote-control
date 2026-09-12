import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { errorText } from '../../lib/errors';
import { rpc } from '../../lib/gateway';
import { strings } from '../../strings';
import { useChat } from '../../stores/chat';
import { useConnection } from '../../stores/connection';
import { useDevices } from '../../stores/devices';
import { useOutbox } from '../../stores/outbox';
import { sessionKey, useSessions } from '../../stores/sessions';
import { emptyTimeline, selectPendingQuestion } from '../../stores/timeline';
import type { SendMode } from '../../protocol/frames';
import type { QuestionAnswers } from '../../protocol/types';
import { NewSessionDrawer } from '../sessions/NewSessionDrawer';
import { ChatHeader } from './ChatHeader';
import { Composer } from './Composer';
import { Sidebar } from './Sidebar';
import { StatusLine } from './StatusLine';
import { Timeline } from './Timeline';
import type { AttachmentDraft } from './attachments';
import './chat.css';

/** Stable identity, so an unopened session does not rebuild the view each render. */
const NO_TIMELINE = emptyTimeline();

export function ChatPage() {
  const { deviceId = '', sessionId = '' } = useParams();
  const key = sessionKey(deviceId, sessionId);
  const navigate = useNavigate();

  const openChat = useChat((s) => s.open);
  const closeChat = useChat((s) => s.close);
  const chat = useChat((s) => s.sessions[key]);
  const sendMessage = useChat((s) => s.send);
  const retrySend = useChat((s) => s.retrySend);
  const stopTurn = useChat((s) => s.stop);
  const approveRequest = useChat((s) => s.approve);
  const answerQuestion = useChat((s) => s.answer);
  const expandBlock = useChat((s) => s.expandBlock);
  const loadOlder = useChat((s) => s.loadOlder);
  const removeQueued = useChat((s) => s.removeQueued);
  const session = useSessions((s) => s.sessions[key]);
  const takeoverSession = useSessions((s) => s.takeover);
  const devices = useDevices((s) => s.devices);
  const socketStatus = useConnection((s) => s.status);
  const stt = useConnection((s) => s.stt);
  const pending = useOutbox((s) => s.pending);

  const [stopping, setStopping] = useState(false);
  const [creating, setCreating] = useState(false);
  // Every request the timeline can issue reports its failure here rather than
  // rejecting into an unhandled promise.
  const [actionError, setActionError] = useState<string | null>(null);

  const device = useMemo(
    () => devices.find((d) => d.device_id === deviceId) ?? null,
    [devices, deviceId],
  );
  const agent = useMemo(
    () => device?.agents.find((a) => a.agent === session?.agent) ?? null,
    [device, session?.agent],
  );

  useEffect(() => {
    if (!deviceId || !sessionId) return;
    if (socketStatus !== 'open') return;
    openChat(deviceId, sessionId);
    return () => closeChat(deviceId, sessionId);
  }, [deviceId, sessionId, socketStatus, openChat, closeChat]);

  const unconfirmed = useMemo(
    () => Object.values(pending).filter((p) => p.sessionKey === key && p.error !== null),
    [pending, key],
  );

  // A20: the composer answers the question the timeline is waiting on.
  const timeline = chat?.timeline ?? NO_TIMELINE;
  const question = useMemo(() => selectPendingQuestion(timeline), [timeline]);

  const onSend = useCallback(
    async (text: string, attachments: AttachmentDraft[], mode: SendMode) => {
      await sendMessage(key, {
        text,
        mode,
        attachments: attachments.map(({ name, mime, data_base64 }) => ({
          name,
          mime,
          data_base64,
        })),
      });
    },
    [sendMessage, key],
  );

  const onSetOption = useCallback(
    (path: { model?: string; permission_mode?: string; effort?: string }) => {
      void rpc('session.set', { session_id: sessionId, ...path })
        .then((result) => useSessions.getState().upsert(result.session))
        .catch((err: unknown) => setActionError(errorText(err, strings.errors.setFailed)));
    },
    [sessionId],
  );

  const onTakeover = useCallback(() => {
    if (!session) return;
    void takeoverSession(session).catch((err: unknown) =>
      setActionError(errorText(err, strings.errors.takeoverFailed)),
    );
  }, [session, takeoverSession]);

  const onStop = useCallback(() => {
    setStopping(true);
    setActionError(null);
    void stopTurn(key)
      .catch((err: unknown) => setActionError(errorText(err, strings.errors.stopFailed)))
      .finally(() => setStopping(false));
  }, [stopTurn, key]);

  const onApprove = useCallback(
    async (requestId: string, optionId: string) => {
      setActionError(null);
      try {
        await approveRequest(key, requestId, optionId);
      } catch (err) {
        setActionError(errorText(err, strings.errors.approveFailed));
      }
    },
    [approveRequest, key],
  );

  /**
   * The banner reports the failure; the rejection travels on so the card and
   * the composer keep what was filled in rather than clearing it (A20).
   */
  const onAnswer = useCallback(
    async (requestId: string, answers: QuestionAnswers) => {
      setActionError(null);
      try {
        await answerQuestion(key, requestId, answers);
      } catch (err) {
        setActionError(errorText(err, strings.errors.answerFailed));
        throw err;
      }
    },
    [answerQuestion, key],
  );

  const onOpenFull = useCallback(
    async (blockId: string) => {
      setActionError(null);
      try {
        await expandBlock(key, blockId);
      } catch (err) {
        setActionError(errorText(err, strings.errors.expandFailed));
      }
    },
    [expandBlock, key],
  );

  const onRemoveQueued = useCallback(
    (queuedId: string) => {
      void removeQueued(key, queuedId).catch((err: unknown) =>
        setActionError(errorText(err, strings.errors.queueRemoveFailed)),
      );
    },
    [removeQueued, key],
  );

  const onLoadOlder = useCallback(() => void loadOlder(key), [loadOlder, key]);

  if (!session) {
    return (
      <div className="chat-layout">
        <Sidebar activeKey={key} onNewSession={() => setCreating(true)} />
        <div className="chat-main">
          <div className="empty card chat-missing">
            <strong>{strings.errors.sessionMissing}</strong>
            <button type="button" className="btn small" onClick={() => navigate('/sessions')}>
              {strings.nav.backToSessions}
            </button>
          </div>
        </div>
        <NewSessionDrawer open={creating} onClose={() => setCreating(false)} />
      </div>
    );
  }

  return (
    <div className="chat-layout">
      <Sidebar activeKey={key} onNewSession={() => setCreating(true)} />

      <div className="chat-main">
        <ChatHeader
          session={session}
          agent={agent}
          deviceName={device?.name ?? deviceId}
          todos={chat?.todos ?? []}
          stopping={stopping}
          onStop={onStop}
        />

        <Timeline
          timeline={timeline}
          historyLoading={chat?.historyLoading ?? false}
          historyHasMore={chat?.historyHasMore ?? false}
          onOpenFull={onOpenFull}
          onApprove={onApprove}
          onAnswer={onAnswer}
          onLoadOlder={onLoadOlder}
          footer={
            <StatusLine
              session={session}
              agent={agent}
              deviceOnline={device?.online ?? false}
              onTakeover={onTakeover}
            />
          }
        />

        {actionError ? (
          <div className="action-error" role="alert">
            <span>{actionError}</span>
            <button type="button" className="btn small ghost" onClick={() => setActionError(null)}>
              {strings.common.dismiss}
            </button>
          </div>
        ) : null}

        {unconfirmed.length > 0 ? (
          <div className="unconfirmed" role="alert">
            <span>
              <strong>{strings.composer.deliveryUnconfirmed}</strong>{' '}
              {strings.composer.deliveryUnconfirmedBody}
            </span>
            <button
              type="button"
              className="btn small"
              onClick={() => {
                for (const entry of unconfirmed) {
                  retrySend(entry.id).catch((err: unknown) =>
                    setActionError(errorText(err, strings.composer.sendFailed)),
                  );
                }
              }}
            >
              {strings.common.retry}
            </button>
            <button
              type="button"
              className="btn small ghost"
              onClick={() => {
                for (const entry of unconfirmed) useOutbox.getState().clear(entry.id);
              }}
            >
              {strings.common.dismiss}
            </button>
          </div>
        ) : null}

        <Composer
          session={session}
          agent={agent}
          deviceOnline={device?.online ?? false}
          queue={chat?.queue ?? []}
          question={question}
          sttEnabled={stt.enabled}
          sttLanguages={stt.languages}
          onSend={onSend}
          onAnswer={onAnswer}
          onSetOption={onSetOption}
          onRemoveQueued={onRemoveQueued}
          onTakeover={onTakeover}
        />
      </div>

      <NewSessionDrawer
        open={creating}
        onClose={() => setCreating(false)}
        {...(deviceId ? { presetDeviceId: deviceId } : {})}
      />
    </div>
  );
}
