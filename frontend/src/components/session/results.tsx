"use client";

import { Badge, Card, cn } from "@/components/ui";
import { humanType, title } from "@/components/question/labels";
import type { ReviewItem, SessionSummary } from "@/lib/api/types";

/**
 * The results of a finished session.
 *
 * <p>Shared by practice and exam rather than duplicated, because what you want to see after a
 * session — the score, where you lost marks, and the explanation for every question — does not
 * depend on which mode produced it. The pages differ only in their heading and where they send
 * you next, so that is all they pass in.
 */
export function SessionResults({ summary }: { summary: SessionSummary }) {
  const minutes = Math.max(1, Math.round(summary.activeMs / 60000));
  const byDifficulty = (summary.breakdown?.byDifficulty ?? {}) as Record<
    string,
    { answered: number; correct: number; percent: number }
  >;
  const bySubject = (summary.breakdown?.bySubject ?? {}) as Record<
    string,
    { answered: number; correct: number; percent: number }
  >;

  return (
    <>
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

      <Breakdown title="By difficulty" rows={byDifficulty} humanise={title} />
      <Breakdown title="By subject" rows={bySubject} />

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
    </>
  );
}

function Breakdown({
  title: heading,
  rows,
  humanise,
}: {
  title: string;
  rows: Record<string, { answered: number; correct: number; percent: number }>;
  humanise?: (value: string) => string;
}) {
  const entries = Object.entries(rows);
  if (entries.length === 0) return null;

  return (
    <section className="flex flex-col gap-2">
      <h2 className="text-base font-semibold text-text">{heading}</h2>
      <Card className="divide-y divide-[var(--border)]">
        {entries.map(([key, counts]) => (
          <div key={key} className="flex items-center gap-3 px-4 py-2.5">
            <span className="w-28 truncate text-[13px] text-text" title={key}>
              {humanise ? humanise(key) : key}
            </span>
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
  );
}

function ReviewCard({ item }: { item: ReviewItem }) {
  const unanswered = item.yourAnswer === null;

  return (
    <Card
      className={cn(
        "flex flex-col gap-2 p-4",
        unanswered ? "border-border" : item.correct ? "border-success/40" : "border-danger/40",
      )}
    >
      <div className="flex flex-wrap items-center gap-1.5">
        <span className="font-mono text-[11px] text-text-faint tabular-nums">
          Q{item.position + 1}
        </span>
        <span
          className={cn(
            "font-mono text-[11px] font-bold tracking-wide",
            unanswered ? "text-text-faint" : item.correct ? "text-success" : "text-danger",
          )}
        >
          {unanswered ? "NOT ANSWERED" : item.correct ? "CORRECT" : "INCORRECT"}
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

      {item.yourAnswer && !item.correct && (
        <p className="text-[13px] text-text-muted">
          <span className="font-medium text-text">You said: </span>
          {formatAnswer(item.yourAnswer)}
        </p>
      )}

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

/** Renders an answer readably, whatever shape its format uses. */
export function formatAnswer(answer: Record<string, unknown>): string {
  if (Array.isArray(answer.optionIds)) {
    return (answer.optionIds as string[]).join(", ");
  }
  if (typeof answer.value === "boolean") {
    return answer.value ? "True" : "False";
  }
  if (typeof answer.text === "string") {
    return answer.text;
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
  if (typeof answer.grade === "string") {
    return answer.grade;
  }
  return JSON.stringify(answer);
}

function Stat({ label, value, tone }: { label: string; value: string; tone?: "good" }) {
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
