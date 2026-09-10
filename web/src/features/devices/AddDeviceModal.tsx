import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { Check } from 'lucide-react';
import { Button } from '../../components/Button';
import { Modal } from '../../components/Modal';
import { Segmented } from '../../components/Segmented';
import { api, type PairingResponse } from '../../lib/api';
import { clock } from '../../lib/format';
import { strings } from '../../strings';
import { useConnection } from '../../stores/connection';
import { useNow } from '../../lib/useNow';
import type { PairingStep } from '../../protocol/frames';

type Platform = 'macos' | 'linux';

const STEP_ORDER: Record<PairingStep, number> = { waiting: 0, enrolled: 1, online: 2, agents: 3 };

/** Mounted only while open so each visit requests exactly one pairing code. */
export function AddDeviceModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  if (!open) return null;
  return <AddDevice onClose={onClose} />;
}

function AddDevice({ onClose }: { onClose: () => void }) {
  const [platform, setPlatform] = useState<Platform>(detectPlatform);
  const [pairing, setPairing] = useState<PairingResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [manual, setManual] = useState(false);
  const [copied, setCopied] = useState(false);
  const [attempt, setAttempt] = useState(0);

  const progress = useConnection((s) => s.pairing);
  const clearPairing = useConnection((s) => s.clearPairing);
  const clockSkewMs = useConnection((s) => s.clockSkewMs);
  const now = useNow(500);
  // The dialog requests the code on mount, so this is when listening started.
  const [openedAt] = useState(() => Date.now());

  useEffect(() => {
    let cancelled = false;
    void api
      .createPairing()
      .then((response) => {
        if (!cancelled) setPairing(response);
      })
      .catch(() => {
        if (!cancelled) setError(strings.pairing.createFailed);
      });
    return () => {
      cancelled = true;
    };
  }, [attempt]);

  const live = progress && pairing && progress.code === pairing.code ? progress : null;
  const step = live?.step ?? null;
  const connected = isConnected(step);

  const close = useCallback(() => {
    if (pairing && !connected) void api.cancelPairing(pairing.code).catch(() => undefined);
    clearPairing();
    onClose();
  }, [pairing, connected, clearPairing, onClose]);

  const command = pairing ? pairing.install[platform] : '';
  // `expires_at` is a gateway timestamp, so compare it against the gateway clock.
  const serverNow = now + clockSkewMs;
  const remaining = pairing ? Math.max(0, pairing.expires_at - serverNow) : 0;
  const expired = pairing !== null && remaining === 0 && !connected;
  const elapsed = Math.max(0, now - openedAt);

  const agentChips = useMemo(
    () => live?.device?.agents.filter((a) => a.available).map((a) => a.agent) ?? [],
    [live],
  );

  return (
    <Modal
      open
      onClose={close}
      title={strings.pairing.title}
      width={580}
      footer={
        <>
          <Button onClick={close}>{strings.common.cancel}</Button>
          <Button variant="primary" disabled={!connected} onClick={close}>
            {strings.common.continue}
          </Button>
        </>
      }
    >
      <p className="hint pairing-intro">{strings.pairing.intro}</p>

      <Segmented<Platform>
        ariaLabel="Platform"
        value={platform}
        onChange={setPlatform}
        options={[
          { value: 'macos', label: strings.pairing.macos },
          { value: 'linux', label: strings.pairing.linux },
        ]}
      />

      {error ? <p className="login-error">{error}</p> : null}

      <div className="pair-command">
        <div className="pair-command-top">
          <code>{command || ' '}</code>
          <Button
            small
            disabled={!command}
            onClick={async () => {
              await copyText(command);
              setCopied(true);
              window.setTimeout(() => setCopied(false), 1400);
            }}
          >
            {copied ? strings.common.copied : strings.common.copy}
          </Button>
        </div>
        <div className="pair-command-foot">
          <span className="mono pair-code">{pairing?.code ?? '—'}</span>
          <span className="pair-sep" aria-hidden />
          <span className="hint">
            {strings.pairing.singleUse} ·{' '}
            {expired ? strings.pairing.expired : strings.pairing.expiresIn(clock(remaining))}
          </span>
          {expired ? (
            <button type="button" className="link-btn" onClick={() => setAttempt((n) => n + 1)}>
              {strings.pairing.newCode}
            </button>
          ) : null}
        </div>
      </div>

      <div className="pair-steps">
        <div className="pair-steps-head">
          <span className="pair-steps-title">
            <span className={`dot ${connected ? 'running' : 'pulse'}`} aria-hidden />
            {connected && live?.device
              ? strings.pairing.connected(live.device.name)
              : strings.pairing.waiting}
          </span>
          <span className="mono hint">{strings.pairing.listening(clock(elapsed))}</span>
        </div>
        <div className="pair-progress" aria-hidden>
          <span style={{ width: `${progressWidth(step)}%` }} />
        </div>
        <ol className="pair-step-list">
          <Step done label={strings.pairing.stepGateway} />
          <Step
            done={rank(step) >= STEP_ORDER.enrolled}
            active={rank(step) < STEP_ORDER.enrolled}
            label={strings.pairing.stepHandshake}
          />
          <Step
            done={rank(step) >= STEP_ORDER.agents}
            active={rank(step) === STEP_ORDER.online}
            label={strings.pairing.stepAgents}
            trailing={
              agentChips.length > 0 ? (
                <span className="mono pair-agents">{agentChips.join(' · ')}</span>
              ) : null
            }
          />
        </ol>
      </div>

      <p className="pair-manual">
        {strings.pairing.noCurl}{' '}
        <button type="button" className="link-btn" onClick={() => setManual((v) => !v)}>
          {strings.pairing.manualInstall}
        </button>
      </p>

      {manual ? (
        <ol className="pair-manual-steps">
          {strings.pairing.manualSteps.map((line) => (
            <li key={line}>{line}</li>
          ))}
          <li>
            <code>
              {strings.pairing.manualPairCommand(
                window.location.origin,
                pairing?.code ?? 'RC-XXXX-XXXX',
              )}
            </code>
          </li>
        </ol>
      ) : null}
    </Modal>
  );
}

function Step({
  done,
  active,
  label,
  trailing,
}: {
  done?: boolean;
  active?: boolean;
  label: string;
  trailing?: ReactNode;
}) {
  return (
    <li className={done ? 'done' : active ? 'active' : ''}>
      <span className="pair-step-mark" aria-hidden>
        {done ? <Check size={11} strokeWidth={3} /> : null}
      </span>
      <span className="pair-step-label">{label}</span>
      {trailing}
    </li>
  );
}

const rank = (step: PairingStep | null): number => (step ? STEP_ORDER[step] : -1);

const isConnected = (step: PairingStep | null | undefined): boolean =>
  step === 'online' || step === 'agents';

function progressWidth(step: PairingStep | null): number {
  const r = rank(step);
  if (r < 0) return 12;
  return Math.min(100, 25 * (r + 1));
}

function detectPlatform(): Platform {
  return /Mac|iPhone|iPad/.test(navigator.userAgent) ? 'macos' : 'linux';
}

async function copyText(text: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    const area = document.createElement('textarea');
    area.value = text;
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.append(area);
    area.select();
    document.execCommand('copy');
    area.remove();
  }
}
