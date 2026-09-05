"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { Alert, Card, Spinner } from "@/components/ui";
import { SessionResults } from "@/components/session/results";
import { api, ApiError } from "@/lib/api/client";
import type { SessionSummary } from "@/lib/api/types";

/**
 * The exam paper, marked.
 *
 * <p>Everything held back during the exam arrives here at once: the score, where the marks
 * went by subject and difficulty, and the explanation for every question including the ones
 * never reached. Withholding it during the paper and then not showing it afterwards would be
 * the worst of both.
 */
export default function ExamResultsPage() {
  const { spaceId, sessionId } = useParams<{ spaceId: string; sessionId: string }>();

  const [summary, setSummary] = useState<SessionSummary | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    (async () => {
      try {
        // POST: arriving here is what submits the paper. Safe to call again, and a paper the
        // deadline already ended comes back as EXPIRED rather than being re-marked.
        const loaded = await api<SessionSummary>(
          `/api/v1/spaces/${spaceId}/sessions/${sessionId}/complete`,
          { method: "POST" },
        );
        if (!cancelled) setSummary(loaded);
      } catch (caught) {
        if (!cancelled) {
          setError(
            caught instanceof ApiError && caught.status === 404
              ? "That exam does not exist, or is not yours."
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
        <Link href={`/spaces/${spaceId}/exam`} className="text-sm text-accent hover:underline">
          &larr; Back to exam setup
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

  const unanswered = summary.totalItems - summary.answeredCount;

  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-6">
      <header className="flex flex-col gap-1.5">
        <Link href={`/spaces/${spaceId}`} className="text-sm text-text-muted hover:text-text">
          &larr; Back to space
        </Link>
        <h1 className="text-2xl font-semibold tracking-tight text-text">Exam results</h1>
        <p className="text-sm text-text-muted">
          {summary.status === "EXPIRED"
            ? "Time ran out, so the paper was submitted as it stood."
            : "Submitted."}{" "}
          {summary.answeredCount} of {summary.totalItems} answered.
        </p>
      </header>

      {unanswered > 0 && (
        <Card className="p-4 text-[13px] text-text-muted">
          The score is out of what you answered, so {unanswered} unanswered question
          {unanswered === 1 ? "" : "s"} did not count against you. In the real paper
          {unanswered === 1 ? " it" : " they"} would have — running out of time is itself
          something to work on.
        </Card>
      )}

      <SessionResults summary={summary} />

      <div className="flex flex-wrap gap-3">
        <Link
          href={`/spaces/${spaceId}/exam`}
          className="inline-flex h-10 items-center rounded-md bg-accent px-4 text-sm font-medium text-accent-fg hover:bg-accent-hover"
        >
          Sit another
        </Link>
        <Link
          href={`/spaces/${spaceId}/practice`}
          className="inline-flex h-10 items-center rounded-md border border-border bg-surface px-4 text-sm font-medium text-text hover:border-border-strong"
        >
          Practise what you missed
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
