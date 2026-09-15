/**
 * The person's own messages, and only those. A34 moved another agent's words
 * out of this row entirely — `tests/AgentMessageRow.test.tsx` covers them.
 * `docs/DESIGN.md` § "The timeline".
 */
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { UserMessageRow } from '../src/features/chat/blocks/UserMessageRow';
import { useSettings } from '../src/stores/settings';
import { en } from '../src/strings';
import { zhHans } from '../src/strings.zh-Hans';
import type { Trigger, UserMessageEvent } from '../src/protocol/types';

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
  it('says where a terminal-typed message was typed', () => {
    render(<UserMessageRow event={message('terminal')} />);
    expect(screen.getByText(en.chat.fromTerminal)).toBeInTheDocument();
  });

  it('captions nothing for a message an app sent', () => {
    render(<UserMessageRow event={message('remote')} />);
    expect(document.querySelector('.user-origin')).toBeNull();
    expect(bubble().textContent).toContain('what broke the layout?');
  });

  it('says the caption in the interface language', () => {
    useSettings.setState({ language: 'zh-Hans' });
    render(<UserMessageRow event={message('terminal')} />);
    expect(screen.getByText(zhHans.chat.fromTerminal)).toBeInTheDocument();
  });

  it('has no variant left for another agent to borrow (A34)', () => {
    render(<UserMessageRow event={message('agent')} />);
    expect(bubble().className).not.toContain('from-agent');
    expect(screen.queryByText(en.chat.fromAgent)).toBeNull();
  });
});
