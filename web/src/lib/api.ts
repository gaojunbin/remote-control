/** Thin fetch wrapper for the gateway HTTP API (PROTOCOL-FROZEN.md §2). */
import type { Device, Session, WireError } from '../protocol/types';

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, error: WireError) {
    super(error.message);
    this.name = 'ApiError';
    this.status = status;
    this.code = error.code;
  }
}

export const isUnauthorized = (e: unknown): boolean =>
  e instanceof ApiError && (e.status === 401 || e.code === 'unauthorized');

async function parseError(res: Response): Promise<WireError> {
  try {
    const body = (await res.json()) as { error?: WireError };
    if (body?.error?.code) return body.error;
  } catch {
    /* not JSON */
  }
  return { code: String(res.status), message: res.statusText || 'Request failed' };
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body !== undefined && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  headers.set('Accept', 'application/json');
  const res = await fetch(path, { ...init, headers, credentials: 'same-origin' });
  if (!res.ok) throw new ApiError(res.status, await parseError(res));
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

const get = <T>(path: string) => request<T>(path);
const post = <T>(path: string, body?: unknown) =>
  request<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) });
const patch = <T>(path: string, body: unknown) =>
  request<T>(path, { method: 'PATCH', body: JSON.stringify(body) });
const del = <T>(path: string, body?: unknown) =>
  request<T>(path, { method: 'DELETE', body: body === undefined ? undefined : JSON.stringify(body) });

export interface HealthResponse {
  ok: boolean;
  version: string;
  protocol: number;
  auth: { mode: string };
}

/** A22: the client wheel the gateway serves. Absent in a developer checkout. */
export interface ClientBuildInfo {
  version: string;
  build: string;
  url: string;
}

export interface ConfigResponse {
  public_origin: string;
  stt: { enabled: boolean; languages: string[] };
  push: { web_enabled: boolean; apns_enabled: boolean };
  version: string;
  client?: ClientBuildInfo;
}

export interface SessionResponse {
  ok: boolean;
  user: { username: string };
  exp: number;
}

export interface LoginResponse {
  ok: boolean;
  token: string;
  exp: number;
  user: { username: string };
}

export interface PairingResponse {
  code: string;
  expires_at: number;
  install: { macos: string; linux: string };
}

/** A23: what claiming a host's scan token hands back — an ordinary pairing code. */
export interface PairingClaimResponse {
  code: string;
  expires_at: number;
}

export const api = {
  health: () => get<HealthResponse>('/api/health'),
  config: () => get<ConfigResponse>('/api/config'),
  session: () => get<SessionResponse>('/api/session'),
  login: (password: string, username?: string) =>
    post<LoginResponse>('/api/login', username ? { password, username } : { password }),
  logout: () => post<{ ok: boolean }>('/api/logout'),

  devices: () => get<{ devices: Device[] }>('/api/devices'),
  renameDevice: (deviceId: string, name: string) =>
    patch<{ device: Device }>(`/api/devices/${encodeURIComponent(deviceId)}`, { name }),
  removeDevice: (deviceId: string) =>
    del<{ ok: boolean }>(`/api/devices/${encodeURIComponent(deviceId)}`),

  createPairing: () => post<PairingResponse>('/api/devices/pairing'),
  cancelPairing: (code: string) =>
    del<{ ok: boolean }>(`/api/devices/pairing/${encodeURIComponent(code)}`),
  claimPairingRequest: (token: string) =>
    post<PairingClaimResponse>(`/api/pairing/requests/${encodeURIComponent(token)}/claim`),

  sessions: (params?: { device_id?: string; archived?: boolean }) => {
    const q = new URLSearchParams();
    if (params?.device_id) q.set('device_id', params.device_id);
    if (params?.archived !== undefined) q.set('archived', String(params.archived));
    const qs = q.toString();
    return get<{ sessions: Session[] }>(`/api/sessions${qs ? `?${qs}` : ''}`);
  },

  vapidKey: () => get<{ public_key: string }>('/api/push/web/vapid'),
  subscribePush: (subscription: unknown) =>
    post<{ ok: boolean }>('/api/push/web/subscribe', { subscription }),
  unsubscribePush: (endpoint: string) =>
    del<{ ok: boolean }>('/api/push/web/subscribe', { endpoint }),
};
