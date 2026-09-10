/**
 * Amendment A10 — `control: "shared"`, a terminal session the device is
 * attached to. Covers the composer state, the Stop and takeover rules, the
 * delivery chip and the fixtures the contract describes.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { ChatHeader } from '../src/features/chat/ChatHeader';
import { Composer } from '../src/features/chat/Composer';
import { StatusLine } from '../src/features/chat/StatusLine';
import { UserMessageRow } from '../src/features/chat/blocks/UserMessageRow';
import { attachHint, canInterruptShared } from '../src/features/chat/attach';
import { applyEvent, emptyTimeline } from '../src/stores/timeline';
import { foldSession } from '../src/stores/chat';
import { sessionStateLabel } from '../src/strings';
import { useSettings } from '../src/stores/settings';
import { claudeAgent, claudeNoShim, codexAgent } from '../mock/fixtures';
import { fixtureOrEmpty, fixturesAvailable } from './fixtures';
import type {
  AgentInfo,
  ApprovalEvent,
  MetaEvent,
  Session,
  UserMessageEvent,
} from '../src/protocol/types';

const CONTROLS = ['remote', 'terminal', 'shared', 'none'];
const DELIVERIES = ['pending', 'delivered', 'absorbed'];

const sharedIdle = fixtureOrEmpty<Session>('objects/session.shared-idle.json');
const sharedRunning = fixtureOrEmpty<Session>('objects/session.shared-running.json');
const attachAgent = fixtureOrEmpty<AgentInfo>('objects/agent.claude-attach.json');
const pendingMessage = fixtureOrEmpty<UserMessageEvent>('events/user_message.pending.json');
const deliveredMessage = fixtureOrEmpty<UserMessageEvent>('events/user_message.delivered.json');
const absorbedMessage = fixtureOrEmpty<UserMessageEvent>('events/user_message.absorbed.json');
const sharedApproval = fixtureOrEmpty<ApprovalEvent>('events/approval.shared-pending.json');
const terminalApproval = fixtureOrEmpty<ApprovalEvent>('events/approval.shared-terminal.json');

const composerProps = (session: Session, agent: AgentInfo | null) => ({
  session,
  agent,
  deviceOnline: true,
  queue: [],
  sttEnabled: false,
  sttLanguages: ['auto'],
  onSend: vi.fn().mockResolvedValue(undefined),
  onSetOption: vi.fn(),
  onRemoveQueued: vi.fn(),
  onTakeover: vi.fn(),
});

const terminalSession = (overrides: Partial<Session> = {}): Session => ({
  ...sharedIdle,
  control: 'terminal',
  state: 'readonly',
  ...overrides,
});

beforeEach(() => {
  useSettings.setState({ sttLanguage: 'auto', pushToTalk: false, showArchived: false });
});

describe.runIf(fixturesAvailable())('A10 fixtures', () => {
  it('decodes the shared session objects', () => {
    for (const value of [sharedIdle, sharedRunning]) {
      expect(value.control).toBe('shared');
      expect(CONTROLS).toContain(value.control);
      // §4.5: `readonly` stays reserved for `control: "terminal"`.
      expect(value.state).not.toBe('readonly');
      expect(value.origin).toBe('terminal');
    }
    expect(sharedRunning.state).toBe('running');
    expect(sharedRunning.turn).not.toBeNull();
  });

  it('decodes the attach-capable agent', () => {
    const agent: AgentInfo = attachAgent;
    expect(agent.attach).toBe('channel');
    expect(agent.attach_ready).toBe(true);
    expect(agent.shared_interrupt).toBe(false);
    expect(agent.capabilities).toContain('takeover');
  });

  it('decodes the three delivery states', () => {
    expect(pendingMessage.delivery).toBe('pending');
    expect(deliveredMessage.delivery).toBe('delivered');
    expect(absorbedMessage.delivery).toBe('absorbed');
    for (const value of [pendingMessage, deliveredMessage, absorbedMessage]) {
      expect(DELIVERIES).toContain(value.delivery);
      // §5.2: `source` stays `remote` for anything an app sent.
      expect(value.source).toBe('remote');
    }
    // The delivered copy replaces the pending block: same id, later seq.
    expect(deliveredMessage.block_id).toBe(pendingMessage.block_id);
    expect(deliveredMessage.first_seq).toBe(pendingMessage.seq);
    expect(deliveredMessage.seq).toBeGreaterThan(pendingMessage.seq);
  });

  it('decodes a relayed approval with allow and deny only', () => {
    for (const value of [sharedApproval, terminalApproval]) {
      expect(value.options.map((o) => o.id)).toEqual(['allow', 'deny']);
      expect(value.diff).toBeUndefined();
      expect(Object.keys(value.input ?? {}).sort()).toEqual([
        'description',
        'input_preview',
        'tool_name',
      ]);
    }
    expect(sharedApproval.status).toBe('pending');
    expect(terminalApproval.decision?.by).toBe('terminal');
  });

  it('rejects the values the contract calls out as invalid', () => {
    expect(CONTROLS).not.toContain('attached');
    expect(DELIVERIES).not.toContain('queued');
  });
});

describe.runIf(fixturesAvailable())('A10 composer on a shared session', () => {
  it('enables the composer exactly like a remote session', () => {
    render(<Composer {...composerProps(sharedIdle, attachAgent)} />);
    const input = screen.getByLabelText('Message the agent…');
    expect(input).toBeEnabled();
    expect(input).toHaveAttribute('placeholder', 'Message the agent…');
  });

  it('never offers Take over', () => {
    render(<Composer {...composerProps(sharedIdle, attachAgent)} />);
    expect(screen.queryByRole('button', { name: 'Take over' })).not.toBeInTheDocument();
    expect(screen.getByText('Attached to the terminal session')).toBeInTheDocument();
  });

  it('locks the model, permission mode and effort pickers to the terminal', () => {
    render(<Composer {...composerProps(sharedIdle, attachAgent)} />);
    for (const name of ['Model', 'Permission mode', 'Effort']) {
      expect(screen.getByRole('button', { name })).toBeDisabled();
    }
    const tips = document.querySelectorAll('.tip[title="Change it in the terminal"]');
    expect(tips).toHaveLength(3);
  });

  it('disables attachments, which cannot reach a terminal session', () => {
    render(<Composer {...composerProps(sharedIdle, attachAgent)} />);
    expect(screen.getByRole('button', { name: 'Attach files' })).toBeDisabled();
    expect(
      document.querySelector('.tip[title="Attachments cannot be delivered to a terminal session"]'),
    ).not.toBeNull();
  });

  it('hides "Interrupt & send" while the attachment cannot interrupt', () => {
    render(<Composer {...composerProps(sharedRunning, attachAgent)} />);
    expect(screen.queryByRole('button', { name: 'Send options' })).not.toBeInTheDocument();
  });

  it('offers "Interrupt & send" when the device reports shared_interrupt', () => {
    const agent: AgentInfo = { ...attachAgent, shared_interrupt: true };
    render(<Composer {...composerProps(sharedRunning, agent)} />);
    expect(screen.getByRole('button', { name: 'Send options' })).toBeInTheDocument();
  });

  it('leaves the pickers alone on a remote session', () => {
    const session: Session = { ...sharedIdle, control: 'remote', origin: 'remote' };
    render(<Composer {...composerProps(session, claudeAgent)} />);
    expect(screen.getByRole('button', { name: 'Model' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Attach files' })).toBeEnabled();
  });
});

describe.runIf(fixturesAvailable())('A10 takeover bar on a terminal session', () => {
  it('offers Take over only when the agent has the capability', () => {
    const { unmount } = render(
      <Composer {...composerProps(terminalSession(), claudeAgent)} />,
    );
    expect(screen.getByRole('button', { name: 'Take over' })).toBeInTheDocument();
    unmount();

    render(<Composer {...composerProps(terminalSession({ agent: 'codex' }), codexAgent)} />);
    expect(screen.getByText('Controlled by the terminal')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Take over' })).not.toBeInTheDocument();
  });

  it('asks for the shim when the device is not ready to attach', () => {
    render(<Composer {...composerProps(terminalSession(), claudeNoShim)} />);
    expect(
      screen.getByText('Start claude through the remote-control shim to control it from here'),
    ).toBeInTheDocument();
  });

  it('asks for a restart when the device is ready but this CLI was not attached', () => {
    render(<Composer {...composerProps(terminalSession(), attachAgent)} />);
    expect(
      screen.getByText(
        'This terminal session was started without the attachment; restart it to control it from here',
      ),
    ).toBeInTheDocument();
  });

  it('picks the hint from the attachment kind and readiness', () => {
    expect(attachHint(null)).toBeNull();
    expect(attachHint(claudeAgent)).toContain('restart it');
    expect(attachHint(claudeNoShim)).toContain('remote-control shim');
    expect(attachHint({ ...codexAgent, attach: 'daemon', attach_ready: false })).toContain(
      'Codex app-server daemon',
    );
    // No `attach` and no readiness flag mean there is nothing to hint at.
    expect(attachHint(codexAgent)).toBeNull();
    expect(attachHint({ ...claudeAgent, attach_ready: undefined })).toBeNull();
  });

  it('shows no hint on a shared session, which is already attached', () => {
    render(<Composer {...composerProps(sharedIdle, attachAgent)} />);
    expect(screen.queryByText(/control it from here/)).not.toBeInTheDocument();
  });
});

describe.runIf(fixturesAvailable())('A10 Stop', () => {
  const header = (session: Session, agent: AgentInfo | null) =>
    render(
      <MemoryRouter>
        <ChatHeader
          session={session}
          agent={agent}
          deviceName="mac-studio-office"
          todos={[]}
          stopping={false}
          onStop={vi.fn()}
        />
      </MemoryRouter>,
    );

  it('is hidden on a shared session the attachment cannot interrupt', () => {
    header(sharedRunning, attachAgent);
    expect(screen.queryByRole('button', { name: 'Stop' })).not.toBeInTheDocument();
  });

  it('appears once the device reports shared_interrupt', () => {
    header(sharedRunning, { ...attachAgent, shared_interrupt: true });
    expect(screen.getByRole('button', { name: 'Stop' })).toBeInTheDocument();
  });

  it('needs the interrupt capability as well as the flag', () => {
    const agent: AgentInfo = {
      ...attachAgent,
      shared_interrupt: true,
      capabilities: attachAgent.capabilities.filter((c) => c !== 'interrupt'),
    };
    expect(canInterruptShared(agent)).toBe(false);
    header(sharedRunning, agent);
    expect(screen.queryByRole('button', { name: 'Stop' })).not.toBeInTheDocument();
  });

  it('still appears on a running remote session', () => {
    header({ ...sharedRunning, control: 'remote' }, claudeAgent);
    expect(screen.getByRole('button', { name: 'Stop' })).toBeInTheDocument();
  });
});

describe.runIf(fixturesAvailable())('A10 status line and list labels', () => {
  it('treats a shared session like a remote one, with no Take over', () => {
    render(
      <StatusLine
        session={sharedRunning}
        agent={attachAgent}
        deviceOnline
        onTakeover={vi.fn()}
      />,
    );
    expect(
      screen.getByText('Claude Code is working · your message will be queued'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Take over' })).not.toBeInTheDocument();
  });

  it('labels a shared session "terminal · attached" in the lists', () => {
    expect(sessionStateLabel(sharedIdle)).toBe('terminal · attached');
    expect(sessionStateLabel(sharedRunning)).toBe('terminal · attached');
    expect(sessionStateLabel(terminalSession())).toBe('terminal');
    expect(sessionStateLabel({ state: 'running', control: 'remote' })).toBe('running');
  });
});

describe.runIf(fixturesAvailable())('A10 delivery chip', () => {
  const chip = (event: UserMessageEvent) => {
    render(<UserMessageRow event={event} />);
    return document.querySelector('.delivery-chip')?.textContent ?? null;
  };

  it('says a held message is waiting for the terminal', () => {
    expect(chip(pendingMessage)).toBe('waiting for the terminal');
  });

  it('says an absorbed message will be re-sent', () => {
    expect(chip(absorbedMessage)).toBe('will be re-sent');
  });

  it('shows nothing once the message is delivered, or on a normal prompt', () => {
    const { unmount } = render(<UserMessageRow event={deliveredMessage} />);
    expect(document.querySelector('.delivery-chip')).toBeNull();
    unmount();
    const plain: UserMessageEvent = { ...pendingMessage };
    delete plain.delivery;
    expect(chip(plain)).toBeNull();
  });

  it('updates the chip when the block is replaced by its delivered copy', () => {
    let timeline = applyEvent(emptyTimeline(), pendingMessage);
    const blockId = pendingMessage.block_id;
    expect((timeline.items[blockId]?.event as UserMessageEvent).delivery).toBe('pending');

    timeline = applyEvent(timeline, deliveredMessage);
    const item = timeline.items[blockId];
    expect((item?.event as UserMessageEvent).delivery).toBe('delivered');
    // One row throughout, held at the seq where the block first appeared.
    expect(timeline.order).toEqual([blockId]);
    expect(item?.seq).toBe(pendingMessage.seq);

    render(<UserMessageRow event={item?.event as UserMessageEvent} />);
    expect(document.querySelector('.delivery-chip')).toBeNull();
  });

  it('clears the "will be re-sent" chip when the device re-injects', () => {
    const absorbed = absorbedMessage;
    let timeline = applyEvent(emptyTimeline(), absorbed);
    expect(
      (timeline.items[absorbed.block_id]?.event as UserMessageEvent).delivery,
    ).toBe('absorbed');

    // §5.2: the re-injection keeps the block id and reports `delivered`.
    timeline = applyEvent(timeline, {
      ...absorbed,
      seq: absorbed.seq + 4,
      first_seq: absorbed.seq,
      delivery: 'delivered',
    });
    const item = timeline.items[absorbed.block_id];
    expect((item?.event as UserMessageEvent).delivery).toBe('delivered');
    expect(timeline.order).toEqual([absorbed.block_id]);

    render(<UserMessageRow event={item?.event as UserMessageEvent} />);
    expect(document.querySelector('.delivery-chip')).toBeNull();
  });
});

describe.runIf(fixturesAvailable())('A10 control transitions', () => {
  // §6.5: a new owner arrives as `meta.control`; `status` follows only when the
  // state moves too, so `control` must never be inferred from a status event.
  const meta = (control: Session['control'], seq: number): MetaEvent => ({
    seq,
    ts: 1788946200000 + seq,
    kind: 'meta',
    control,
  });

  it('follows the attachment registering and dropping', () => {
    let session: Session = { ...sharedIdle, control: 'terminal', state: 'readonly' };

    session = foldSession(session, [meta('shared', 40)]);
    expect(session.control).toBe('shared');
    // The state did not move, so nothing else on the summary may change.
    expect(session.state).toBe('readonly');

    session = foldSession(session, [meta('terminal', 41)]);
    expect(session.control).toBe('terminal');
  });

  it('applies the state only when a status event carries one', () => {
    const session = foldSession({ ...sharedIdle, state: 'running' }, [
      meta('none', 42),
      { seq: 43, ts: 1788946200043, kind: 'status', state: 'stopped' },
    ]);
    expect(session.control).toBe('none');
    expect(session.state).toBe('stopped');
  });

  it('leaves the owner alone when meta carries no control', () => {
    const session = foldSession(sharedIdle, [
      { seq: 44, ts: 1788946200044, kind: 'meta', title: 'Renamed from the terminal' },
    ]);
    expect(session.control).toBe('shared');
    expect(session.title).toBe('Renamed from the terminal');
  });
});
