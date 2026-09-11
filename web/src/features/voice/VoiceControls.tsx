/**
 * What the composer's control row holds while dictation runs: how loud it is,
 * how long it has been listening, and the only two ways out.
 *
 * There is no "stop and send". Done leaves the transcript in the message field
 * and Send stays the separate, explicit click it is for anything typed.
 */
import { clock } from '../../lib/format';
import { strings } from '../../strings';
import type { VoiceController } from './useVoice';

/** Fixed per-bar weights: a level of 1 lights the middle bars tallest. */
const BAR_WEIGHTS = [0.35, 0.55, 0.8, 1, 0.7, 0.95, 0.6, 0.85, 0.5, 0.7, 0.4];

interface Props {
  voice: VoiceController;
  onCancel: () => void;
  onDone: () => void;
}

export function VoiceControls({ voice, onCancel, onDone }: Props) {
  const elapsed = clock(voice.elapsedMs);
  return (
    <div className="voice-controls">
      <span className="waveform" aria-hidden>
        {BAR_WEIGHTS.map((weight, index) => (
          <span key={index} style={{ height: `${Math.round(4 + weight * voice.level * 20)}px` }} />
        ))}
      </span>
      <span className="mono voice-timer" aria-label={strings.voice.listeningFor(elapsed)}>
        {elapsed}
      </span>
      <button type="button" className="btn small" onClick={onCancel}>
        {strings.voice.cancel}
      </button>
      <button
        type="button"
        className="btn small primary"
        disabled={voice.state !== 'listening'}
        onClick={onDone}
      >
        {strings.voice.done}
      </button>
    </div>
  );
}
