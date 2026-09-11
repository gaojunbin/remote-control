/** Local, per-browser preferences. Persisted in localStorage when available. */
import { create } from 'zustand';
import { createJSONStorage, persist, type StateStorage } from 'zustand/middleware';
import type { TimelineDetail } from './timeline';

interface SettingsState {
  sttLanguage: string;
  /** How much of a transcript is drawn. Simple by default, and never sent. */
  timelineDetail: TimelineDetail;
  /** Device groups the user folded shut in a session list. Expanded by default. */
  collapsedDevices: string[];
  /** Devices whose Archive sub-group is open. Collapsed by default. */
  archiveExpanded: string[];
  setSttLanguage: (language: string) => void;
  setTimelineDetail: (detail: TimelineDetail) => void;
  toggleDeviceCollapsed: (deviceId: string) => void;
  toggleArchiveExpanded: (deviceId: string) => void;
}

const toggle = (list: string[], id: string): string[] =>
  list.includes(id) ? list.filter((entry) => entry !== id) : [...list, id];

/**
 * `localStorage` is missing or throws in private windows, in embedded webviews
 * and under strict cookie policies. Preferences are a convenience, so fall back
 * to memory rather than letting the app fail to boot.
 */
function browserStorage(): StateStorage | null {
  try {
    const store = globalThis.localStorage;
    if (!store) return null;
    const probe = '__rc_probe__';
    store.setItem(probe, '1');
    store.removeItem(probe);
    return store;
  } catch {
    return null;
  }
}

function memoryStorage(): StateStorage {
  const map = new Map<string, string>();
  return {
    getItem: (name) => map.get(name) ?? null,
    setItem: (name, value) => void map.set(name, value),
    removeItem: (name) => void map.delete(name),
  };
}

const storage = browserStorage() ?? memoryStorage();

export const useSettings = create<SettingsState>()(
  persist(
    (set) => ({
      sttLanguage: 'auto',
      timelineDetail: 'simple',
      collapsedDevices: [],
      archiveExpanded: [],
      setSttLanguage: (sttLanguage) => set({ sttLanguage }),
      setTimelineDetail: (timelineDetail) => set({ timelineDetail }),
      toggleDeviceCollapsed: (deviceId) =>
        set((s) => ({ collapsedDevices: toggle(s.collapsedDevices, deviceId) })),
      toggleArchiveExpanded: (deviceId) =>
        set((s) => ({ archiveExpanded: toggle(s.archiveExpanded, deviceId) })),
    }),
    { name: 'rc.settings', version: 1, storage: createJSONStorage(() => storage) },
  ),
);
