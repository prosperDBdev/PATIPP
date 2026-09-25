"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { Button, Card, cn } from "@/components/ui";
import { api, ApiError } from "@/lib/api/client";
import type { ReviewDebt, SessionResponse } from "@/lib/api/types";

/**
 * Review debt, and a way to clear it.
 *
 * <p>The one number worth putting in front of a learner daily. Retention debt is knowledge
 * already paid for and about to be lost, which makes it the most time-critical thing twenty
 * minutes could go on — more urgent than new material and more urgent than a weak topic.
 */
export function ReviewPanel({ spaceId }: { spaceId: string }) {
  const router = useRouter();

  const [debt, setDebt] = useState<ReviewDebt | null>(null);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    (async () => {
      try {
        const loaded = await api<ReviewDebt>(`/api/v1/spaces/${spaceId}/review-debt`);
        if (!cancelled) setDebt(loaded);
      } catch {
        // A missing panel is better than an error banner on the space overview.
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [spaceId]);

  if (!debt) return null;

  async function startReview() {
    setStarting(true);
    setError(null);
    try {
      const session = await api<SessionResponse>(`/api/v1/spaces/${spaceId}/sessions`, {
        method: "POST",
        body: { mode: "FLASHCARD_REVIEW" },
      });
      router.push(`/spaces/${spaceId}/practice/${session.id}`);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "Could not start a review.");
      setStarting(false);
    }
  }

  const urgent = debt.due > 0;

  return (
    <Card
      className={cn(
        "flex flex-wrap items-center gap-3 p-4",
        urgent && "border-flag/40 bg-flag-soft/30",
      )}
    >
      <div className="min-w-0 flex-1">
        <p className="flex items-baseline gap-2 text-sm font-medium text-text">
          {urgent && (
            <span className="font-mono text-base tabular-nums text-flag">{debt.due}</span>
          )}
          Review
        </p>
        <p className="text-xs text-text-muted">{debt.summary}</p>
        {error && <p className="mt-1 text-xs text-danger">{error}</p>}
      </div>

      {debt.tracked > 0 && (
        <span className="font-mono text-[11px] text-text-faint tabular-nums">
          {debt.tracked} tracked
        </span>
      )}

      <Button onClick={startReview} loading={starting} variant={urgent ? "primary" : "secondary"}>
        {urgent ? "Clear the backlog" : "Review anyway"}
      </Button>
    </Card>
  );
}
