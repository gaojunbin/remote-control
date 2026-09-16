/**
 * What has been typed and attached for a conversation, keyed by session key.
 *
 * `docs/DESIGN.md` § "The composer" — **A draft belongs to its session**: words
 * typed and files attached for one session stay with it when another is opened,
 * and are found again on return. The composer used to keep both in its own
 * component state, which the router reused across a session switch, so A's
 * words were sent to B. They live here instead: the composer reads the entry
 * its session names and writes back to the same one.
 *
 * In memory for the tab's life, and emptied on sign-out with every other store
 * (`signOut`). Nothing is persisted: a draft is not worth a round trip, and a
 * shared browser must not hand it to the next account.
 */
import { create } from 'zustand';
import { MAX_ATTACHMENTS, type AttachmentDraft } from '../features/chat/attachments';

export interface Draft {
  text: string;
  attachments: AttachmentDraft[];
}

/** Stable identity, so a session nobody has typed into does not redraw. */
export const EMPTY_DRAFT: Draft = { text: '', attachments: [] };

interface DraftsState {
  drafts: Record<string, Draft>;
  setText: (key: string, text: string) => void;
  /**
   * Append files to a draft, never past the contract's cap. Two attach
   * operations can be in flight at once — a paste and a file dialog — and each
   * computed its budget from the count it read before it started, so the cap is
   * enforced here, where the list is written. Returns how many were dropped.
   */
  addAttachments: (key: string, files: readonly AttachmentDraft[]) => number;
  removeAttachment: (key: string, index: number) => void;
  /** Hand back the files a refused send took, unless newer ones are there. */
  restoreAttachments: (key: string, files: readonly AttachmentDraft[]) => void;
  clear: (key: string) => void;
  reset: () => void;
}

function write(
  state: DraftsState,
  key: string,
  update: (draft: Draft) => Draft,
): Partial<DraftsState> {
  const previous = state.drafts[key] ?? EMPTY_DRAFT;
  const next = update(previous);
  if (next === previous) return {};
  if (next.text.length === 0 && next.attachments.length === 0) {
    if (!state.drafts[key]) return {};
    const drafts = { ...state.drafts };
    delete drafts[key];
    return { drafts };
  }
  return { drafts: { ...state.drafts, [key]: next } };
}

export const useDrafts = create<DraftsState>((set, get) => ({
  drafts: {},

  setText: (key, text) =>
    set((state) => write(state, key, (draft) => (draft.text === text ? draft : { ...draft, text }))),

  addAttachments: (key, files) => {
    const before = (get().drafts[key] ?? EMPTY_DRAFT).attachments.length;
    const dropped = Math.max(0, before + files.length - MAX_ATTACHMENTS);
    if (files.length === 0) return 0;
    set((state) =>
      write(state, key, (draft) => ({
        ...draft,
        attachments: [...draft.attachments, ...files].slice(0, MAX_ATTACHMENTS),
      })),
    );
    return dropped;
  },

  removeAttachment: (key, index) =>
    set((state) =>
      write(state, key, (draft) => ({
        ...draft,
        attachments: draft.attachments.filter((_, i) => i !== index),
      })),
    ),

  restoreAttachments: (key, files) =>
    set((state) =>
      write(state, key, (draft) =>
        draft.attachments.length === 0 ? { ...draft, attachments: [...files] } : draft,
      ),
    ),

  clear: (key) =>
    set((state) => {
      if (!state.drafts[key]) return {};
      const drafts = { ...state.drafts };
      delete drafts[key];
      return { drafts };
    }),

  reset: () => set({ drafts: {} }),
}));

export const draftOf =
  (key: string) =>
  (state: DraftsState): Draft =>
    state.drafts[key] ?? EMPTY_DRAFT;
