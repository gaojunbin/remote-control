/** Session cookie state. The token itself never touches JavaScript. */
import { create } from 'zustand';
import { api, isUnauthorized, type ConfigResponse } from '../lib/api';

type AuthStatus = 'unknown' | 'signed-in' | 'signed-out';

interface AuthState {
  status: AuthStatus;
  username: string | null;
  config: ConfigResponse | null;
  version: string | null;
  protocol: number | null;
  check: () => Promise<void>;
  login: (password: string) => Promise<void>;
  logout: () => Promise<void>;
  markSignedOut: () => void;
  loadConfig: () => Promise<void>;
}

export const useAuth = create<AuthState>((set, get) => ({
  status: 'unknown',
  username: null,
  config: null,
  version: null,
  protocol: null,

  loadConfig: async () => {
    try {
      const [config, health] = await Promise.all([api.config(), api.health()]);
      set({ config, version: health.version, protocol: health.protocol });
    } catch {
      /* about/diagnostics detail is optional for rendering */
    }
  },

  check: async () => {
    try {
      const session = await api.session();
      set({ status: 'signed-in', username: session.user.username });
      void get().loadConfig();
    } catch (err) {
      set({ status: 'signed-out', username: null });
      if (!isUnauthorized(err)) {
        // Network failure: the login screen surfaces "cannot reach the gateway".
        set({ config: null });
      }
    }
  },

  login: async (password) => {
    const res = await api.login(password);
    set({ status: 'signed-in', username: res.user.username });
    void get().loadConfig();
  },

  logout: async () => {
    try {
      await api.logout();
    } finally {
      set({ status: 'signed-out', username: null });
    }
  },

  markSignedOut: () => set({ status: 'signed-out', username: null }),
}));
