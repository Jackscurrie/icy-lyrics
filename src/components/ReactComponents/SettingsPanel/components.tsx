import React, { useCallback, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";

export function matches(query: string, label: string, description?: string): boolean {
  if (!query.trim()) return true;
  const q = query.toLowerCase();
  return label.toLowerCase().includes(q) || (description ?? "").toLowerCase().includes(q);
}

export function Row({
  label,
  description,
  children,
  disabled,
  disabledReason,
  stacked,
}: {
  label: string;
  description?: string;
  children: React.ReactNode;
  disabled?: boolean;
  disabledReason?: string;
  stacked?: boolean;
}) {
  return (
    <div
      className={`il-sp-row il-list-row${disabled ? " il-sp-row--disabled" : ""}${stacked ? " il-sp-row--stacked" : ""}`}
    >
      <div className="il-sp-label-wrap">
        <span className="il-sp-label">{label}</span>
        {description && <span className="il-sp-description">{description}</span>}
      </div>
      <div className="il-sp-control">{children}</div>
      {disabled && disabledReason && <div className="il-sp-row-tooltip">{disabledReason}</div>}
    </div>
  );
}

export function Toggle({
  checked,
  onChange,
}: {
  checked: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <label className="il-sp-toggle">
      <input
        type="checkbox"
        checked={checked}
        onChange={(e) => onChange(e.currentTarget.checked)}
      />
      <span className="il-sp-toggle-track" />
    </label>
  );
}

export function Select({
  value,
  options,
  labels,
  onChange,
  disabled,
}: {
  value: string;
  options: string[];
  labels?: string[];
  onChange: (v: string) => void;
  disabled?: boolean;
}) {
  return (
    <select
      className="il-sp-select"
      value={value}
      onChange={(e) => onChange(e.currentTarget.value)}
      disabled={disabled}
    >
      {options.map((opt, i) => (
        <option key={opt} value={opt}>
          {labels?.[i] ?? opt}
        </option>
      ))}
    </select>
  );
}

/**
 * Bipolar position slider — a snapping range control centred on a neutral
 * point with a negative axis to the left and a positive axis to the right.
 * The filled segment grows outward from the centre toward the thumb, so the
 * sign of the value is conveyed by the direction of the fill. Pass a
 * `defaultValue` to surface an inline Reset affordance.
 */
export function Slider({
  value,
  min,
  max,
  step = 1,
  defaultValue,
  unit,
  onChange,
  disabled,
}: {
  value: number;
  min: number;
  max: number;
  step?: number;
  defaultValue?: number;
  unit?: string;
  onChange: (v: number) => void;
  disabled?: boolean;
}) {
  // Keep in sync with the thumb width in settings-panel.css.
  const THUMB = 16;
  const range = max - min || 1;
  const clamped = Math.min(max, Math.max(min, value));
  const frac = (clamped - min) / range;
  const isBipolar = min < 0 && max > 0;
  const zeroFrac = ((isBipolar ? 0 : min) - min) / range;

  // Position of a fraction along the track, compensating for the thumb width
  // so the fill and centre tick line up with the native thumb at both ends.
  const posFor = (f: number) =>
    `calc(${(f * 100).toFixed(4)}% + ${((0.5 - f) * THUMB).toFixed(3)}px)`;

  const fillFrom = Math.min(zeroFrac, frac);
  const fillSpan = Math.abs(frac - zeroFrac);

  const sign = isBipolar && clamped > 0 ? "+" : "";
  const valueLabel = `${sign}${clamped}${unit ? ` ${unit}` : ""}`;
  const changed = defaultValue !== undefined && clamped !== defaultValue;

  return (
    <div className={`il-sp-slider${disabled ? " il-sp-slider--disabled" : ""}`}>
      <div className="il-sp-slider-track-wrap">
        <span className="il-sp-slider-track" />
        <span
          className="il-sp-slider-fill"
          style={{
            left: posFor(fillFrom),
            width: `calc(${(fillSpan * 100).toFixed(4)}% - ${(fillSpan * THUMB).toFixed(3)}px)`,
          }}
        />
        {isBipolar && <span className="il-sp-slider-center" style={{ left: posFor(zeroFrac) }} />}
        <input
          type="range"
          className="il-sp-slider-input"
          min={min}
          max={max}
          step={step}
          value={clamped}
          onChange={(e) => onChange(Number(e.currentTarget.value))}
          disabled={disabled}
        />
      </div>
      <div className="il-sp-slider-meta">
        <span className="il-sp-slider-value">{valueLabel}</span>
        {changed && (
          <button
            type="button"
            className="il-sp-slider-reset"
            onClick={() => onChange(defaultValue!)}
          >
            Reset
          </button>
        )}
      </div>
    </div>
  );
}

export function SectionTitle({ children }: { children: React.ReactNode }) {
  return <p className="il-sp-section-title">{children}</p>;
}

export function SearchBar({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  return (
    <div className="il-sp-search-wrap">
      <svg
        className="il-sp-search-icon"
        width="14"
        height="14"
        viewBox="0 0 14 14"
        fill="none"
        xmlns="http://www.w3.org/2000/svg"
      >
        <circle cx="6" cy="6" r="4.5" stroke="currentColor" strokeWidth="1.5" />
        <line
          x1="9.5"
          y1="9.5"
          x2="13"
          y2="13"
          stroke="currentColor"
          strokeWidth="1.5"
          strokeLinecap="round"
        />
      </svg>
      <input
        className="il-sp-search"
        type="text"
        placeholder="Search settings…"
        value={value}
        onChange={(e) => onChange(e.currentTarget.value)}
        spellCheck={false}
      />
      {value && (
        <button
          className="il-sp-search-clear"
          onClick={() => onChange("")}
          aria-label="Clear search"
        >
          <svg
            width="10"
            height="10"
            viewBox="0 0 10 10"
            fill="none"
            xmlns="http://www.w3.org/2000/svg"
          >
            <line
              x1="1"
              y1="1"
              x2="9"
              y2="9"
              stroke="currentColor"
              strokeWidth="1.5"
              strokeLinecap="round"
            />
            <line
              x1="9"
              y1="1"
              x2="1"
              y2="9"
              stroke="currentColor"
              strokeWidth="1.5"
              strokeLinecap="round"
            />
          </svg>
        </button>
      )}
    </div>
  );
}

export function Tooltip({ text, children }: { text: string; children: React.ReactNode }) {
  return (
    <div className="il-sp-tooltip-wrap">
      {children}
      <div className="il-sp-tooltip-bubble">{text}</div>
    </div>
  );
}

export function FilterDropdown({
  sections,
  value,
  onChange,
}: {
  sections: string[];
  value: string;
  onChange: (v: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [coords, setCoords] = useState<{ top: number; right: number } | null>(null);
  const wrapRef = useRef<HTMLDivElement>(null);
  const btnRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);

  const updateCoords = useCallback(() => {
    const btn = btnRef.current;
    if (!btn) return;
    const rect = btn.getBoundingClientRect();
    setCoords({
      top: rect.bottom + 6,
      right: window.innerWidth - rect.right,
    });
  }, []);

  useEffect(() => {
    if (!open) return;
    updateCoords();
    const handler = (e: MouseEvent) => {
      const target = e.target as Node;
      if (wrapRef.current?.contains(target)) return;
      if (menuRef.current?.contains(target)) return;
      setOpen(false);
    };
    const onReflow = () => updateCoords();
    document.addEventListener("mousedown", handler);
    window.addEventListener("resize", onReflow);
    window.addEventListener("scroll", onReflow, true);
    return () => {
      document.removeEventListener("mousedown", handler);
      window.removeEventListener("resize", onReflow);
      window.removeEventListener("scroll", onReflow, true);
    };
  }, [open, updateCoords]);

  const allOptions = ["All", ...sections];

  const portalTarget =
    open && typeof document !== "undefined"
      ? (document.querySelector(
          "icy-lyrics-modal.IcyLyricsModal .il-modal-overlay"
        ) as HTMLElement | null)
      : null;

  return (
    <div className={`il-sp-filter-wrap${open ? " il-sp-filter-wrap--open" : ""}`} ref={wrapRef}>
      <button
        ref={btnRef}
        className={`il-sp-filter-btn${open ? " open" : ""}${value !== "All" ? " active" : ""}`}
        onClick={() => setOpen((o) => !o)}
        aria-haspopup="listbox"
        aria-expanded={open}
      >
        <svg
          className="il-sp-filter-icon"
          width="13"
          height="12"
          viewBox="0 0 13 12"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
        >
          <path
            d="M1 1h11M3 5h7M5 9h3"
            stroke="currentColor"
            strokeWidth="1.5"
            strokeLinecap="round"
          />
        </svg>
        <span className="il-sp-filter-label">{value === "All" ? "Filter" : value}</span>
        <svg
          className={`il-sp-filter-chevron${open ? " rotated" : ""}`}
          width="10"
          height="10"
          viewBox="0 0 10 10"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
        >
          <path
            d="M2 3.5L5 6.5L8 3.5"
            stroke="currentColor"
            strokeWidth="1.5"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      </button>

      {open &&
        coords &&
        portalTarget &&
        createPortal(
          <div
            ref={menuRef}
            className="il-sp-filter-menu"
            role="listbox"
            style={{ position: "fixed", top: coords.top, right: coords.right }}
            onClick={(e) => e.stopPropagation()}
          >
            {allOptions.map((s) => {
              const isActive = value === s;
              return (
                <button
                  key={s}
                  role="option"
                  aria-selected={isActive}
                  className={`il-sp-filter-item${isActive ? " il-sp-filter-item--active" : ""}`}
                  onClick={() => {
                    onChange(s);
                    setOpen(false);
                  }}
                >
                  <span className="il-sp-filter-item-check">
                    {isActive && (
                      <svg
                        width="10"
                        height="10"
                        viewBox="0 0 10 10"
                        fill="none"
                        xmlns="http://www.w3.org/2000/svg"
                      >
                        <path
                          d="M1.5 5L4 7.5L8.5 2.5"
                          stroke="currentColor"
                          strokeWidth="1.5"
                          strokeLinecap="round"
                          strokeLinejoin="round"
                        />
                      </svg>
                    )}
                  </span>
                  {s}
                </button>
              );
            })}
          </div>,
          portalTarget
        )}
    </div>
  );
}
