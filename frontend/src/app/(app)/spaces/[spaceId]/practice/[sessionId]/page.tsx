"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { Alert, Badge, Button, Card, Spinner, cn } from "@/components/ui";
import { AnswerInput } from "@/components/session/answer-input";
import { WhyThisQuestion } from "@/components/session/why-this-question";
import { humanType, title } from "@/components/question/labels";
import { api, ApiError } from "@/lib/api/client";
import { describeInterval, explainGrade } from "@/components/session/interval";
import type {
  AnswerResult,
  IntervalPreview,
  ServedItem,
  SessionResponse,
} from "@/lib/api/types";

/**
 * The session runner.
 *
 * <p>This chrome — progress, question, answer control, feedback, keyboard handling — is what
 * exam, interview and flashcard modes will reuse. Only the answer control varies by format,
 * and that is resolved by a lookup rather than by branching here.
 */
export default function SessionRunnerPage() {
  const { spaceId, sessionId } = useParams<{ spaceId: string; sessionId: string }>();
  const router = useRouter();

  const [session, setSession] = useState<SessionResponse | null>(null);
  const [item, setItem] = useState<ServedItem | null>(null);
  const [answer, setAnswer] = useState<Record<string, unknown> | null>(null);
  const [result, setResult] = useState<AnswerResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  // Stored with the question it belongs to, rather than cleared at the top of the effect.
  // Clearing there is a setState during render as far as React is concerned; tagging it means
  // a stale preview is simply ignored, which is the same outcome without the hazard.
  const [preview, setPreview] = useState<{ questionId: string; intervals: IntervalPreview } | null>(
    null,
  );

  // Wall-clock per question. Recorded with the attempt, and used from Phase 6 to tell
  // recall apart from working it out.
  // Initialised to 0 rather than Date.now(): calling it during render is impure. It is set
  // when a question is actually put on screen, which is the moment that matters anyway.
  const shownAt = useRef<number>(0);

  useEffect(() => {
    let cancelled = false;

    (async () => {
      try {
        const loaded = await api<SessionResponse>(
          `/api/v1/spaces/${spaceId}/sessions/${sessionId}`,
        );
        if (cancelled) return;
        setSession(loaded);
        setItem(loaded.currentItem);
        shownAt.current = Date.now();

        if (loaded.status !== "IN_PROGRESS" || !loaded.currentItem) {
          router.replace(`/spaces/${spaceId}/practice/${sessionId}/summary`);
        }
      } catch (caught) {
        if (!cancelled) {
          setError(
            caught instanceof ApiError && caught.status === 404
              ? "That session does not exist, or is not yours."
              : "Could not load this session.",
          );
        }
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [spaceId, sessionId, router]);

  useEffect(() => {
    if (!item) return;
    let cancelled = false;
    const questionId = item.questionId;

    (async () => {
      try {
        const loaded = await api<IntervalPreview>(
          `/api/v1/spaces/${spaceId}/questions/${questionId}/interval-preview`,
        );
        if (!cancelled) setPreview({ questionId, intervals: loaded });
      } catch {
        // A missing preview just means the buttons carry no interval. Not worth an error.
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [spaceId, item]);

  const submit = useCallback(async () => {
    if (!item || !answer || submitting || result) return;

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
      setResult(outcome);
      setSession((current) =>
        current
          ? {
              ...current,
              answeredCount: outcome.answeredCount,
              correctCount: current.correctCount + (outcome.correct ? 1 : 0),
            }
          : current,
      );
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "Could not submit that answer.");
    } finally {
      setSubmitting(false);
    }
  }, [item, answer, submitting, result, spaceId, sessionId]);

  const advance = useCallback(() => {
    if (!result) return;

    if (result.sessionComplete || !result.nextItem) {
      router.replace(`/spaces/${spaceId}/practice/${sessionId}/summary`);
      return;
    }

    setItem(result.nextItem);
    setAnswer(null);
    setResult(null);
    shownAt.current = Date.now();
  }, [result, router, spaceId, sessionId]);

  // Enter submits, then advances. Number keys pick an option. Keeping hands on the keyboard
  // is most of what makes a long practice session bearable.
  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      const typing = event.target instanceof HTMLTextAreaElement
        || event.target instanceof HTMLInputElement;

      if (event.key === "Enter" && !event.shiftKey) {
        if (typing && !result) return; // let Enter be a newline while writing an answer
        event.preventDefault();
        if (result) advance();
        else submit();
      }
    }

    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [result, submit, advance]);

  if (error && !session) {
    return (
      <div className="flex flex-col gap-4">
        <Alert>{error}</Alert>
        <Link href={`/spaces/${spaceId}/practice`} className="text-sm text-accent hover:underline">
          &larr; Back to practice
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

  const progress = session.totalItems === 0 ? 0 : (session.answeredCount / session.totalItems) * 100;

  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-5">
      {/* Progress, not a countdown. Practice is untimed on purpose. */}
      <div className="flex flex-col gap-2">
        <div className="flex items-center justify-between text-xs text-text-muted">
          <span className="font-mono tabular-nums">
            {session.answeredCount + 1} of {session.totalItems}
          </span>
          <span className="font-mono tabular-nums">
            {session.correctCount} correct
          </span>
        </div>
        <div className="h-1 overflow-hidden rounded-full bg-surface-2">
          <div
            className="h-full rounded-full bg-accent transition-[width] duration-300"
            style={{ width: `${progress}%` }}
          />
        </div>
      </div>

      {error && <Alert>{error}</Alert>}

      <Card className="flex flex-col gap-5 p-5">
        <div className="flex flex-wrap items-center gap-1.5">
          <Badge tone="accent">{humanType(item.type)}</Badge>
          <Badge>{title(item.difficulty)}</Badge>
        </div>

        <h1 className="text-lg leading-snug font-medium whitespace-pre-wrap text-text">
          {item.stem}
        </h1>

        <AnswerInput
          // Keyed by question: React remounts the control for each question, so its state
          // resets by construction instead of by an effect.
          key={item.questionId}
          item={item}
          onChange={setAnswer}
          locked={Boolean(result)}
          result={result ? { correct: result.correct === true, correctAnswer: result.correctAnswer } : null}
          preview={preview?.questionId === item.questionId ? preview.intervals : undefined}
        />

        <WhyThisQuestion reason={item.selectionReason} />

        {item.hints.length > 0 && !result && (
          <details className="text-sm">
            <summary className="cursor-pointer text-text-muted hover:text-text">
              Need a hint?
            </summary>
            <ul className="mt-2 flex flex-col gap-1 pl-4 text-text-muted">
              {item.hints.map((hint, index) => (
                <li key={index} className="list-disc">
                  {hint}
                </li>
              ))}
            </ul>
          </details>
        )}
      </Card>

      {result && <Feedback result={result} />}

      <div className="flex items-center gap-3">
        {result ? (
          <Button onClick={advance} autoFocus>
            {result.sessionComplete ? "See results" : "Next question"}
          </Button>
        ) : (
          <Button onClick={submit} loading={submitting} disabled={!answer}>
            Submit answer
          </Button>
        )}

        <span className="font-mono text-[11px] text-text-faint">
          Enter to {result ? "continue" : "submit"}
        </span>

        <button
          type="button"
          onClick={async () => {
            if (!confirm("End this session? Your answers so far are kept.")) return;
            await api<void>(`/api/v1/spaces/${spaceId}/sessions/${sessionId}/complete`, {
              method: "POST",
            });
            router.replace(`/spaces/${spaceId}/practice/${sessionId}/summary`);
          }}
          className="ml-auto text-xs text-text-faint hover:text-text-muted"
        >
          End session
        </button>
      </div>
    </div>
  );
}

function Feedback({ result }: { result: AnswerResult }) {
  // Practice always reveals, so these are present. Defaulted anyway rather than asserted:
  // the field is nullable because a deferring mode omits it, and a crash would be a poor
  // way to find out a mode was misconfigured.
  const correct = result.correct === true;
  const score = result.score ?? 0;

  return (
    <Card
      className={cn(
        "flex flex-col gap-2 p-4",
        correct ? "border-success/40 bg-success-soft" : "border-danger/40 bg-danger-soft",
      )}
    >
      <div className="flex items-center gap-2">
        <span
          className={cn(
            "text-sm font-semibold",
            correct ? "text-success" : "text-danger",
          )}
        >
          {correct ? "Correct" : score > 0 ? "Partly right" : "Not quite"}
        </span>
        {score > 0 && score < 1 && (
          <span className="font-mono text-xs text-text-muted tabular-nums">
            {Math.round(score * 100)}%
          </span>
        )}
      </div>

      {result.note && <p className="text-[13px] text-text-muted">{result.note}</p>}

      {/* When it comes back, and why that grade. A correct answer scheduled as HARD looks
          like a bug unless the reason is on screen. */}
      {result.review && (
        <div className="flex flex-col gap-0.5 border-t border-border pt-2">
          <p className="text-[13px] text-text-muted">
            <span className="font-mono text-[11px] tracking-wide text-text-faint uppercase">
              {result.review.grade}
            </span>
            {" — "}
            {describeInterval(result.review.intervalDays)}
          </p>
          {explainGrade(result.review.grade, result.review.derived) && (
            <p className="text-[11px] text-text-faint">
              {explainGrade(result.review.grade, result.review.derived)}
            </p>
          )}
        </div>
      )}

      {/* The explanation is the point of practice — being told you were wrong teaches
          nothing on its own. */}
      {result.explanation && (
        <p className="text-sm whitespace-pre-wrap text-text">{result.explanation}</p>
      )}
    </Card>
  );
}
