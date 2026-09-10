import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { ChevronDown } from 'lucide-react';
import { cx } from '../lib/cx';
import './popover.css';

type Align = 'start' | 'end';
type Side = 'top' | 'bottom';

interface PopoverProps {
  label: ReactNode;
  children: (close: () => void) => ReactNode;
  align?: Align;
  side?: Side;
  className?: string;
  triggerClassName?: string;
  ariaLabel?: string;
  disabled?: boolean;
  chevron?: boolean;
}

export function Popover({
  label,
  children,
  align = 'start',
  side = 'bottom',
  className,
  triggerClassName,
  ariaLabel,
  disabled,
  chevron = true,
}: PopoverProps) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const panelId = useId();

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (!root.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  return (
    <div className={cx('popover-root', className)} ref={root}>
      <button
        type="button"
        className={cx('pill', triggerClassName)}
        aria-expanded={open}
        aria-haspopup="true"
        aria-controls={open ? panelId : undefined}
        aria-label={ariaLabel}
        disabled={disabled}
        onClick={() => setOpen((v) => !v)}
      >
        {label}
        {chevron ? <ChevronDown size={13} aria-hidden className="popover-chevron" /> : null}
      </button>
      {open ? (
        <div className={cx('popover-panel', `align-${align}`, `side-${side}`)} id={panelId}>
          {children(() => setOpen(false))}
        </div>
      ) : null}
    </div>
  );
}

export interface MenuOption {
  id: string;
  label: string;
  description?: string;
  disabled?: boolean;
}

interface MenuProps {
  label: ReactNode;
  options: MenuOption[];
  value: string | null;
  onSelect: (id: string) => void;
  ariaLabel: string;
  align?: Align;
  side?: Side;
  disabled?: boolean;
}

export function Menu({ label, options, value, onSelect, ariaLabel, align, side, disabled }: MenuProps) {
  return (
    <Popover label={label} ariaLabel={ariaLabel} align={align} side={side} disabled={disabled}>
      {(close) => (
        <ul className="menu" role="listbox" aria-label={ariaLabel}>
          {options.map((option) => (
            <li key={option.id}>
              <button
                type="button"
                role="option"
                aria-selected={value === option.id}
                disabled={option.disabled}
                className={cx('menu-item', value === option.id && 'selected')}
                onClick={() => {
                  onSelect(option.id);
                  close();
                }}
              >
                <span className="menu-label">{option.label}</span>
                {option.description ? <span className="menu-desc">{option.description}</span> : null}
              </button>
            </li>
          ))}
        </ul>
      )}
    </Popover>
  );
}
