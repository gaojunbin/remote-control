/**
 * The Timeline group in Settings: the detail level the transcript is drawn at,
 * `docs/DESIGN.md` § "The timeline". Simple is the default.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { SettingsPage } from '../src/features/settings/SettingsPage';
import { useAuth } from '../src/stores/auth';
import { useSettings } from '../src/stores/settings';
import { strings } from '../src/strings';

beforeEach(() => {
  useSettings.setState({ language: 'en' });
  useAuth.setState({ status: 'signed-in', username: 'admin', role: 'admin' });
});

const renderPage = () =>
  render(
    <MemoryRouter>
      <SettingsPage />
    </MemoryRouter>,
  );

describe('settings: timeline detail', () => {
  it('starts at Simple, before anything is rendered', () => {
    expect(useSettings.getState().timelineDetail).toBe('simple');
  });

  it('offers the two levels under a Timeline group, with the explanation', () => {
    renderPage();

    expect(screen.getByRole('heading', { name: strings.settings.timeline })).toBeInTheDocument();
    expect(screen.getByText(strings.settings.timelineDetail)).toBeInTheDocument();
    expect(screen.getByText(strings.settings.timelineDetailNote)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: strings.settings.timelineDetail })).toHaveTextContent(
      'Simple',
    );
  });

  it('writes the level the reader picks', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: strings.settings.timelineDetail }));
    await user.click(screen.getByRole('option', { name: 'Detailed' }));

    expect(useSettings.getState().timelineDetail).toBe('detailed');
    expect(screen.getByRole('button', { name: strings.settings.timelineDetail })).toHaveTextContent(
      'Detailed',
    );
  });
});

/**
 * The Language group, above Timeline. `docs/DESIGN.md` § "The three screens":
 * English and 中文, each written in its own script, English by default whatever
 * the system language is, and the app's own words only.
 */
describe('settings: interface language', () => {
  it('starts in English, before anything is rendered', () => {
    expect(useSettings.getState().language).toBe('en');
  });

  it('offers the two languages, each in its own script, above Timeline', () => {
    renderPage();

    const headings = [...document.querySelectorAll('.group-title')].map((el) => el.textContent);
    expect(headings.indexOf(strings.settings.language)).toBe(
      headings.indexOf(strings.settings.timeline) - 1,
    );
    expect(screen.getByRole('button', { name: strings.settings.language })).toHaveTextContent(
      'English',
    );
  });

  it('writes the language the reader picks, and says it in that language', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: strings.settings.language }));
    await user.click(screen.getByRole('option', { name: '中文' }));

    expect(useSettings.getState().language).toBe('zh-Hans');
    expect(screen.getByRole('heading', { name: '设置' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '语言' })).toHaveTextContent('中文');
  });
});


/**
 * A24: the Account group names the signed-in account and its role, and carries
 * the one row that account is allowed — Users for an admin, Change password for
 * a member. `docs/DESIGN.md` § "Accounts".
 */
describe('settings: the account', () => {
  it('shows the username with its role word under it', () => {
    useAuth.setState({ username: 'alice', role: 'member' });
    renderPage();

    expect(screen.getByText('alice')).toBeInTheDocument();
    expect(screen.getByText('Member')).toBeInTheDocument();
  });

  it('offers a member its own password and no accounts screen', () => {
    useAuth.setState({ username: 'alice', role: 'member' });
    renderPage();

    expect(screen.getByRole('button', { name: strings.settings.changePassword })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: strings.users.title })).not.toBeInTheDocument();
  });

  it('offers an admin the accounts screen and not a password it cannot change', () => {
    renderPage();

    expect(screen.getByRole('button', { name: strings.users.title })).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: strings.settings.changePassword }),
    ).not.toBeInTheDocument();
  });

  it("posts a member's password change to /api/password", async () => {
    const user = userEvent.setup();
    useAuth.setState({ username: 'alice', role: 'member' });
    const calls: { path: string; body: Record<string, unknown> }[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        calls.push({
          path: new URL(String(input), 'http://gateway.test').pathname,
          body: JSON.parse(String(init?.body ?? '{}')) as Record<string, unknown>,
        });
        return new Response(JSON.stringify({ ok: true }), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        });
      }),
    );
    renderPage();

    await user.click(screen.getByRole('button', { name: strings.settings.changePassword }));
    await user.type(screen.getByLabelText(strings.account.currentPassword), 'oldoldold');
    await user.type(screen.getByLabelText(strings.account.newPassword), 'devdevdev');
    await user.click(screen.getByRole('button', { name: strings.common.save }));

    await waitFor(() => expect(calls.find((c) => c.path === '/api/password')).toBeDefined());
    expect(calls.find((c) => c.path === '/api/password')?.body).toEqual({
      current_password: 'oldoldold',
      new_password: 'devdevdev',
    });
    vi.unstubAllGlobals();
  });
});
