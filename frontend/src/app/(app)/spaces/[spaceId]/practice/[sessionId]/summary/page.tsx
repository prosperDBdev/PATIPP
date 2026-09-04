"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { Alert, Badge, Card, Spinner, cn } from "@/components/ui";
import { humanType, title } from "@/components/question/labels";
import { api, ApiError } from "@/lib/api/client";
import type { ReviewItem, SessionSummary } from "@/lib/api/types";

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

  const minutes = Math.max(1, Math.round(summary.activeMs / 60000));
  const byDifficulty = (summary.breakdown?.byDifficulty ?? {}) as Record<
    string,
    { answered: number; correct: number; percent: number }
  >;

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

      <div className="grid grid-cols-2 gap-px overflow-hidden rounded-lg border border-border bg-border sm:grid-cols-4">
        <Stat
          label="Score"
          value={summary.score === null ? "—" : `${Math.round(summary.score)}%`}
          tone={summary.score !== null && summary.score >= 70 ? "good" : undefined}
        />
        <Stat label="Correct" value={`${summary.correctCount}/${summary.answeredCount}`} />
        <Stat label="Time" value={`${minutes} min`} />
        <Stat label="Questions" value={String(summary.totalItems)} />
      </div>

      {Object.keys(byDifficulty).length > 0 && (
        <section className="flex flex-col gap-2">
          <h2 className="text-base font-semibold text-text">By difficulty</h2>
          <Card className="divide-y divide-[var(--border)]">
            {Object.entries(byDifficulty).map(([level, counts]) => (
              <div key={level} className="flex items-center gap-3 px-4 py-2.5">
                <span className="w-20 text-[13px] text-text">{title(level)}</span>
                <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-surface-2">
                  <div
                    className="h-full rounded-full bg-accent"
                    style={{ width: `${counts.percent}%` }}
                  />
                </div>
                <span className="w-20 text-right font-mono text-xs text-text-muted tabular-nums">
                  {counts.correct}/{counts.answered}
                </span>
              </div>
            ))}
          </Card>
        </section>
      )}

      <section className="flex flex-col gap-2">
        <h2 className="text-base font-semibold text-text">Review</h2>
        <p className="text-sm text-text-muted">
          Everything is revealed here, including questions you did not reach. This is where
          the learning actually happens.
        </p>
        <ul className="flex flex-col gap-3">
          {summary.items.map((item) => (
            <li key={item.questionId}>
              <ReviewCard item={item} />
            </li>
          ))}
        </ul>
      </section>

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

function ReviewCard({ item }: { item: ReviewItem }) {
  const unanswered = item.yourAnswer === null;

  return (
    <Card
      className={cn(
        "flex flex-col gap-2 p-4",
        unanswered
          ? "border-border"
          : item.correct
            ? "border-success/40"
            : "border-danger/40",
      )}
    >
      <div className="flex flex-wrap items-center gap-1.5">
        <span
          className={cn(
            "font-mono text-[11px] font-bold tracking-wide",
            unanswered ? "text-text-faint" : item.correct ? "text-success" : "text-danger",
          )}
        >
          {unanswered ? "NOT REACHED" : item.correct ? "CORRECT" : "INCORRECT"}
        </span>
        <Badge>{humanType(item.type)}</Badge>
        <Badge>{title(item.difficulty)}</Badge>
        {item.timeSpentMs > 0 && (
          <span className="font-mono text-[11px] text-text-faint tabular-nums">
            {(item.timeSpentMs / 1000).toFixed(1)}s
          </span>
        )}
      </div>

      <p className="text-sm whitespace-pre-wrap text-text">{item.stem}</p>

      {item.correctAnswer && (
        <p className="text-[13px] text-text-muted">
          <span className="font-medium text-text">Answer: </span>
          {formatAnswer(item.correctAnswer)}
        </p>
      )}

      {item.explanation && (
        <p className="text-[13px] whitespace-pre-wrap text-text-muted">{item.explanation}</p>
      )}
    </Card>
  );
}

/** Renders an answer key readably, whatever shape its format uses. */
function formatAnswer(answer: Record<string, unknown>): string {
  if (Array.isArray(answer.optionIds)) {
    return (answer.optionIds as string[]).join(", ");
  }
  if (typeof answer.value === "boolean") {
    return answer.value ? "True" : "False";
  }
  if (Array.isArray(answer.acceptedAnswers)) {
    return (answer.acceptedAnswers as string[]).join(" / ");
  }
  if (Array.isArray(answer.requiredKeywords)) {
    return `must mention: ${(answer.requiredKeywords as string[]).join(", ")}`;
  }
  if (typeof answer.back === "string") {
    return answer.back;
  }
  return JSON.stringify(answer);
}

function Stat({
  label,
  value,
  tone,
}: {
  label: string;
  value: string;
  tone?: "good";
}) {
  return (
    <div className="bg-surface px-4 py-3">
      <div
        className={cn(
          "text-xl font-semibold tabular-nums",
          tone === "good" ? "text-success" : "text-text",
        )}
      >
        {value}
      </div>
      <div className="mt-0.5 font-mono text-[10px] tracking-wider text-text-faint uppercase">
        {label}
      </div>
    </div>
  );
}
