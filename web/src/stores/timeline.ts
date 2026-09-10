/**
 * Pure timeline reducer: session events in, ordered renderable items out.
 *
 * Contract rules implemented here (PROTOCOL-FROZEN.md §4, §7, A8):
 *  - a later event with the same `block_id` fully replaces the earlier one,
 *    except `delta` events on `assistant_text` / `thinking`, which append;
 *  - events whose `seq` is not greater than the last applied one are dropped;
 *  - `status`, `meta`, `queue` and `todos` are session state, not timeline items;
 *  - events carrying `parent_block_id` belong under their parent tool row;
 *  - blocks are ordered by `first_seq ?? seq`, so a tool call that finishes long
 *    after it started keeps its place — live and after a reload.
 */
import { eventKey } from '../lib/ids';
import type { SessionEvent } from '../protocol/types';

export interface TimelineItem {
  key: string;
  /** Ordering key: `first_seq ?? seq` of the block's first appearance. */
  seq: number;
  ts: number;
  parentBlockId: string | null;
  event: SessionEvent;
}

export interface TimelineState {
  order: string[];
  items: Record<string, TimelineItem>;
  lastSeq: number;
  oldestSeq: number | null;
}

export function emptyTimeline(): TimelineState {
  return { order: [], items: {}, lastSeq: 0, oldestSeq: null };
}

/** Event kinds that never produce a timeline item. */
const STATE_ONLY = new Set(['status', 'meta', 'queue', 'todos', 'turn_started']);

function isRenderable(event: SessionEvent): boolean {
  if (STATE_ONLY.has(event.kind)) return false;
  // A clean turn boundary needs no row; interrupted / failed turns do.
  if (event.kind === 'turn_completed') return event.stop_reason !== 'completed';
  return true;
}

function keyFor(event: SessionEvent): string {
  if ('block_id' in event && typeof event.block_id === 'string' && event.block_id) {
    return event.block_id;
  }
  return eventKey(event.kind, event.seq);
}

/**
 * Where the block belongs in the timeline. `first_seq` wins when the device
 * sends it; otherwise the earliest `seq` this app has seen for the block does.
 */
function orderSeq(event: SessionEvent, previous: TimelineItem | undefined): number {
  const declared = typeof event.first_seq === 'number' ? event.first_seq : event.seq;
  return previous ? Math.min(previous.seq, declared) : declared;
}

function streamingText(previous: SessionEvent | undefined, next: SessionEvent): string | undefined {
  if (next.kind !== 'assistant_text' && next.kind !== 'thinking') return undefined;
  if (next.delta === undefined) return next.text;
  const prevText =
    previous && (previous.kind === 'assistant_text' || previous.kind === 'thinking')
      ? (previous.text ?? '')
      : '';
  return prevText + next.delta;
}

/** Merge one event into an existing item (or create it). Returns null when not renderable. */
function mergeItem(previous: TimelineItem | undefined, event: SessionEvent): TimelineItem | null {
  if (!isRenderable(event)) return null;
  const key = keyFor(event);
  const text = streamingText(previous?.event, event);
  const merged: SessionEvent =
    text === undefined
      ? event
      : ({ ...event, text, delta: undefined } as SessionEvent);
  return {
    key,
    seq: orderSeq(event, previous),
    ts: event.ts,
    parentBlockId: event.parent_block_id ?? previous?.parentBlockId ?? null,
    event: merged,
  };
}

/**
 * Insert a key so `order` stays sorted by ordering seq. Scans from the end, so
 * the common case — a block that belongs last — costs one comparison.
 */
function insertOrdered(
  order: readonly string[],
  items: Record<string, TimelineItem>,
  key: string,
  seq: number,
): string[] {
  let index = order.length;
  while (index > 0) {
    const previous = items[order[index - 1] as string];
    if (previous !== undefined && previous.seq > seq) index -= 1;
    else break;
  }
  if (index === order.length) return [...order, key];
  const next = [...order];
  next.splice(index, 0, key);
  return next;
}

/** Apply a live event. Out-of-order or duplicate frames are dropped. */
export function applyEvent(state: TimelineState, event: SessionEvent): TimelineState {
  if (event.seq <= state.lastSeq) return state;
  const key = keyFor(event);
  const previous = state.items[key];
  const item = mergeItem(previous, event);
  const lastSeq = event.seq;
  const oldestSeq = state.oldestSeq === null ? event.seq : Math.min(state.oldestSeq, event.seq);
  if (!item) return { ...state, lastSeq };

  const items = { ...state.items, [key]: item };
  let order = state.order;
  if (previous === undefined) {
    order = insertOrdered(order, items, key, item.seq);
  } else if (previous.seq !== item.seq) {
    // A replacement revealed an earlier `first_seq`: move the row back.
    order = insertOrdered(
      order.filter((k) => k !== key),
      items,
      key,
      item.seq,
    );
  }
  return { order, items, lastSeq, oldestSeq };
}

export function applyEvents(state: TimelineState, events: readonly SessionEvent[]): TimelineState {
  return events.reduce(applyEvent, state);
}

/**
 * Merge an older history page (ascending `seq`, ending before the oldest known
 * event). Existing items win: they are at least as fresh as the history copy.
 */
export function mergeHistory(state: TimelineState, events: readonly SessionEvent[]): TimelineState {
  if (events.length === 0) return state;
  const items = { ...state.items };
  const added: TimelineItem[] = [];
  let oldest = state.oldestSeq;
  for (const event of events) {
    if (oldest === null || event.seq < oldest) oldest = event.seq;
    const key = keyFor(event);
    if (items[key]) continue;
    const item = mergeItem(undefined, event);
    if (!item) continue;
    items[key] = item;
    added.push(item);
  }
  // Both sides are already sorted by ordering seq, so merge instead of sorting.
  added.sort((a, b) => a.seq - b.seq);
  const order = mergeSorted(added, state.order, items);
  return {
    order,
    items,
    lastSeq: Math.max(state.lastSeq, events[events.length - 1]?.seq ?? 0),
    oldestSeq: oldest,
  };
}

function mergeSorted(
  added: readonly TimelineItem[],
  existing: readonly string[],
  items: Record<string, TimelineItem>,
): string[] {
  const out: string[] = [];
  let i = 0;
  let j = 0;
  while (i < added.length && j < existing.length) {
    const left = added[i] as TimelineItem;
    const right = items[existing[j] as string];
    if (right === undefined || left.seq <= right.seq) {
      out.push(left.key);
      i += 1;
    } else {
      out.push(existing[j] as string);
      j += 1;
    }
  }
  for (; i < added.length; i += 1) out.push((added[i] as TimelineItem).key);
  for (; j < existing.length; j += 1) out.push(existing[j] as string);
  return out;
}

/** Replace one block with its full, untruncated version (`session.block`). */
export function replaceBlock(state: TimelineState, event: SessionEvent): TimelineState {
  const key = keyFor(event);
  const previous = state.items[key];
  if (!previous) return state;
  return {
    ...state,
    items: { ...state.items, [key]: { ...previous, ts: event.ts, event } },
  };
}

export interface TimelineView {
  roots: TimelineItem[];
  children: Record<string, TimelineItem[]>;
}

/** Split items into top-level rows and sub-agent children keyed by parent block. */
export function selectView(state: TimelineState): TimelineView {
  const roots: TimelineItem[] = [];
  const children: Record<string, TimelineItem[]> = {};
  for (const key of state.order) {
    const item = state.items[key];
    if (!item) continue;
    const parent = item.parentBlockId;
    if (parent && state.items[parent]) {
      (children[parent] ??= []).push(item);
    } else {
      roots.push(item);
    }
  }
  return { roots, children };
}
