/**
 * Owns the app socket lifecycle and fans incoming push frames out to the
 * device / session / chat stores.
 */
import { create } from 'zustand';
import { getSocket, setSocket } from '../lib/gateway';
import { AppSocket, socketUrl, type SocketStatus } from '../lib/ws';
import type { PairingStep, PushFrame } from '../protocol/frames';
import type { Device } from '../protocol/types';
import { useChat } from './chat';
import { useDevices } from './devices';
import { useSessions } from './sessions';

export interface PairingProgress {
  code: string;
  step: PairingStep;
  device?: Device;
}

interface ConnectionState {
  status: SocketStatus;
  helloAt: number | null;
  gatewayVersion: string | null;
  protocol: number | null;
  username: string | null;
  stt: { enabled: boolean; languages: string[] };
  /** server_time minus the local clock at the last hello, in milliseconds. */
  clockSkewMs: number;
  pairing: PairingProgress | null;
  onUnauthorized: (() => void) | null;
  connect: (onUnauthorized: () => void) => void;
  disconnect: () => void;
  clearPairing: () => void;
}

export const useConnection = create<ConnectionState>((set, get) => ({
  status: 'idle',
  helloAt: null,
  gatewayVersion: null,
  protocol: null,
  username: null,
  stt: { enabled: false, languages: ['auto'] },
  clockSkewMs: 0,
  pairing: null,
  onUnauthorized: null,

  connect: (onUnauthorized) => {
    if (get().status !== 'idle' && get().status !== 'closed') return;
    const socket = new AppSocket({
      url: socketUrl('/ws/app'),
      onStatus: (status) => set({ status }),
      onFrame: (frame) => handleFrame(frame, set),
      onUnauthorized,
    });
    setSocket(socket);
    set({ onUnauthorized });
    socket.start();
  },

  disconnect: () => {
    getSocket()?.stop();
    setSocket(null);
    set({ status: 'closed', helloAt: null, username: null, pairing: null });
  },

  clearPairing: () => set({ pairing: null }),
}));

type Setter = (partial: Partial<ConnectionState>) => void;

function handleFrame(frame: PushFrame, set: Setter): void {
  switch (frame.type) {
    case 'hello': {
      useDevices.getState().replaceAll(frame.devices);
      useSessions.getState().replaceAll(frame.sessions);
      set({
        helloAt: Date.now(),
        gatewayVersion: frame.gateway_version,
        protocol: frame.protocol,
        username: frame.user.username,
        stt: frame.stt,
        clockSkewMs:
          typeof frame.server_time === 'number' ? frame.server_time - Date.now() : 0,
      });
      return;
    }
    case 'device.updated':
      useDevices.getState().upsert(frame.device);
      return;
    case 'device.removed':
      useDevices.getState().remove(frame.device_id);
      return;
    case 'session.updated':
      useSessions.getState().upsert(frame.session);
      return;
    case 'session.removed':
      useSessions.getState().remove(frame.device_id, frame.session_id);
      return;
    case 'session.event':
      // Amendment A5: `device_id` is present when the gateway stamped it.
      useChat.getState().ingestEvent(frame.session_id, frame.event, frame.device_id);
      return;
    case 'pairing.progress':
      set({
        pairing: {
          code: frame.code,
          step: frame.step,
          ...(frame.device ? { device: frame.device } : {}),
        },
      });
      return;
    default:
      return;
  }
}

export const isOnline = (status: SocketStatus): boolean => status === 'open';
