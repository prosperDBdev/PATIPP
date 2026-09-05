"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Card, Spinner, cn } from "@/components/ui";
import { api } from "@/lib/api/client";
import type { Focus, MasteryLevelKey, Weakness } from "@/lib/api/types";

/**
 * What to work on next.
 *
 * <p>Two lists, never merged. "You are weak here" and "you have not tested yourself here"
 * are different instructions, and a ranking that mixes them puts a topic with two attempts
 * beside one with sixty. The second time that recommendation is obviously wrong, the learner
 * stops reading the first list too.
 */
export function FocusPanel({ spaceId }: { spaceId: string }) {
  const [focus, setFocus] = useState<Focus | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;

    (async () => {
      try {
        const loaded = await api<Focus>(`/api/v1/spaces/${spaceId}/focus`);
        if (!cancelled) setFocus(loaded);
      } catch {
        // A missing focus panel is not worth an error banner on the space page.
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
      <Card className="grid place-items-center p-6">
        <Spinner className="size-4 text-text-faint" />
      </Card>
    );
  }

  if (!focus || (focus.weakest.length === 0 && focus.needsAssessment.length === 0)) {
    return (
      <Card className="p-4">
        <p className="text-sm text-text-muted">
          Answer some questions and this will show you where to focus. Nothing is measured
          yet, and guessing would be worse than saying so.
        </p>
      </Card>
    );
  }

  return (
    <Card className="flex flex-col gap-4 p-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <span className="text-[13px] font-medium text-text">Where to focus</span>
        <span className="font-mono text-[11px] text-text-faint tabular-nums">
          {focus.totalAttempts} answered · ability {focus.ability}
        </span>
      </div>

      {/* Said out loud rather than hidden behind a spinner. An engine that admits when it
          does not know yet is one you can still trust when it does. */}
      {focus.note && (
        <p className="rounded-md border border-border bg-surface-2 px-3 py-2 text-xs text-text-muted">
          {focus.note}
        </p>
      )}

      {focus.weakest.length > 0 && (
        <section className="flex flex-col gap-1.5">
          <h3 className="font-mono text-[10px] tracking-wider text-text-faint uppercase">
            Weakest first
          </h3>
          <ul className="flex flex-col gap-1.5">
            {focus.weakest.slice(0, 5).map((entry) => (
              <li key={`${entry.subjectId}-${entry.topicId ?? "untagged"}`}>
                <WeaknessRow entry={entry} />
              </li>
            ))}
          </ul>
        </section>
      )}

      {focus.needsAssessment.length > 0 && (
        <section className="flex flex-col gap-1.5">
          <h3 className="font-mono text-[10px] tracking-wider text-text-faint uppercase">
            Needs assessment
          </h3>
          <p className="text-xs text-text-muted">
            Too few answers here to call it either way.
          </p>
          <ul className="flex flex-wrap gap-1.5">
            {focus.needsAssessment.slice(0, 6).map((entry) => (
              <li
                key={`${entry.subjectId}-${entry.topicId ?? "untagged"}`}
                className="rounded-full border border-border bg-surface px-2.5 py-1 text-xs text-text-muted"
              >
                {name(entry)}{" "}
                <span className="font-mono text-text-faint tabular-nums">
                  {entry.attempts}
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}

      <Link
        href={`/spaces/${spaceId}/practice`}
        className="text-xs text-accent hover:underline"
      >
        Practise this &rarr;
      </Link>
    </Card>
  );
}

function WeaknessRow({ entry }: { entry: Weakness }) {
  return (
    <div className="flex items-center gap-3">
      <span className="w-28 truncate text-[13px] text-text" title={name(entry)}>
        {name(entry)}
      </span>
      <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-surface-2">
        <div
          className={cn("h-full rounded-full", LEVEL_BAR[entry.level])}
          style={{ width: `${entry.recentAccuracy}%` }}
        />
      </div>
      <span className="w-16 text-right font-mono text-[11px] text-text-muted tabular-nums">
        {entry.recentAccuracy}%
      </span>
    </div>
  );
}

function name(entry: Weakness): string {
  return entry.topicName ?? entry.subjectName;
}

const LEVEL_BAR: Record<MasteryLevelKey, string> = {
  UNTOUCHED: "bg-border-strong",
  UNASSESSED: "bg-border-strong",
  WEAK: "bg-danger",
  DEVELOPING: "bg-flag",
  PROFICIENT: "bg-accent",
  STRONG: "bg-success",
};
