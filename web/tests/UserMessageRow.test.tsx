/**
 * A30: Claude Code files another agent's words as a user turn — a teammate's
 * report, a background task's notification. The row must never let those pass
 * for something the person typed. `docs/DESIGN.md` § "Messages from other
 * agents".
 */
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { UserMessageRow } from '../src/features/chat/blocks/UserMessageRow';
import { useSettings } from '../src/stores/settings';
import { en, strings } from '../src/strings';
import { zhHans } from '../src/strings.zh-Hans';
import type { Trigger, UserMessageEvent } from '../src/protocol/types';
import { fixturesAvailable, readFixture } from './fixtures';

const message = (source: Trigger, text = 'what broke the layout?'): UserMessageEvent => ({
  seq: 9,
  ts: 1_788_944_409_000,
  kind: 'user_message',
  block_id: 'blk-1',
  text,
  source,
});

/** The bubble, whatever the row wrapped it in. */
const bubble = (): HTMLElement => {
  const element = document.querySelector('.user-bubble');
  if (!element) throw new Error('no bubble rendered');
  return element as HTMLElement;
};

afterEach(() => {
  cleanup();
  useSettings.setState({ language: 'en' });
});

describe('UserMessageRow', () => {
  it('captions a message another agent put into the conversation and mutes it', () => {
    render(<UserMessageRow event={message('agent', 'recon-ios: Recon complete.')} />);
    expect(screen.getByText(en.chat.fromAgent)).toBeInTheDocument();
    expect(bubble().className).toContain('from-agent');
    // The text is the reduced report, drawn as it arrived.
    expect(screen.getByText('recon-ios: Recon complete.')).toBeInTheDocument();
  });

  it('says where a terminal-typed message was typed, in the same place', () => {
    render(<UserMessageRow event={message('terminal')} />);
    expect(screen.getByText(en.chat.fromTerminal)).toBeInTheDocument();
    expect(bubble().className).not.toContain('from-agent');
  });

  it('captions nothing for a message an app sent', () => {
    render(<UserMessageRow event={message('remote')} />);
    expect(document.querySelector('.user-origin')).toBeNull();
    expect(bubble().className).not.toContain('from-agent');
  });

  it('says both captions in the interface language', () => {
    useSettings.setState({ language: 'zh-Hans' });
    expect(strings.chat.fromAgent).toBe(zhHans.chat.fromAgent);
    render(<UserMessageRow event={message('agent')} />);
    expect(screen.getByText(zhHans.chat.fromAgent)).toBeInTheDocument();
    cleanup();
    render(<UserMessageRow event={message('terminal')} />);
    expect(screen.getByText(zhHans.chat.fromTerminal)).toBeInTheDocument();
  });

  it.runIf(fixturesAvailable())('draws the frozen A30 fixture', () => {
    const event = readFixture<UserMessageEvent>('events/user_message.agent.json');
    expect(event.source).toBe('agent');
    render(<UserMessageRow event={event} />);
    expect(screen.getByText(en.chat.fromAgent)).toBeInTheDocument();
    expect(screen.getByText(event.text)).toBeInTheDocument();
    expect(bubble().className).toContain('from-agent');
  });
});
