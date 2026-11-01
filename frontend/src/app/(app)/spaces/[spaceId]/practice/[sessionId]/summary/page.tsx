"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { Alert, Spinner } from "@/components/ui";
import { SessionResults } from "@/components/session/results";
import { api, ApiError } from "@/lib/api/client";
import type { SessionSummary } from "@/lib/api/types";

export default function SessionSummaryPage() {
  const { spaceId, sessionId } = useParams<{ spaceId: string; sessionId: string }>();

  const [summary, setSummary] = useState<SessionSummary | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    (async () => {
      try {
        // POST rather than GET: arriving here is what finishes the session, and calling it
        // again is safe.
        const loaded = await api<SessionSummary>(
          `/api/v1/spaces/${spaceId}/sessions/${sessionId}/complete`,
          { method: "POST" },
        );
        if (!cancelled) setSummary(loaded);
      } catch (caught) {
        if (!cancelled) {
          setError(
            caught instanceof ApiError && caught.status === 404
              ? "That session does not exist, or is not yours."
              : "Could not load the results.",
          );
        }
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [spaceId, sessionId]);

  if (error) {
    return (
      <div className="flex flex-col gap-4">
        <Alert>{error}</Alert>
        <Link href={`/spaces/${spaceId}/practice`} className="text-sm text-accent hover:underline">
          &larr; Back to practice
        </Link>
      </div>
    );
  }

  if (!summary) {
    return (
      <div className="grid place-items-center py-20">
        <Spinner className="size-5 text-text-faint" />
      </div>
    );
  }

  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-6">
      <header className="flex flex-col gap-1.5">
        <Link href={`/spaces/${spaceId}`} className="text-sm text-text-muted hover:text-text">
          &larr; Back to space
        </Link>
        <h1 className="text-2xl font-semibold tracking-tight text-text">Session results</h1>
        <p className="text-sm text-text-muted">
          {summary.answeredCount} of {summary.totalItems} answered
          {summary.answeredCount < summary.totalItems && (
            <> — the unanswered ones are not counted against you</>
          )}
          .
        </p>
      </header>

      <SessionResults summary={summary} />

      {summary.items.some((item) => item.selectionReason?.reason === "GUIDED_TASK") && <Link
        href={`/spaces/${spaceId}/challenge`} className="text-sm font-medium text-accent hover:underline">
        Continue the 30-day challenge &rarr;
      </Link>}

      <div className="flex flex-wrap gap-3">
        <Link
          href={`/spaces/${spaceId}/practice`}
          className="inline-flex h-10 items-center rounded-md bg-accent px-4 text-sm font-medium text-accent-fg hover:bg-accent-hover"
        >
          Practise again
        </Link>
        <Link
          href={`/spaces/${spaceId}`}
          className="inline-flex h-10 items-center rounded-md border border-border bg-surface px-4 text-sm font-medium text-text hover:border-border-strong"
        >
          Back to space
        </Link>
      </div>
    </div>
  );
}
