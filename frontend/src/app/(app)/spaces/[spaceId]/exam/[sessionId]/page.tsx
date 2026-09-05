"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { Alert, Badge, Button, Card, Spinner, cn } from "@/components/ui";
import { AnswerInput } from "@/components/session/answer-input";
import { humanType, title } from "@/components/question/labels";
import { api, ApiError } from "@/lib/api/client";
import type {
  AnswerResult,
  ItemSummary,
  ServedItem,
  SessionResponse,
} from "@/lib/api/types";

/** How often the countdown is re-checked against the server rather than only ticking locally. */
const RESYNC_MS = 30_000;

/**
 * The exam runner.
 *
 * <p>Three things separate it from practice: a clock, a navigation grid, and silence about
 * whether you were right. The clock on screen is drawn from `remainingMs`, which the server
 * computes — the browser only interpolates between re-syncs, so a tampered or merely wrong
 * local clock cannot buy time. When it reaches zero the paper is submitted, and the server
 * would have refused a late answer regardless.
 */
export default function ExamRunnerPage() {
  const { spaceId, sessionId } = useParams<{ spaceId: string; sessionId: string }>();
  const router = useRouter();

  const [session, setSession] = useState<SessionResponse | null>(null);
  const [item, setItem] = useState<ServedItem | null>(null);
  const [answer, setAnswer] = useState<Record<string, unknown> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [remainingMs, setRemainingMs] = useState<number | null>(null);

  // Answers as given, so returning to a flagged question shows what you put down rather than
  // an empty control. The server does not send them back: an exam never echoes answers while
  // it is running, and this is only the local record of what this browser submitted.
  const answersByPosition = useRef<Map<number, Record<string, unknown>>>(new Map());

  // The wall-clock instant this question went on screen, and the local instant the exam ends.
  // Both refs rather than state: they are read when something happens, not rendered.
  const shownAt = useRef<number>(0);
  const endsAt = useRef<number | null>(null);
  const finishing = useRef(false);

  const adoptSession = useCallback((loaded: SessionResponse) => {
    setSession(loaded);
    endsAt.current = loaded.remainingMs === null ? null : Date.now() + loaded.remainingMs;
    setRemainingMs(loaded.remainingMs);
  }, []);

  const goToResults = useCallback(() => {
    if (finishing.current) return;
    finishing.current = true;
    router.replace(`/spaces/${spaceId}/exam/${sessionId}/results`);
  }, [router, spaceId, sessionId]);

  useEffect(() => {
    let cancelled = false;

    (async () => {
      try {
        const loaded = await api<SessionResponse>(
          `/api/v1/spaces/${spaceId}/sessions/${sessionId}`,
        );
        if (cancelled) return;

        if (loaded.mode !== "EXAM") {
          router.replace(`/spaces/${spaceId}/practice/${sessionId}`);
          return;
        }
        if (loaded.status !== "IN_PROGRESS") {
          goToResults();
          return;
        }

        adoptSession(loaded);
        setItem(loaded.currentItem);
        shownAt.current = Date.now();
      } catch (caught) {
        if (!cancelled) {
          setError(
            caught instanceof ApiError && caught.status === 404
              ? "That exam does not exist, or is not yours."
              : "Could not load this exam.",
          );
        }
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [spaceId, sessionId, router, adoptSession, goToResults]);

  // Tick the countdown locally, and re-sync with the server periodically so a drifting or
  // suspended browser clock corrects itself rather than quietly gaining minutes.
  useEffect(() => {
    if (!session || session.status !== "IN_PROGRESS" || endsAt.current === null) return;

    let sinceResync = 0;

    const timer = window.setInterval(() => {
      const left = Math.max(0, (endsAt.current ?? 0) - Date.now());
      setRemainingMs(left);

      if (left === 0) {
        goToResults();
        return;
      }

      sinceResync += 1000;
      if (sinceResync >= RESYNC_MS) {
        sinceResync = 0;
        api<SessionResponse>(`/api/v1/spaces/${spaceId}/sessions/${sessionId}`)
          .then((fresh) => {
            if (fresh.status !== "IN_PROGRESS") {
              goToResults();
              return;
            }
            endsAt.current =
              fresh.remainingMs === null ? null : Date.now() + fresh.remainingMs;
            setSession(fresh);
          })
          .catch(() => {
            // A failed re-sync is not worth interrupting the exam for. The local countdown
            // carries on, and the server still refuses anything that arrives late.
          });
      }
    }, 1000);

    return () => window.clearInterval(timer);
  }, [session, spaceId, sessionId, goToResults]);

  const openPosition = useCallback(
    async (position: number) => {
      if (position === item?.position) return;
      setError(null);
      try {
        const served = await api<ServedItem>(
          `/api/v1/spaces/${spaceId}/sessions/${sessionId}/items/${position}`,
        );
        setItem(served);
        setAnswer(answersByPosition.current.get(position) ?? null);
        shownAt.current = Date.now();
        setSession((current) => current && withItemState(current, position, "VIEWED"));
      } catch (caught) {
        setError(
          caught instanceof ApiError ? caught.message : "Could not open that question.",
        );
      }
    },
    [spaceId, sessionId, item?.position],
  );

  const submit = useCallback(async () => {
    if (!item || !answer || submitting) return;

    setSubmitting(true);
    setError(null);
    try {
      const outcome = await api<AnswerResult>(
        `/api/v1/spaces/${spaceId}/sessions/${sessionId}/answers`,
        {
          method: "POST",
          body: {
            position: item.position,
            answer,
            responseTimeMs: Date.now() - shownAt.current,
          },
        },
      );

      answersByPosition.current.set(item.position, answer);
      setSession((current) =>
        current
          ? {
              ...withItemState(current, item.position, "ANSWERED"),
              answeredCount: outcome.answeredCount,
            }
          : current,
      );

      // Straight on to the next unanswered question, the way a paper is normally worked
      // through. Nothing is revealed: `outcome.correct` is deliberately not shown.
      if (outcome.nextItem) {
        setItem(outcome.nextItem);
        setAnswer(answersByPosition.current.get(outcome.nextItem.position) ?? null);
        shownAt.current = Date.now();
      }
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 409) {
        // Time ran out mid-answer, or the paper was already submitted.
        goToResults();
        return;
      }
      setError(caught instanceof ApiError ? caught.message : "Could not save that answer.");
    } finally {
      setSubmitting(false);
    }
  }, [item, answer, submitting, spaceId, sessionId, goToResults]);

  const toggleMark = useCallback(async () => {
    if (!item || !session) return;
    const current = session.items.find((entry) => entry.position === item.position);
    if (!current || current.answered) return;

    try {
      const updated = await api<SessionResponse>(
        `/api/v1/spaces/${spaceId}/sessions/${sessionId}/items/${item.position}/mark?marked=${
          current.state !== "MARKED_FOR_REVIEW"
        }`,
        { method: "POST" },
      );
      adoptSession(updated);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "Could not flag that question.");
    }
  }, [item, session, spaceId, sessionId, adoptSession]);

  // Enter submits. F flags the current question. Both matter more here than in practice:
  // under time pressure, reaching for the mouse costs seconds you are being marked on.
  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      const typing =
        event.target instanceof HTMLTextAreaElement ||
        event.target instanceof HTMLInputElement;

      if (event.key === "Enter" && !event.shiftKey && !typing) {
        event.preventDefault();
        submit();
      }
      if ((event.key === "f" || event.key === "F") && !typing) {
        event.preventDefault();
        toggleMark();
      }
    }

    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [submit, toggleMark]);

  if (error && !session) {
    return (
      <div className="flex flex-col gap-4">
        <Alert>{error}</Alert>
        <Link href={`/spaces/${spaceId}/exam`} className="text-sm text-accent hover:underline">
          &larr; Back to exam setup
        </Link>
      </div>
    );
  }

  if (!session || !item) {
    return (
      <div className="grid place-items-center py-20">
        <Spinner className="size-5 text-text-faint" />
      </div>
    );
  }

  const currentState = session.items.find((entry) => entry.position === item.position);
  const alreadyAnswered = Boolean(currentState?.answered);
  const unanswered = session.totalItems - session.answeredCount;

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-5 lg:flex-row lg:items-start">
      <div className="flex min-w-0 flex-1 flex-col gap-5">
        <div className="flex flex-wrap items-center gap-3">
          <Countdown remainingMs={remainingMs} />
          <span className="font-mono text-xs text-text-muted tabular-nums">
            {session.answeredCount} / {session.totalItems} answered
          </span>
          <span className="ml-auto font-mono text-xs text-text-faint">
            Question {item.position + 1}
          </span>
        </div>

        {error && <Alert>{error}</Alert>}

        <Card className="flex flex-col gap-5 p-5">
          <div className="flex flex-wrap items-center gap-1.5">
            <Badge tone="accent">{humanType(item.type)}</Badge>
            <Badge>{title(item.difficulty)}</Badge>
            {alreadyAnswered && (
              <span className="font-mono text-[11px] text-text-faint">
                ANSWERED — SUBMIT AGAIN TO CHANGE IT
              </span>
            )}
          </div>

          <h1 className="text-lg leading-snug font-medium whitespace-pre-wrap text-text">
            {item.stem}
          </h1>

          <AnswerInput
            // Keyed by position rather than question id, so moving to a different question
            // remounts the control even in the unlikely case the same question appears twice.
            key={item.position}
            item={item}
            // `answer` already holds whatever was restored for this position — navigating
            // sets the item and the answer together, so at the moment this control mounts
            // the two agree. It is read once, on mount, and the learner owns it after that.
            initialAnswer={answer}
            onChange={setAnswer}
            locked={false}
            result={null}
          />

          {/* No hints in an exam. There are none in the real one either. */}
        </Card>

        <div className="flex flex-wrap items-center gap-3">
          <Button onClick={submit} loading={submitting} disabled={!answer}>
            {alreadyAnswered ? "Change answer" : "Save answer"}
          </Button>
          <Button variant="secondary" onClick={toggleMark} disabled={alreadyAnswered}>
            {currentState?.state === "MARKED_FOR_REVIEW" ? "Unflag" : "Flag for review"}
          </Button>
          <span className="font-mono text-[11px] text-text-faint">Enter to save · F to flag</span>
        </div>
      </div>

      <aside className="flex w-full shrink-0 flex-col gap-3 lg:w-64">
        <Card className="flex flex-col gap-3 p-4">
          <h2 className="text-[13px] font-medium text-text">Paper</h2>
          <NavigationGrid
            items={session.items}
            current={item.position}
            onSelect={openPosition}
          />
          <Legend />
        </Card>

        <Button
          variant="secondary"
          onClick={() => {
            const warning =
              unanswered > 0
                ? `Submit with ${unanswered} question${unanswered === 1 ? "" : "s"} unanswered? They will not count against you, but you cannot come back.`
                : "Submit the exam? You cannot come back to it.";
            if (confirm(warning)) goToResults();
          }}
        >
          Submit exam
        </Button>
      </aside>
    </div>
  );
}

/** Replaces one cell's state without rebuilding the rest of the session object. */
function withItemState(
  session: SessionResponse,
  position: number,
  state: ItemSummary["state"],
): SessionResponse {
  return {
    ...session,
    items: session.items.map((entry) =>
      entry.position === position
        ? { ...entry, state, answered: state === "ANSWERED" || entry.answered }
        : entry,
    ),
  };
}

function Countdown({ remainingMs }: { remainingMs: number | null }) {
  if (remainingMs === null) {
    return <span className="font-mono text-sm text-text-muted">Untimed</span>;
  }

  const total = Math.ceil(remainingMs / 1000);
  const minutes = Math.floor(total / 60);
  const seconds = total % 60;
  // Under five minutes the clock turns red, which is the only nudge an exam should give.
  const urgent = remainingMs <= 5 * 60_000;

  return (
    <span
      role="timer"
      aria-live="off"
      className={cn(
        "rounded-md px-2 py-1 font-mono text-sm font-semibold tabular-nums",
        urgent ? "bg-danger-soft text-danger" : "bg-surface-2 text-text",
      )}
    >
      {minutes}:{String(seconds).padStart(2, "0")}
    </span>
  );
}

function NavigationGrid({
  items,
  current,
  onSelect,
}: {
  items: ItemSummary[];
  current: number;
  onSelect: (position: number) => void;
}) {
  return (
    <ol className="grid grid-cols-8 gap-1.5 lg:grid-cols-6">
      {items.map((entry) => (
        <li key={entry.position}>
          <button
            type="button"
            onClick={() => onSelect(entry.position)}
            aria-current={entry.position === current}
            aria-label={`Question ${entry.position + 1}, ${entry.state.toLowerCase().replace(/_/g, " ")}`}
            className={cn(
              "grid size-8 w-full place-items-center rounded border font-mono text-[11px] tabular-nums transition-colors",
              entry.position === current && "ring-2 ring-accent ring-offset-1 ring-offset-surface",
              entry.answered
                ? "border-success bg-success-soft text-success"
                : entry.state === "MARKED_FOR_REVIEW"
                  ? "border-flag bg-flag-soft text-flag"
                  : entry.state === "UNSEEN"
                    ? "border-border bg-surface text-text-faint hover:border-border-strong"
                    : "border-border-strong bg-surface-2 text-text-muted",
            )}
          >
            {entry.position + 1}
          </button>
        </li>
      ))}
    </ol>
  );
}

function Legend() {
  return (
    <ul className="flex flex-wrap gap-x-3 gap-y-1 text-[11px] text-text-faint">
      <LegendItem className="border-success bg-success-soft" label="Answered" />
      <LegendItem className="border-flag bg-flag-soft" label="Flagged" />
      <LegendItem className="border-border-strong bg-surface-2" label="Seen" />
      <LegendItem className="border-border bg-surface" label="Not seen" />
    </ul>
  );
}

function LegendItem({ className, label }: { className: string; label: string }) {
  return (
    <li className="flex items-center gap-1">
      <span className={cn("size-2.5 rounded-sm border", className)} />
      {label}
    </li>
  );
}
