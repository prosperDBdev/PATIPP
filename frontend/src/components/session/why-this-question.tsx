"use client";

import { useState } from "react";
import { cn } from "@/components/ui";
import type { SelectionReason } from "@/lib/api/types";

/**
 * Why the engine served this question.
 *
 * <p>Collapsed by default — mid-session you want the question, not a lecture about the
 * algorithm. But it is always one click away, and that matters more than it looks: an
 * adaptive system that cannot account for its own choices is one you eventually stop
 * believing, and a recommendation engine nobody believes is dead weight.
 */
export function WhyThisQuestion({ reason }: { reason: SelectionReason | null | undefined }) {
  const [open, setOpen] = useState(false);

  if (!reason?.reason) return null;

  const label = LABELS[reason.reason] ?? "Why this one?";
  const components = reason.components;

  return (
    <div className="flex flex-col gap-2">
      <button
        type="button"
        onClick={() => setOpen((current) => !current)}
        aria-expanded={open}
        className="flex w-fit items-center gap-1.5 text-xs text-text-faint transition-colors hover:text-text-muted"
      >
        <span
          className={cn(
            "inline-block size-1.5 rounded-full",
            TONE[reason.reason] ?? "bg-text-faint",
          )}
        />
        {label}
        <span className="font-mono">{open ? "−" : "+"}</span>
      </button>

      {open && (
        <div className="flex flex-col gap-2 rounded-md border border-border bg-surface-2 p-3">
          {reason.why && <p className="text-[13px] text-text">{reason.why}</p>}

          {typeof reason.expectation === "number" && (
            <p className="text-xs text-text-muted">
              The engine put your chances at{" "}
              <span className="font-mono tabular-nums">
                {Math.round(reason.expectation * 100)}%
              </span>
              . It aims for about 78% — high enough to be worth doing, low enough to be
              worth learning from.
            </p>
          )}

          {components && (
            <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-[11px] sm:grid-cols-3">
              <Component label="Weak area" value={components.weakness} />
              <Component label="Right level" value={components.difficultyFit} />
              <Component label="Not covered" value={components.coverageGap} />
              <Component label="Due review" value={components.due} />
              <Component label="Not seen lately" value={components.freshness} />
            </dl>
          )}

          {reason.engine && (
            <p className="font-mono text-[10px] text-text-faint">{reason.engine}</p>
          )}
        </div>
      )}
    </div>
  );
}

/** One scored component, as a bar rather than a bare decimal nobody can read. */
function Component({ label, value }: { label: string; value: number | undefined }) {
  if (typeof value !== "number") return null;

  return (
    <div className="flex flex-col gap-0.5">
      <div className="flex items-baseline justify-between gap-2">
        <dt className="text-text-muted">{label}</dt>
        <dd className="font-mono tabular-nums text-text-faint">
          {Math.round(value * 100)}
        </dd>
      </div>
      <div className="h-0.5 overflow-hidden rounded-full bg-border">
        <div
          className="h-full rounded-full bg-accent"
          style={{ width: `${Math.round(Math.min(1, Math.max(0, value)) * 100)}%` }}
        />
      </div>
    </div>
  );
}

const LABELS: Record<string, string> = {
  WEAK_TOPIC: "Chosen because this is a weak area",
  COVERAGE_GAP: "Chosen because you have not covered this",
  DIFFICULTY_FIT: "Chosen because it is about your level",
  RECOVERY: "Chosen to get you back on track",
  DUE_REVIEW: "Chosen because it is due for review",
  CALIBRATION: "Still working out where you stand",
  RANDOM: "Drawn at random",
  BLUEPRINT_WEIGHTED: "Weighted like the real paper",
};

const TONE: Record<string, string> = {
  WEAK_TOPIC: "bg-danger",
  COVERAGE_GAP: "bg-flag",
  DIFFICULTY_FIT: "bg-accent",
  RECOVERY: "bg-success",
  DUE_REVIEW: "bg-flag",
  CALIBRATION: "bg-text-faint",
};
