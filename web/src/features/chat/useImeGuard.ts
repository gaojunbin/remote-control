import { useCallback, useRef, type KeyboardEvent } from 'react';

/**
 * Tells an Enter that belongs to the input method from one meant for Send.
 *
 * Under a Chinese or Japanese IME the letters are composed first and Enter
 * confirms them; that Enter must not send. Chrome and Firefox mark the
 * keydown itself (`isComposing`, keyCode 229) and the composition events
 * bracket it. WebKit — Safari on the Mac and on iOS — fires `compositionend`
 * *before* the keydown of the Enter that confirmed the text, with
 * `isComposing` already false (WebKit bug 165004), so that keydown alone
 * cannot tell. Both events come out of one key press, dispatched
 * synchronously in one task, so a composition that ended in the current task
 * still owns the Enter that follows it; a zero timer lets go of the claim
 * before the next task begins, long before a person can press Enter again.
 */
export function useImeGuard() {
  const composing = useRef(false);
  const endedThisTask = useRef(false);

  const onCompositionStart = useCallback(() => {
    composing.current = true;
  }, []);

  const onCompositionEnd = useCallback(() => {
    composing.current = false;
    endedThisTask.current = true;
    setTimeout(() => {
      endedThisTask.current = false;
    }, 0);
  }, []);

  const ownsEnter = useCallback(
    (e: KeyboardEvent) => composing.current || e.nativeEvent.isComposing || endedThisTask.current,
    [],
  );

  return { onCompositionStart, onCompositionEnd, ownsEnter };
}
