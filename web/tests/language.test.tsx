/**
 * The interface language. `docs/DESIGN.md` § "The three screens" (Settings):
 * English and 中文, English by default whatever the browser says, applied at
 * once on every open screen, and never applied to anything a device reported.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { App } from '../src/App';
import { Composer } from '../src/features/chat/Composer';
import { relativeAgo, relativeTime } from '../src/lib/format';
import { en, sessionStateLabel, stringTables, strings } from '../src/strings';
import { useAuth } from '../src/stores/auth';
import { useSessions } from '../src/stores/sessions';
import { useSettings } from '../src/stores/settings';
import { claudeAgent } from '../mock/fixtures';
import type { Session } from '../src/protocol/types';

/** Every leaf path in a table, so a missing nested key is a failure, not a gap. */
function leaves(value: unknown, prefix = ''): string[] {
  if (typeof value !== 'object' || value === null) return [`${prefix}:${typeof value}`];
  return Object.entries(value).flatMap(([key, child]) =>
    leaves(child, prefix ? `${prefix}.${key}` : key),
  );
}

beforeEach(() => {
  useSettings.setState({ language: 'en' });
});

describe('the string tables', () => {
  it('carry the same keys, of the same kinds, in both languages', () => {
    expect(leaves(stringTables['zh-Hans'])).toEqual(leaves(en));
  });

  it('leave the product and the agent names in Latin script', () => {
    expect(stringTables['zh-Hans'].productName).toBe(en.productName);
    expect(stringTables['zh-Hans'].newSession.git).toBe('Git');
  });

  it('starts in English, whatever language the browser reports', async () => {
    Object.defineProperty(window.navigator, 'language', {
      value: 'zh-CN',
      configurable: true,
    });
    Object.defineProperty(window.navigator, 'languages', {
      value: ['zh-CN', 'zh'],
      configurable: true,
    });
    vi.resetModules();
    const fresh = await import('../src/stores/settings');
    expect(fresh.useSettings.getState().language).toBe('en');
  });
});

describe('a screen already on the page', () => {
  class SilentSocket {
    close(): void {}
    send(): void {}
  }

  beforeEach(() => {
    vi.stubGlobal('WebSocket', SilentSocket);
    vi.stubGlobal(
      'fetch',
      vi.fn(
        async () =>
          new Response(JSON.stringify({ ok: true, user: { username: 'admin' }, exp: 0 }), {
            status: 200,
            headers: { 'content-type': 'application/json' },
          }),
      ),
    );
    useAuth.setState({ status: 'signed-in', username: 'admin', config: null, version: null });
    useSessions.setState({ loaded: true, sessions: {} });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    useSettings.setState({ language: 'en' });
  });

  it('changes language the moment the setting does', async () => {
    render(
      <MemoryRouter initialEntries={['/sessions']}>
        <App />
      </MemoryRouter>,
    );
    await screen.findByRole('heading', { name: 'Sessions' });

    act(() => useSettings.getState().setLanguage('zh-Hans'));

    await waitFor(() => expect(screen.getByRole('heading', { name: '会话' })).toBeInTheDocument());
    expect(screen.queryByRole('heading', { name: 'Sessions' })).not.toBeInTheDocument();
    expect(document.documentElement.lang).toBe('zh-Hans');
    // The shell around the page follows too, not only the page itself.
    expect([...document.querySelectorAll('.tab')].map((tab) => tab.textContent)).toEqual([
      '设备',
      '会话',
      '设置',
    ]);
  });
});

describe('the words themselves', () => {
  const session: Session = {
    session_id: 'ses-1',
    device_id: 'dev-1',
    agent: 'claude',
    title: 'Fix flaky auth test',
    cwd: '/Users/me/dev/gateway',
    git: null,
    state: 'running',
    state_detail: null,
    origin: 'remote',
    control: 'remote',
    model: 'claude-sonnet-4-5',
    permission_mode: 'default',
    effort: 'high',
    created_at: 1,
    updated_at: 2,
    last_seq: 0,
    archived: false,
    turn: null,
    todos: null,
    usage: null,
    queued: 0,
  };

  it('labels the composer button in Chinese', () => {
    useSettings.setState({ language: 'zh-Hans' });
    render(
      <Composer
        session={session}
        agent={claudeAgent}
        deviceOnline
        queue={[]}
        sttEnabled={false}
        sttLanguages={['auto']}
        question={null}
        onAnswer={vi.fn().mockResolvedValue(undefined)}
        onSend={vi.fn().mockResolvedValue(undefined)}
        onSetOption={vi.fn()}
        onRemoveQueued={vi.fn()}
        onTakeover={vi.fn()}
      />,
    );

    // Claude Code cannot be steered, so the primary reads "Queue".
    expect(screen.getByRole('button', { name: '排队' })).toBeInTheDocument();
    expect(screen.getByLabelText('给 agent 发消息…')).toBeInTheDocument();
    // What the device reported is not translated, in either half of the chip.
    expect(screen.getByRole('button', { name: strings.composer.modelCard })).toHaveTextContent(
      'Sonnet 4.5 High',
    );
  });

  it('translates the words in a relative time and leaves the numbers alone', () => {
    const now = Date.UTC(2026, 8, 13, 12, 0, 0);
    expect(relativeTime(now - 3 * 60_000, now)).toBe('3m');
    expect(relativeAgo(now - 3 * 60_000, now)).toBe('3m ago');

    useSettings.setState({ language: 'zh-Hans' });
    expect(relativeTime(now - 3 * 60_000, now)).toBe('3 分钟');
    expect(relativeAgo(now - 3 * 60_000, now)).toBe('3 分钟前');
    expect(relativeAgo(now - 36 * 3_600_000, now)).toBe('昨天');
  });

  it('translates a session state, and the terminal it is attached to', () => {
    expect(sessionStateLabel({ state: 'running', control: 'remote' })).toBe('running');
    expect(sessionStateLabel({ state: 'idle', control: 'shared' })).toBe('terminal · attached');

    useSettings.setState({ language: 'zh-Hans' });
    expect(sessionStateLabel({ state: 'running', control: 'remote' })).toBe('运行中');
    expect(sessionStateLabel({ state: 'idle', control: 'shared' })).toBe('终端 · 已连接');
    expect(sessionStateLabel({ state: 'running', control: 'terminal' })).toBe('终端 · 运行中');
  });
});
