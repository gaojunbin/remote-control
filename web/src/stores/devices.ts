import { create } from 'zustand';
import { api } from '../lib/api';
import type { Device } from '../protocol/types';

interface DevicesState {
  devices: Device[];
  loaded: boolean;
  error: string | null;
  load: () => Promise<void>;
  replaceAll: (devices: Device[]) => void;
  upsert: (device: Device) => void;
  remove: (deviceId: string) => void;
  rename: (deviceId: string, name: string) => Promise<void>;
  revoke: (deviceId: string) => Promise<void>;
}

const byName = (a: Device, b: Device): number => a.name.localeCompare(b.name);

export const useDevices = create<DevicesState>((set, get) => ({
  devices: [],
  loaded: false,
  error: null,

  load: async () => {
    try {
      const { devices } = await api.devices();
      set({ devices: [...devices].sort(byName), loaded: true, error: null });
    } catch (err) {
      set({ error: err instanceof Error ? err.message : 'failed', loaded: true });
      throw err;
    }
  },

  replaceAll: (devices) => set({ devices: [...devices].sort(byName), loaded: true }),

  upsert: (device) =>
    set((s) => {
      const next = s.devices.filter((d) => d.device_id !== device.device_id);
      next.push(device);
      return { devices: next.sort(byName) };
    }),

  remove: (deviceId) =>
    set((s) => ({ devices: s.devices.filter((d) => d.device_id !== deviceId) })),

  rename: async (deviceId, name) => {
    const { device } = await api.renameDevice(deviceId, name);
    get().upsert(device);
  },

  revoke: async (deviceId) => {
    await api.removeDevice(deviceId);
    get().remove(deviceId);
  },
}));

export const selectDevice = (deviceId: string | undefined) => (s: DevicesState): Device | undefined =>
  deviceId ? s.devices.find((d) => d.device_id === deviceId) : undefined;
