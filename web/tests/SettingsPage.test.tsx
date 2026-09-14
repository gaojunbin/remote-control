/**
 * The Timeline group in Settings: the detail level the transcript is drawn at,
 * `docs/DESIGN.md` § "The timeline". Simple is the default.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { SettingsPage } from '../src/features/settings/SettingsPage';
import { useAuth } from '../src/stores/auth';
import { useConnection } from '../src/stores/connection';
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

/**
 * A29 — the Voice group carries dictation polish: one switch, the model the
 * gateway's provider offers, and how far the model may go. `docs/DESIGN.md`
 * § "Polishing what you dictated".
 */
describe('settings: dictation polish', () => {
  const models = [
    { id: 'gpt-4.1-mini', label: 'gpt-4.1-mini' },
    { id: 'gpt-4.1', label: 'gpt-4.1' },
  ];

  const gateway = (polishEnabled: boolean) =>
    useConnection.setState({
      stt: { enabled: true, languages: ['auto', 'en'] },
      polish: { enabled: polishEnabled },
    });

  const answerModels = (status = 200) =>
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        new Response(
          JSON.stringify(
            status === 200 ? { models } : { error: { code: 'upstream', message: 'provider' } },
          ),
          { status, headers: { 'content-type': 'application/json' } },
        ),
      ),
    );

  beforeEach(() => {
    useSettings.setState({ polishEnabled: false, polishModel: '', polishStrength: 'moderate' });
    answerModels();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    useConnection.setState({
      stt: { enabled: false, languages: ['auto'] },
      polish: { enabled: false },
    });
  });

  it('starts off, with no model and the gentler strength', () => {
    expect(useSettings.getState().polishEnabled).toBe(false);
    expect(useSettings.getState().polishModel).toBe('');
    expect(useSettings.getState().polishStrength).toBe('moderate');
  });

  it('offers the switch and, once it is on, the model and the strength', async () => {
    const user = userEvent.setup();
    gateway(true);
    renderPage();

    const polish = screen.getByRole('switch', { name: strings.settings.polish });
    expect(polish).toBeEnabled();
    expect(polish).toHaveAttribute('aria-checked', 'false');
    expect(screen.getByText(strings.settings.polishNote)).toBeInTheDocument();
    // Nothing to choose while it is off.
    expect(screen.queryByRole('button', { name: strings.settings.polishModel })).toBeNull();

    await user.click(polish);

    expect(useSettings.getState().polishEnabled).toBe(true);
    // The first model the gateway listed is chosen, so the switch is enough.
    await waitFor(() => expect(useSettings.getState().polishModel).toBe('gpt-4.1-mini'));
    expect(screen.getByRole('button', { name: strings.settings.polishModel })).toHaveTextContent(
      'gpt-4.1-mini',
    );

    await user.click(screen.getByRole('button', { name: strings.settings.polishModel }));
    await user.click(screen.getByRole('option', { name: 'gpt-4.1' }));
    expect(useSettings.getState().polishModel).toBe('gpt-4.1');

    await user.click(screen.getByRole('button', { name: strings.settings.polishStrong }));
    expect(useSettings.getState().polishStrength).toBe('strong');
  });

  it('keeps the select and says one line when the model list cannot be read', async () => {
    const user = userEvent.setup();
    gateway(true);
    useSettings.setState({ polishEnabled: true, polishModel: 'gpt-4.1' });
    answerModels(502);
    renderPage();

    await waitFor(() =>
      expect(screen.getByText(strings.settings.polishModelsFailed)).toBeInTheDocument(),
    );
    const select = screen.getByRole('button', { name: strings.settings.polishModel });
    expect(select).toHaveTextContent('gpt-4.1');
    await user.click(select);
    expect(screen.getByRole('listbox', { name: strings.settings.polishModel })).toBeInTheDocument();
  });

  it('disables the switch and says why when the gateway has no polish model', () => {
    gateway(false);
    renderPage();

    expect(screen.getByRole('switch', { name: strings.settings.polish })).toBeDisabled();
    expect(screen.getByText(strings.settings.polishServerDisabled)).toBeInTheDocument();
    expect(screen.queryByText(strings.settings.polishNote)).toBeNull();
    expect(fetch).not.toHaveBeenCalled();
  });
});
