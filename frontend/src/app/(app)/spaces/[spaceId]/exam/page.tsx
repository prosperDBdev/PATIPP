"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { Alert, Button, Card, EmptyState, Input, Spinner } from "@/components/ui";
import { humanType, title } from "@/components/question/labels";
import {
  Chip,
  DIFFICULTIES,
  FilterGroup,
  QUESTION_TYPES,
} from "@/components/session/filters";
import { api, ApiError } from "@/lib/api/client";
import type {
  ExamTemplate,
  SessionAvailability,
  SessionListEntry,
  SessionResponse,
  Subject,
} from "@/lib/api/types";

const DURATIONS = [15, 30, 45, 60, 90, 120];
const LENGTHS = [10, 20, 30, 40, 50];

/**
 * Setting up a mock exam.
 *
 * <p>The difference from practice is not the form — it is the same filters against the same
 * engine — but what the session then does: a clock the server owns, no feedback until the end,
 * and a paper weighted towards the subjects that carry the most marks. Saying so plainly here
 * matters, because a mock is only useful if you sat it under the conditions you will face.
 */
export default function ExamSetupPage() {
  const { spaceId } = useParams<{ spaceId: string }>();
  const router = useRouter();

  const [subjects, setSubjects] = useState<Subject[]>([]);
  const [templates, setTemplates] = useState<ExamTemplate[]>([]);
  const [inProgress, setInProgress] = useState<SessionListEntry | null>(null);
  const [availability, setAvailability] = useState<SessionAvailability | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [subjectIds, setSubjectIds] = useState<string[]>([]);
  const [types, setTypes] = useState<string[]>([]);
  const [difficulties, setDifficulties] = useState<string[]>([]);
  const [length, setLength] = useState<number | null>(null);
  const [durationMinutes, setDurationMinutes] = useState<number | null>(null);
  const [templateName, setTemplateName] = useState("");

  useEffect(() => {
    let cancelled = false;

    (async () => {
      try {
        const [loadedSubjects, open, loadedTemplates] = await Promise.all([
          api<Subject[]>(`/api/v1/spaces/${spaceId}/curriculum`),
          api<SessionListEntry | undefined>(`/api/v1/spaces/${spaceId}/sessions/in-progress`),
          api<ExamTemplate[]>(`/api/v1/spaces/${spaceId}/exam-templates`),
        ]);
        if (cancelled) return;
        setSubjects(loadedSubjects);
        setInProgress(open ?? null);
        setTemplates(loadedTemplates);
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

  // Asked with mode EXAM, so the suggested length is the one the preparation type proposes
  // for a paper rather than for a practice run.
  useEffect(() => {
    let cancelled = false;

    (async () => {
      try {
        const result = await api<SessionAvailability>(
          `/api/v1/spaces/${spaceId}/sessions/availability`,
          {
            method: "POST",
            body: { mode: "EXAM", subjectIds, types, difficulties, length },
          },
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

  const enter = useCallback(
    (session: SessionResponse) => router.push(`/spaces/${spaceId}/exam/${session.id}`),
    [router, spaceId],
  );

  async function start() {
    setBusy(true);
    setError(null);
    try {
      enter(
        await api<SessionResponse>(`/api/v1/spaces/${spaceId}/sessions`, {
          method: "POST",
          body: { mode: "EXAM", subjectIds, types, difficulties, length, durationMinutes },
        }),
      );
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "Could not start the exam.");
      setBusy(false);
    }
  }

  async function sit(template: ExamTemplate) {
    setBusy(true);
    setError(null);
    try {
      enter(
        await api<SessionResponse>(
          `/api/v1/spaces/${spaceId}/exam-templates/${template.id}/sit`,
          { method: "POST" },
        ),
      );
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "Could not start that exam.");
      setBusy(false);
    }
  }

  async function saveTemplate() {
    if (!templateName.trim()) return;
    setBusy(true);
    setError(null);
    try {
      const saved = await api<ExamTemplate>(`/api/v1/spaces/${spaceId}/exam-templates`, {
        method: "POST",
        body: {
          name: templateName.trim(),
          length,
          durationMinutes,
          subjectIds,
          types,
          difficulties,
        },
      });
      setTemplates((current) => [saved, ...current]);
      setTemplateName("");
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "Could not save that setup.");
    } finally {
      setBusy(false);
    }
  }

  async function archive(template: ExamTemplate) {
    await api<void>(`/api/v1/spaces/${spaceId}/exam-templates/${template.id}`, {
      method: "DELETE",
    });
    setTemplates((current) => current.filter((entry) => entry.id !== template.id));
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
        <Link href="/spaces" className="text-sm text-accent hover:underline">
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
        <h1 className="text-2xl font-semibold tracking-tight text-text">Mock exam</h1>
        <p className="text-sm text-text-muted">
          Timed, answered in any order, and marked only at the end — so the score means
          something.
        </p>
      </header>

      {error && <Alert>{error}</Alert>}

      {inProgress && (
        <Card className="flex flex-wrap items-center gap-3 border-accent/40 bg-accent-soft/40 p-4">
          <div className="flex-1">
            <p className="text-sm font-medium text-text">
              {inProgress.mode === "EXAM"
                ? "You have an exam in progress"
                : "You have a practice session in progress"}
            </p>
            <p className="text-xs text-text-muted">
              {inProgress.answeredCount} of {inProgress.totalItems} answered
              {inProgress.mode === "EXAM" && " — the clock is still running"}
            </p>
          </div>
          <Link
            href={
              inProgress.mode === "EXAM"
                ? `/spaces/${spaceId}/exam/${inProgress.id}`
                : `/spaces/${spaceId}/practice/${inProgress.id}`
            }
            className="inline-flex h-9 items-center rounded-md bg-accent px-3 text-[13px] font-medium text-accent-fg hover:bg-accent-hover"
          >
            Resume
          </Link>
          <Button
            variant="secondary"
            size="sm"
            onClick={async () => {
              await api<void>(`/api/v1/spaces/${spaceId}/sessions/${inProgress.id}/abandon`, {
                method: "POST",
              });
              setInProgress(null);
            }}
          >
            Abandon
          </Button>
        </Card>
      )}

      {templates.length > 0 && (
        <section className="flex flex-col gap-2">
          <h2 className="text-base font-semibold text-text">Saved papers</h2>
          <Card className="divide-y divide-[var(--border)]">
            {templates.map((template) => (
              <div key={template.id} className="flex flex-wrap items-center gap-3 px-4 py-3">
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium text-text">{template.name}</p>
                  <p className="text-xs text-text-muted">
                    {template.length ?? "default"} questions
                    {template.durationMinutes && ` · ${template.durationMinutes} min`}
                    {template.timesUsed > 0 &&
                      ` · sat ${template.timesUsed} time${template.timesUsed === 1 ? "" : "s"}`}
                  </p>
                </div>
                <Button
                  size="sm"
                  onClick={() => sit(template)}
                  disabled={busy || Boolean(inProgress)}
                >
                  Sit
                </Button>
                <button
                  type="button"
                  onClick={() => archive(template)}
                  className="text-xs text-text-faint hover:text-danger"
                >
                  Remove
                </button>
              </div>
            ))}
          </Card>
        </section>
      )}

      {availability?.availableQuestions === 0 ? (
        <EmptyState
          title="No questions to examine on"
          description="An exam draws on the question bank for this space. Add or import some questions first."
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
              hint="All of them, weighted as your curriculum says."
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
                {LENGTHS.map((option) => (
                  <Chip
                    key={option}
                    label={String(option)}
                    active={length === option}
                    onClick={() => setLength(length === option ? null : option)}
                  />
                ))}
                <Chip label="Default" active={length === null} onClick={() => setLength(null)} />
              </div>
            </div>

            <div className="flex flex-col gap-2">
              <span className="text-[13px] font-medium text-text">
                Time limit
                <span className="ml-1.5 font-normal text-text-faint">
                  Default is whatever the real paper allows.
                </span>
              </span>
              <div className="flex flex-wrap gap-1.5">
                {DURATIONS.map((option) => (
                  <Chip
                    key={option}
                    label={`${option} min`}
                    active={durationMinutes === option}
                    onClick={() =>
                      setDurationMinutes(durationMinutes === option ? null : option)
                    }
                  />
                ))}
                <Chip
                  label="Default"
                  active={durationMinutes === null}
                  onClick={() => setDurationMinutes(null)}
                />
              </div>
            </div>
          </Card>

          <div className="flex flex-wrap items-center gap-3">
            <Button onClick={start} loading={busy} disabled={Boolean(inProgress)}>
              Start the exam
            </Button>
            {availability && (
              <span className="text-sm text-text-muted">
                {availability.availableQuestions} available
                {availability.suggestedLength > 0 && (
                  <> — the paper will be {availability.suggestedLength} questions</>
                )}
              </span>
            )}
          </div>

          <Card className="flex flex-wrap items-end gap-3 p-4">
            <div className="min-w-[200px] flex-1">
              <label
                htmlFor="template-name"
                className="mb-1.5 block text-[13px] font-medium text-text"
              >
                Save this setup
              </label>
              <Input
                id="template-name"
                value={templateName}
                maxLength={120}
                placeholder="e.g. Semester 2 mock"
                onChange={(event) => setTemplateName(event.target.value)}
              />
            </div>
            <Button variant="secondary" onClick={saveTemplate} disabled={!templateName.trim()}>
              Save
            </Button>
          </Card>
        </>
      )}
    </div>
  );
}
