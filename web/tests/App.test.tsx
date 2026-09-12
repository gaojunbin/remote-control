import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { App } from '../src/App';
import { useAuth } from '../src/stores/auth';
import { useSessions } from '../src/stores/sessions';
import { strings } from '../src/strings';

let requested: string[] = [];

function stubFetch(sessionStatus: number) {
  requested = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input), 'http://gateway.test');
      requested.push(url.pathname);
      if (url.pathname === '/api/session') {
        return sessionStatus === 200
          ? new Response(JSON.stringify({ ok: true, user: { username: 'admin' }, exp: 0 }), {
              status: 200,
              headers: { 'content-type': 'application/json' },
            })
          : new Response(JSON.stringify({ ok: false, error: { code: 'unauthorized' } }), {
              status: 401,
              headers: { 'content-type': 'application/json' },
            });
      }
      return new Response(JSON.stringify({}), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      });
    }),
  );
}

beforeEach(() => {
  useAuth.setState({ status: 'unknown', username: null, config: null, version: null });
  useSessions.setState({ loaded: false, sessions: {} });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('App routing', () => {
  it('shows the login screen for a protected path when the session probe fails', async () => {
    stubFetch(401);
    render(
      <MemoryRouter initialEntries={['/sessions']}>
        <App />
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByText(strings.login.subtitle)).toBeInTheDocument());
    expect(screen.queryByRole('heading', { name: strings.sessions.title })).not.toBeInTheDocument();
  });

  it('returns to a /pair link, fragment and all, once the password lands (A23)', async () => {
    stubFetch(401);
    render(
      <MemoryRouter initialEntries={['/pair#7ZK3M9Q2X5H8B1V4N6P0R2T4W6']}>
        <App />
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByText(strings.login.subtitle)).toBeInTheDocument());
    // The claim token lives in the fragment, so the whole path has to survive
    // the round trip through the login screen.
    useAuth.setState({ status: 'signed-in', username: 'admin' });

    await waitFor(() =>
      expect(screen.getByRole('heading', { name: strings.pairing.claimTitle })).toBeInTheDocument(),
    );
    await waitFor(() =>
      expect(requested).toContain('/api/pairing/requests/7ZK3M9Q2X5H8B1V4N6P0R2T4W6/claim'),
    );
  });

  it('never issues an authenticated request while signed out', async () => {
    stubFetch(401);
    render(
      <MemoryRouter initialEntries={['/sessions']}>
        <App />
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByText(strings.login.subtitle)).toBeInTheDocument());
    // Give any mount effect of a protected page the chance to fire.
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(requested).toEqual(['/api/session']);
  });
});
