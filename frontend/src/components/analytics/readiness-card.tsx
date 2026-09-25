"use client";

import { useEffect, useState } from "react";
import { Card, Spinner, cn } from "@/components/ui";
import { api } from "@/lib/api/client";
import type { ConfidenceBandKey, Readiness, ReadinessComponentKey } from "@/lib/api/types";

/**
 * Readiness, with the reason it says what it says.
 *
 * <p>The number is the least useful thing here. What a learner can act on is which of the six
 * components is weakest and what to do about it — so the breakdown is the body of the card, not
 * something hidden behind a disclosure, and the recommended action is given as much weight as the
 * score itself.
 */
export function ReadinessCard({ spaceId }: { spaceId: string }) {
  const [readiness, setReadiness] = useState<Readiness | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;

    (async () => {
      try {
        const loaded = await api<Readiness>(`/api/v1/spaces/${spaceId}/readiness`);
        if (!cancelled) setReadiness(loaded);
      } catch {
        // A missing readiness card is better than an error banner over the whole page.
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [spaceId]);

  if (loading) {
    return (
      <Card className="grid place-items-center p-8">
        <Spinner className="size-5 text-text-faint" />
      </Card>
    );
  }

  if (!readiness) return null;

  const calibrating = readiness.confidenceBand === "CALIBRATING";
  const totalDelta = readiness.deltas.total;

  return (
    <Card className="flex flex-col gap-5 p-5">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="flex items-baseline gap-3">
          <span
            className={cn(
              "font-mono text-4xl font-semibold tabular-nums",
              calibrating ? "text-text-faint" : scoreTone(readiness.score),
            )}
          >
            {calibrating ? "—" : `${Math.round(readiness.score)}%`}
          </span>
          <div className="flex flex-col">
            <span className="text-[13px] font-medium text-text">Readiness</span>
            {/* Never just the number. A figure that cannot say how much to trust it is a
                figure people over-read in both directions. */}
            <span className="text-xs text-text-muted">{readiness.headline}</span>
          </div>
        </div>

        <div className="flex flex-col items-end gap-1">
          {typeof totalDelta === "number" && Math.abs(totalDelta) >= 0.1 && (
            <span
              className={cn(
                "font-mono text-xs tabular-nums",
                totalDelta > 0 ? "text-success" : "text-danger",
              )}
            >
              {totalDelta > 0 ? "+" : ""}
              {totalDelta.toFixed(1)} since last
            </span>
          )}
          <ConfidenceChip band={readiness.confidenceBand} />
          {readiness.score < readiness.raw && (
            <span className="text-[11px] text-text-faint">
              {Math.round(readiness.raw)}% on the evidence so far
            </span>
          )}
        </div>
      </div>

      {/* The biggest lever — the single most useful output of the whole module. Given the same
          visual weight as the score, because it is the part you act on. */}
      {readiness.biggestLever && (
        <div className="flex flex-col gap-1 rounded-lg border border-accent/40 bg-accent-soft/40 p-3">
          <span className="font-mono text-[10px] tracking-wider text-accent uppercase">
            Do this next
          </span>
          <p className="text-[13px] text-text">{readiness.biggestLever.action}</p>
          {readiness.biggestLever.estimatedGain >= 0.5 && (
            <p className="text-[11px] text-text-muted">
              Worth roughly {readiness.biggestLever.estimatedGain.toFixed(1)} points.
            </p>
          )}
        </div>
      )}

      <section className="flex flex-col gap-2">
        <h3 className="font-mono text-[10px] tracking-wider text-text-faint uppercase">
          What it is made of
        </h3>
        <ul className="flex flex-col gap-1.5">
          {COMPONENTS.map(({ key, label, asks }) => (
            <li key={key}>
              <ComponentBar
                label={label}
                asks={asks}
                value={readiness.components[key] ?? 0}
                weight={readiness.weights[key] ?? 0}
                delta={readiness.deltas[key]}
              />
            </li>
          ))}
        </ul>
      </section>

      {readiness.drivers.length > 0 && (
        <section className="flex flex-col gap-1">
          <h3 className="font-mono text-[10px] tracking-wider text-text-faint uppercase">
            What changed
          </h3>
          <ul className="flex flex-wrap gap-x-3 gap-y-1 text-xs text-text-muted">
            {readiness.drivers.map((driver) => (
              <li key={driver}>{driver}</li>
            ))}
          </ul>
        </section>
      )}

      <div className="flex flex-wrap items-center justify-between gap-2 border-t border-border pt-3">
        <span className="text-xs text-text-muted">
          {readiness.streak.current > 0 ? (
            <>
              <span className="font-mono tabular-nums text-text">
                {readiness.streak.current}
              </span>
              {readiness.streak.current === 1 ? " day" : " day"} streak
              {readiness.streak.longest > readiness.streak.current && (
                <span className="text-text-faint">
                  {" "}
                  · best {readiness.streak.longest}
                </span>
              )}
            </>
          ) : (
            "No streak yet — answer something today to start one"
          )}
        </span>
        <span className="font-mono text-[10px] text-text-faint">{readiness.modelVersion}</span>
      </div>
    </Card>
  );
}

/** One component, its weight, and which question it answers. */
function ComponentBar({
  label,
  asks,
  value,
  weight,
  delta,
}: {
  label: string;
  asks: string;
  value: number;
  weight: number;
  delta: number | undefined;
}) {
  return (
    <div className="flex items-center gap-3">
      <span className="w-24 shrink-0 text-[13px] text-text" title={asks}>
        {label}
      </span>
      <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-surface-2">
        <div
          className={cn("h-full rounded-full", barTone(value))}
          style={{ width: `${Math.round(Math.max(0, Math.min(100, value)))}%` }}
        />
      </div>
      <span className="w-8 shrink-0 text-right font-mono text-[11px] tabular-nums text-text-muted">
        {Math.round(value)}
      </span>
      {/* The weight is shown because a component at 40% that counts for a tenth matters less
          than one at 60% that counts for a quarter. */}
      <span className="w-8 shrink-0 text-right font-mono text-[10px] tabular-nums text-text-faint">
        ×{weight.toFixed(2)}
      </span>
      <span
        className={cn(
          "w-9 shrink-0 text-right font-mono text-[10px] tabular-nums",
          typeof delta !== "number" || Math.abs(delta) < 0.1
            ? "text-transparent"
            : delta > 0
              ? "text-success"
              : "text-danger",
        )}
      >
        {typeof delta === "number" && Math.abs(delta) >= 0.1
          ? `${delta > 0 ? "+" : ""}${delta.toFixed(1)}`
          : "—"}
      </span>
    </div>
  );
}

function ConfidenceChip({ band }: { band: ConfidenceBandKey }) {
  const tone: Record<ConfidenceBandKey, string> = {
    CALIBRATING: "border-border bg-surface-2 text-text-faint",
    LOW: "border-danger/30 bg-danger-soft text-danger",
    MODERATE: "border-flag/30 bg-flag-soft text-flag",
    HIGH: "border-success/30 bg-success-soft text-success",
  };

  return (
    <span
      className={cn(
        "rounded border px-1.5 py-0.5 font-mono text-[10px] tracking-wide uppercase",
        tone[band],
      )}
    >
      confidence {band.toLowerCase()}
    </span>
  );
}

function scoreTone(score: number): string {
  if (score >= 75) return "text-success";
  if (score >= 50) return "text-text";
  return "text-danger";
}

function barTone(value: number): string {
  if (value >= 75) return "bg-success";
  if (value >= 45) return "bg-accent";
  return "bg-danger";
}

/**
 * The six, with the question each one answers.
 *
 * <p>Naming the question matters more than naming the component: "Retention" means nothing on its
 * own, and "will I still know it on the day?" is immediately actionable.
 */
const COMPONENTS: { key: ReadinessComponentKey; label: string; asks: string }[] = [
  { key: "coverage", label: "Coverage", asks: "Have you seen the syllabus?" },
  { key: "accuracy", label: "Accuracy", asks: "Are you getting them right?" },
  { key: "depth", label: "Depth", asks: "At the level the real thing demands?" },
  { key: "retention", label: "Retention", asks: "Will you still know it on the day?" },
  { key: "consistency", label: "Consistency", asks: "Are you actually studying?" },
  { key: "mock", label: "Mock", asks: "Does it hold up under exam conditions?" },
];
