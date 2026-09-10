import { useEffect, useRef } from 'react';
import { Square } from 'lucide-react';
import { clock } from '../../lib/format';
import { strings } from '../../strings';
import type { VoiceController } from './useVoice';

/** Fixed per-bar weights: a level of 1 lights the middle bars tallest. */
const BAR_WEIGHTS = [0.35, 0.55, 0.8, 1, 0.7, 0.95, 0.6, 0.85, 0.5, 0.7, 0.4];

/** Replaces the composer while a transcription is running. */
export function VoicePanel({ voice }: { voice: VoiceController }) {
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  return (
    <div className="voice-panel">
      <div className="voice-row">
        <span className="waveform" aria-hidden>
          {BAR_WEIGHTS.map((weight, index) => (
            <span key={index} style={{ height: `${Math.round(4 + weight * voice.level * 20)}px` }} />
          ))}
        </span>
        <span className="mono voice-timer">{clock(voice.elapsedMs)}</span>
        <input
          ref={inputRef}
          className="voice-text"
          value={voice.text}
          placeholder={voice.state === 'starting' ? strings.voice.connecting : ''}
          aria-label={strings.voice.editHint}
          onChange={(e) => voice.setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              voice.stopAndSend();
            }
            if (e.key === 'Escape') voice.cancel();
          }}
        />
      </div>
      <div className="voice-actions">
        <span className="hint">{voice.error ?? strings.voice.transcribing}</span>
        <button type="button" className="btn small" onClick={voice.cancel}>
          {strings.voice.cancel}
        </button>
        <button
          type="button"
          className="btn small primary"
          onClick={voice.stopAndSend}
          disabled={voice.state === 'finishing'}
        >
          <Square size={11} fill="currentColor" aria-hidden />
          {strings.voice.stopAndSend}
        </button>
      </div>
    </div>
  );
}
