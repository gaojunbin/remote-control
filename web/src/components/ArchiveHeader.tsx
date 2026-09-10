import { ChevronRight } from 'lucide-react';
import { cx } from '../lib/cx';
import { strings } from '../strings';

interface Props {
  count: number;
  expanded: boolean;
  onToggle: () => void;
  className?: string;
}

/**
 * The one collapsed group at the bottom of every session list. The Sessions
 * page and the chat sidebar share it so the two lists read the same way.
 */
export function ArchiveHeader({ count, expanded, onToggle, className }: Props) {
  return (
    <button
      type="button"
      className={cx('group-title archive-head', className)}
      aria-expanded={expanded}
      onClick={onToggle}
    >
      <ChevronRight size={12} aria-hidden className={cx('archive-chevron', expanded && 'open')} />
      {strings.sessions.archiveGroup(count)}
    </button>
  );
}
