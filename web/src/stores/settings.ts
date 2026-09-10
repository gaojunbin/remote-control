/** Local, per-browser preferences. Persisted in localStorage when available. */
import { create } from 'zustand';
import { createJSONStorage, persist, type StateStorage } from 'zustand/middleware';

interface SettingsState {
  sttLanguage: string;
  pushToTalk: boolean;
  showArchived: boolean;
  /** Whether the Archive group at the bottom of a session list is open. */
  archiveExpanded: boolean;
  setSttLanguage: (language: string) => void;
  setPushToTalk: (enabled: boolean) => void;
  setShowArchived: (show: boolean) => void;
  setArchiveExpanded: (expanded: boolean) => void;
}

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
      pushToTalk: true,
      showArchived: false,
      archiveExpanded: false,
      setSttLanguage: (sttLanguage) => set({ sttLanguage }),
      setPushToTalk: (pushToTalk) => set({ pushToTalk }),
      setShowArchived: (showArchived) => set({ showArchived }),
      setArchiveExpanded: (archiveExpanded) => set({ archiveExpanded }),
    }),
    { name: 'rc.settings', storage: createJSONStorage(() => storage) },
  ),
);
