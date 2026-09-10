/** Display formatters. Pure functions so they are cheap to unit test. */

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/** Compact relative time as used in session rows: "4m", "3h", "2d", "just now". */
export function relativeTime(ts: number, now = Date.now()): string {
  const delta = Math.max(0, now - ts);
  if (delta < 45_000) return 'now';
  if (delta < HOUR) return `${Math.round(delta / MINUTE)}m`;
  if (delta < DAY) return `${Math.round(delta / HOUR)}h`;
  if (delta < 7 * DAY) return `${Math.round(delta / DAY)}d`;
  return new Date(ts).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

/** Longer relative form used for "recent directories": "2h ago", "yesterday". */
export function relativeAgo(ts: number, now = Date.now()): string {
  const delta = Math.max(0, now - ts);
  if (delta < MINUTE) return 'just now';
  if (delta < HOUR) return `${Math.round(delta / MINUTE)}m ago`;
  if (delta < DAY) return `${Math.round(delta / HOUR)}h ago`;
  if (delta < 2 * DAY) return 'yesterday';
  if (delta < 7 * DAY) return `${Math.round(delta / DAY)}d ago`;
  return new Date(ts).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

/** "6.4s", "1m 12s", "820ms" — durations inside tool rows and turn timers. */
export function duration(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return '';
  if (ms < 1000) return `${Math.round(ms)}ms`;
  const seconds = ms / 1000;
  if (seconds < 60) return `${seconds < 10 ? seconds.toFixed(1) : Math.round(seconds)}s`;
  const m = Math.floor(seconds / 60);
  const s = Math.round(seconds % 60);
  if (m < 60) return `${m}m ${s}s`;
  const h = Math.floor(m / 60);
  return `${h}h ${m % 60}m`;
}

/** "0:12" / "9:47" clock used by pairing countdowns and the voice timer. */
export function clock(ms: number): string {
  const total = Math.max(0, Math.round(ms / 1000));
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
}

/** "48.2k" token counts. */
export function compactNumber(n: number): string {
  if (!Number.isFinite(n)) return '0';
  if (n < 1000) return String(Math.round(n));
  if (n < 1_000_000) {
    const k = n / 1000;
    return `${k < 100 ? k.toFixed(1) : Math.round(k)}k`;
  }
  return `${(n / 1_000_000).toFixed(1)}M`;
}

export function bytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KiB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MiB`;
}

/** Collapse the home directory to `~` the way the prototype shows paths. */
export function tildePath(path: string, home?: string | null): string {
  if (home && path.startsWith(home)) return `~${path.slice(home.length)}`;
  const unixHome = /^\/(?:home|Users)\/[^/]+/.exec(path);
  if (unixHome) return `~${path.slice(unixHome[0].length)}`;
  return path;
}

/** Last path segment, used as the short cwd label in the sidebar. */
export function baseName(path: string): string {
  const trimmed = path.replace(/(.)\/+$/, '$1');
  const idx = trimmed.lastIndexOf('/');
  if (idx === -1) return trimmed;
  return trimmed.slice(idx + 1) || '/';
}

export function latency(ms: number | null): string {
  return ms === null || !Number.isFinite(ms) ? '—' : `${Math.round(ms)} ms`;
}

/** Truncate to a line budget, returning the head and whether it was folded. */
export function foldLines(text: string, maxLines: number): { head: string; folded: boolean; total: number } {
  const lines = text.split('\n');
  if (lines.length <= maxLines) return { head: text, folded: false, total: lines.length };
  return { head: lines.slice(0, maxLines).join('\n'), folded: true, total: lines.length };
}
