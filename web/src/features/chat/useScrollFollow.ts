import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';

const BOTTOM_THRESHOLD_PX = 56;
const TOP_TRIGGER_PX = 120;

interface Options {
  /** The session's last applied seq; changes on every event, streaming included. */
  revision: number;
  /** Key of the first row, so a prepended history page can be told from a tail append. */
  firstKey: string | null;
  /** Key of the last row; a change means a genuinely new block arrived. */
  lastKey: string | null;
  onReachTop: () => void;
}

/**
 * Keeps the timeline pinned to the bottom while the user has not scrolled away,
 * counts new blocks missed otherwise, and preserves the scroll anchor when an
 * older history page is prepended.
 */
export function useScrollFollow({ revision, firstKey, lastKey, onReachTop }: Options) {
  const ref = useRef<HTMLDivElement>(null);
  const [following, setFollowing] = useState(true);
  const [missed, setMissed] = useState(0);
  const previousHeight = useRef(0);
  const previousFirstKey = useRef(firstKey);
  // Written only from event handlers and effects, never during render.
  const followingRef = useRef(true);

  const scrollToBottom = useCallback((behavior: ScrollBehavior = 'auto') => {
    const el = ref.current;
    if (!el) return;
    el.scrollTo({ top: el.scrollHeight, behavior });
    followingRef.current = true;
    setFollowing(true);
    setMissed(0);
  }, []);

  const onScroll = useCallback(() => {
    const el = ref.current;
    if (!el) return;
    const distance = el.scrollHeight - el.scrollTop - el.clientHeight;
    const atBottom = distance <= BOTTOM_THRESHOLD_PX;
    followingRef.current = atBottom;
    setFollowing(atBottom);
    if (atBottom) setMissed(0);
    if (el.scrollTop <= TOP_TRIGGER_PX) onReachTop();
  }, [onReachTop]);

  // Only a prepended history page moves the anchor. Content appended below the
  // viewport must not drag the reader down.
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const prepended = firstKey !== null && firstKey !== previousFirstKey.current;
    if (prepended && !followingRef.current) {
      const delta = el.scrollHeight - previousHeight.current;
      if (delta > 0) el.scrollTop += delta;
    }
    previousFirstKey.current = firstKey;
    previousHeight.current = el.scrollHeight;
  }, [firstKey, revision]);

  useEffect(() => {
    if (followingRef.current) scrollToBottom();
  }, [revision, scrollToBottom]);

  // Count blocks, not streaming deltas: the badge should read "2 updates" for
  // two messages, not once per 80 ms flush.
  useEffect(() => {
    if (lastKey === null) return;
    if (!followingRef.current) setMissed((n) => n + 1);
  }, [lastKey]);

  return { ref, following, missed, scrollToBottom, onScroll };
}
