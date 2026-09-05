"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { Alert, Button, Card, EmptyState, Spinner } from "@/components/ui";
import { humanType, title } from "@/components/question/labels";
import {
  Chip,
  DIFFICULTIES,
  FilterGroup,
  QUESTION_TYPES,
} from "@/components/session/filters";
import { api, ApiError } from "@/lib/api/client";
import type {
  SessionAvailability,
  SessionListEntry,
  SessionResponse,
  Subject,
} from "@/lib/api/types";

/** Choosing what to practise, or resuming what was left open. */
export default function PracticeSetupPage() {
  const { spaceId } = useParams<{ spaceId: string }>();
  const router = useRouter();

  const [subjects, setSubjects] = useState<Subject[]>([]);
  const [inProgress, setInProgress] = useState<SessionListEntry | null>(null);
  const [availability, setAvailability] = useState<SessionAvailability | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);

  const [subjectIds, setSubjectIds] = useState<string[]>([]);
  const [types, setTypes] = useState<string[]>([]);
  const [difficulties, setDifficulties] = useState<string[]>([]);
  const [length, setLength] = useState<number | null>(null);

  useEffect(() => {
    let cancelled = false;

    (async () => {
      try {
        const [loadedSubjects, open] = await Promise.all([
          api<Subject[]>(`/api/v1/spaces/${spaceId}/curriculum`),
          api<SessionListEntry | undefined>(`/api/v1/spaces/${spaceId}/sessions/in-progress`),
        ]);
        if (cancelled) return;
        setSubjects(loadedSubjects);
        setInProgress(open ?? null);
      } catch (caught) {
        if (!cancelled) {
          setError(
            caught instanceof ApiError && caught.status === 404
              ? "That space does not exist, or is not yours."
              : "Could not load this space.",
          );
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [spaceId]);

  // Re-check availability whenever the filters change, so the count on the button is honest.
  useEffect(() => {
    let cancelled = false;

    (async () => {
      try {
        const result = await api<SessionAvailability>(
          `/api/v1/spaces/${spaceId}/sessions/availability`,
          { method: "POST", body: { subjectIds, types, difficulties, length } },
        );
        if (!cancelled) setAvailability(result);
      } catch {
        if (!cancelled) setAvailability(null);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [spaceId, subjectIds, types, difficulties, length]);

  async function start() {
    setStarting(true);
    setError(null);
    try {
      const session = await api<SessionResponse>(`/api/v1/spaces/${spaceId}/sessions`, {
        method: "POST",
        body: { mode: "PRACTICE", subjectIds, types, difficulties, length },
      });
      router.push(`/spaces/${spaceId}/practice/${session.id}`);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "Could not start a session.");
      setStarting(false);
    }
  }

  if (loading) {
    return (
      <div className="grid place-items-center py-20">
        <Spinner className="size-5 text-text-faint" />
      </div>
    );
  }

  if (error && subjects.length === 0) {
    return (
      <div className="flex flex-col gap-4">
        <Alert>{error}</Alert>
        <Link href="/" className="text-sm text-accent hover:underline">
          &larr; Back to spaces
        </Link>
      </div>
    );
  }

  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-6">
      <header className="flex flex-col gap-1.5">
        <Link href={`/spaces/${spaceId}`} className="text-sm text-text-muted hover:text-text">
          &larr; Back to space
        </Link>
        <h1 className="text-2xl font-semibold tracking-tight text-text">Practice</h1>
        <p className="text-sm text-text-muted">
          Untimed, with the answer explained as soon as you commit to one.
        </p>
      </header>

      {error && <Alert>{error}</Alert>}

      {inProgress && (
        <Card className="flex flex-wrap items-center gap-3 border-accent/40 bg-accent-soft/40 p-4">
          <div className="flex-1">
            <p className="text-sm font-medium text-text">You have a session in progress</p>
            <p className="text-xs text-text-muted">
              {inProgress.answeredCount} of {inProgress.totalItems} answered
            </p>
          </div>
          <Link
            href={`/spaces/${spaceId}/practice/${inProgress.id}`}
            className="inline-flex h-9 items-center rounded-md bg-accent px-3 text-[13px] font-medium text-accent-fg hover:bg-accent-hover"
          >
            Resume
          </Link>
          <Button
            variant="secondary"
            size="sm"
            onClick={async () => {
              await api<void>(
                `/api/v1/spaces/${spaceId}/sessions/${inProgress.id}/abandon`,
                { method: "POST" },
              );
              setInProgress(null);
            }}
          >
            Abandon
          </Button>
        </Card>
      )}

      {availability?.availableQuestions === 0 ? (
        <EmptyState
          title="Nothing to practise yet"
          description="Add or import some questions first — practice draws on the question bank for this space."
          action={
            <Link
              href={`/spaces/${spaceId}/questions/import`}
              className="mt-1 rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-fg hover:bg-accent-hover"
            >
              Import questions
            </Link>
          }
        />
      ) : (
        <>
          <Card className="flex flex-col gap-4 p-4">
            <FilterGroup
              label="Subjects"
              hint="All of them, unless you narrow it."
              options={subjects.map((subject) => ({ value: subject.id, label: subject.name }))}
              selected={subjectIds}
              onChange={setSubjectIds}
            />
            <FilterGroup
              label="Question types"
              options={QUESTION_TYPES.map((type) => ({ value: type, label: humanType(type) }))}
              selected={types}
              onChange={setTypes}
            />
            <FilterGroup
              label="Difficulty"
              options={DIFFICULTIES.map((value) => ({ value, label: title(value) }))}
              selected={difficulties}
              onChange={setDifficulties}
            />

            <div className="flex flex-col gap-2">
              <span className="text-[13px] font-medium text-text">How many questions</span>
              <div className="flex flex-wrap gap-1.5">
                {[5, 10, 20, 30].map((option) => (
                  <Chip
                    key={option}
                    label={String(option)}
                    active={length === option}
                    onClick={() => setLength(length === option ? null : option)}
                  />
                ))}
                <Chip
                  label="Default"
                  active={length === null}
                  onClick={() => setLength(null)}
                />
              </div>
            </div>
          </Card>

          <div className="flex flex-wrap items-center gap-3">
            <Button onClick={start} loading={starting} disabled={Boolean(inProgress)}>
              Start practising
            </Button>
            {availability && (
              <span className="text-sm text-text-muted">
                {availability.availableQuestions} question
                {availability.availableQuestions === 1 ? "" : "s"} match
                {availability.availableQuestions === 1 ? "es" : ""}
                {availability.suggestedLength > 0 && (
                  <> — you&rsquo;ll get {availability.suggestedLength}</>
                )}
              </span>
            )}
          </div>
        </>
      )}
    </div>
  );
}

