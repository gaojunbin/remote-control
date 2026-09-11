/**
 * Amendment A10/A11 helpers: how a session shared with a live terminal behaves.
 *
 * `shared` means a CLI process owns the session and the device is attached to
 * it, so the composer, approvals and the queue work exactly as for `remote`.
 * What else the attachment carries is per agent: the device reports
 * `shared_interrupt`, `shared_settings` and `shared_attachments`, each
 * defaulting to false. The Claude channel carries none of them; the Codex
 * app-server daemon carries all three.
 */
import { strings } from '../../strings';
import type { AgentInfo, Session } from '../../protocol/types';

/** True when the device is attached to a terminal-owned session. */
export const isShared = (session: Session): boolean => session.control === 'shared';

/** True when only the terminal can drive the session. */
export const isTerminalOnly = (session: Session): boolean => session.control === 'terminal';

/**
 * A10 §4.4: Stop needs the `interrupt` capability *and* a device that reports
 * `shared_interrupt`. Claude channels cannot interrupt; the Codex daemon can.
 */
export function canInterruptShared(agent: AgentInfo | null): boolean {
  return agent?.shared_interrupt === true && agent.capabilities.includes('interrupt');
}

/**
 * A11 §4.2: the model, permission mode and effort pickers are shown on a
 * shared session only when the device can forward `session.set` to the CLI.
 */
export function canSetShared(agent: AgentInfo | null): boolean {
  return agent?.shared_settings === true;
}

/**
 * A11 §4.2: whether attachments reach the CLI through the attachment. When
 * they cannot, the attachment button is not rendered at all.
 */
export function canAttachShared(agent: AgentInfo | null): boolean {
  return agent?.shared_attachments === true;
}

/**
 * The one-line hint under "Controlled by the terminal", for a `terminal`
 * session whose agent could have been attached. `attach_ready === false` means
 * the device is not set up yet; `true` means this CLI was started without it.
 */
export function attachHint(agent: AgentInfo | null): string | null {
  if (!agent?.attach) return null;
  if (agent.attach_ready === true) return strings.chat.attachHintRestart;
  if (agent.attach_ready === false) {
    return agent.attach === 'daemon'
      ? strings.chat.attachHintDaemon
      : strings.chat.attachHintChannel;
  }
  return null;
}

/** The chip on a user bubble that says where the message got to. */
export function deliveryLabel(delivery: string | undefined): string | null {
  if (delivery === 'pending') return strings.chat.deliveryPending;
  if (delivery === 'absorbed') return strings.chat.deliveryAbsorbed;
  return null;
}
