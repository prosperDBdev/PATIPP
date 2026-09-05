"use client";

/**
 * The controls that narrow what a session draws on.
 *
 * <p>Shared by practice and exam setup. The filters are a property of the session engine, not
 * of a mode, so the two pages differ in what they do with the selection rather than in how it
 * is collected.
 */

export const QUESTION_TYPES = [
  "MCQ",
  "MULTI_SELECT",
  "TRUE_FALSE",
  "SHORT_ANSWER",
  "FLASHCARD",
] as const;

export const DIFFICULTIES = ["EASY", "MEDIUM", "HARD", "EXPERT"] as const;

export function FilterGroup({
  label,
  hint,
  options,
  selected,
  onChange,
}: {
  label: string;
  hint?: string;
  options: { value: string; label: string }[];
  selected: string[];
  onChange: (next: string[]) => void;
}) {
  if (options.length === 0) return null;

  return (
    <div className="flex flex-col gap-2">
      <span className="text-[13px] font-medium text-text">
        {label}
        {hint && <span className="ml-1.5 font-normal text-text-faint">{hint}</span>}
      </span>
      <div className="flex flex-wrap gap-1.5">
        {options.map((option) => (
          <Chip
            key={option.value}
            label={option.label}
            active={selected.includes(option.value)}
            onClick={() =>
              onChange(
                selected.includes(option.value)
                  ? selected.filter((value) => value !== option.value)
                  : [...selected, option.value],
              )
            }
          />
        ))}
      </div>
    </div>
  );
}

export function Chip({
  label,
  active,
  onClick,
}: {
  label: string;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={`rounded-full border px-2.5 py-1 text-xs transition-colors ${
        active
          ? "border-accent bg-accent text-accent-fg"
          : "border-border bg-surface text-text-muted hover:border-border-strong"
      }`}
    >
      {label}
    </button>
  );
}
