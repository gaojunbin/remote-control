/** Local, per-browser preferences. Persisted in localStorage when available. */
import { create } from 'zustand';
import { createJSONStorage, persist, type StateStorage } from 'zustand/middleware';
import type { TimelineDetail } from './timeline';

/**
 * The language the app speaks its own words in. It is English until the reader
 * asks for something else — never guessed from `navigator.language`, because a
 * developer whose system is Chinese still reads the agent in English and a
 * surprise translation at first launch reads as a different product.
 */
export type InterfaceLanguage = 'en' | 'zh-Hans';

export const INTERFACE_LANGUAGES: InterfaceLanguage[] = ['en', 'zh-Hans'];

interface SettingsState {
  /** The app's own words. Never applied to anything a device reported. */
  language: InterfaceLanguage;
  sttLanguage: string;
  /** How much of a transcript is drawn. Simple by default, and never sent. */
  timelineDetail: TimelineDetail;
  /** Device groups the user folded shut in a session list. Expanded by default. */
  collapsedDevices: string[];
  /** Devices whose Archive sub-group is open. Collapsed by default. */
  archiveExpanded: string[];
  setLanguage: (language: InterfaceLanguage) => void;
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
      language: 'en',
      sttLanguage: 'auto',
      timelineDetail: 'simple',
      collapsedDevices: [],
      archiveExpanded: [],
      setLanguage: (language) => set({ language }),
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
