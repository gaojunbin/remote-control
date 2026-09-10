/**
 * Live speech-to-text: microphone frames out on `WS /ws/stt`, partial and final
 * transcripts back. The transcript stays editable until the user sends it.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { socketUrl } from '../../lib/ws';
import { strings } from '../../strings';
import { MicRecorder, type RecorderError } from './recorder';

export type VoiceState = 'idle' | 'starting' | 'recording' | 'finishing' | 'error';

const MAX_SECONDS = 120;

interface Options {
  enabled: boolean;
  language: string;
  onFinal: (text: string) => void;
}

export interface VoiceController {
  state: VoiceState;
  text: string;
  level: number;
  elapsedMs: number;
  error: string | null;
  setText: (text: string) => void;
  start: () => void;
  stopAndSend: () => void;
  cancel: () => void;
}

export function useVoice({ enabled, language, onFinal }: Options): VoiceController {
  const [state, setState] = useState<VoiceState>('idle');
  const [text, setText] = useState('');
  const [level, setLevel] = useState(0);
  const [elapsedMs, setElapsedMs] = useState(0);
  const [error, setError] = useState<string | null>(null);

  const socket = useRef<WebSocket | null>(null);
  const recorder = useRef<MicRecorder | null>(null);
  const startedAt = useRef(0);
  const editedRef = useRef(false);
  const textRef = useRef('');
  const onFinalRef = useRef(onFinal);

  useEffect(() => {
    onFinalRef.current = onFinal;
    textRef.current = text;
  }, [onFinal, text]);

  const teardown = useCallback(async () => {
    const rec = recorder.current;
    recorder.current = null;
    await rec?.stop();
    const ws = socket.current;
    socket.current = null;
    if (ws && ws.readyState <= WebSocket.OPEN) ws.close();
  }, []);

  const cancel = useCallback(() => {
    const ws = socket.current;
    if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'stt.cancel' }));
    void teardown();
    setState('idle');
    setText('');
    setLevel(0);
    setElapsedMs(0);
    setError(null);
    editedRef.current = false;
  }, [teardown]);

  const finish = useCallback(() => {
    if (state !== 'recording') return;
    setState('finishing');
    const ws = socket.current;
    void recorder.current?.stop().then(() => {
      if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'stt.stop' }));
      else {
        onFinalRef.current(textRef.current);
        cancel();
      }
    });
    recorder.current = null;
  }, [state, cancel]);

  const start = useCallback(() => {
    if (!enabled || (state !== 'idle' && state !== 'error')) return;
    setError(null);
    setText('');
    setElapsedMs(0);
    editedRef.current = false;
    setState('starting');
    startedAt.current = Date.now();

    const ws = new WebSocket(`${socketUrl('/ws/stt')}?language=${encodeURIComponent(language)}`);
    ws.binaryType = 'arraybuffer';
    socket.current = ws;

    ws.onopen = async () => {
      const rec = new MicRecorder({
        onFrame: (frame) => {
          if (ws.readyState === WebSocket.OPEN) ws.send(frame);
        },
        onLevel: setLevel,
        onError: (err) => {
          setError(recorderErrorText(err));
          setState('error');
          void teardown();
        },
      });
      recorder.current = rec;
      const ok = await rec.start();
      if (ok) setState('recording');
    };

    ws.onmessage = (event) => {
      if (typeof event.data !== 'string') return;
      let frame: { type?: string; text?: string; message?: string };
      try {
        frame = JSON.parse(event.data) as typeof frame;
      } catch {
        return;
      }
      if (frame.type === 'stt.partial' && !editedRef.current) setText(frame.text ?? '');
      if (frame.type === 'stt.final') {
        const finalText = editedRef.current ? textRef.current : (frame.text ?? textRef.current);
        onFinalRef.current(finalText);
        void teardown();
        setState('idle');
        setText('');
        setLevel(0);
        setElapsedMs(0);
      }
      if (frame.type === 'stt.error') {
        setError(frame.message || strings.voice.failed);
        setState('error');
        void teardown();
      }
    };

    ws.onerror = () => {
      setError(strings.voice.failed);
      setState('error');
      void teardown();
    };

    ws.onclose = () => {
      if (socket.current === ws) socket.current = null;
    };
  }, [enabled, state, language, teardown]);

  useEffect(() => {
    if (state !== 'recording') return;
    const handle = window.setInterval(() => {
      const ms = Date.now() - startedAt.current;
      setElapsedMs(ms);
      if (ms >= MAX_SECONDS * 1000) finish();
    }, 200);
    return () => window.clearInterval(handle);
  }, [state, finish]);

  useEffect(() => () => void teardown(), [teardown]);

  return {
    state,
    text,
    level,
    elapsedMs,
    error,
    setText: (next) => {
      editedRef.current = true;
      setText(next);
    },
    start,
    stopAndSend: finish,
    cancel,
  };
}

function recorderErrorText(error: RecorderError): string {
  if (error === 'denied') return strings.voice.denied;
  if (error === 'unsupported') return strings.voice.unsupported;
  return strings.voice.failed;
}
