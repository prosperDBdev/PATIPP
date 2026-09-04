"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { Alert, Badge, Card, Spinner } from "@/components/ui";
import { api, ApiError } from "@/lib/api/client";
import type { Space, Subject } from "@/lib/api/types";

export default function SpaceOverviewPage() {
  const { spaceId } = useParams<{ spaceId: string }>();

  const [space, setSpace] = useState<Space | null>(null);
  const [subjects, setSubjects] = useState<Subject[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    (async () => {
      try {
        const [loadedSpace, loadedSubjects] = await Promise.all([
          api<Space>(`/api/v1/spaces/${spaceId}`),
          api<Subject[]>(`/api/v1/spaces/${spaceId}/curriculum`),
        ]);
        if (cancelled) return;
        setSpace(loadedSpace);
        setSubjects(loadedSubjects);
      } catch (caught) {
        if (cancelled) return;
        setError(
          caught instanceof ApiError && caught.status === 404
            ? "That space does not exist, or is not yours."
            : "Could not load this space.",
        );
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [spaceId]);

  if (error) {
    return (
      <div className="flex flex-col gap-4">
        <Alert>{error}</Alert>
        <Link href="/" className="text-sm text-accent hover:underline">
          &larr; Back to spaces
        </Link>
      </div>
    );
  }

  if (!space || !subjects) {
    return (
      <div className="grid place-items-center py-20">
        <Spinner className="size-5 text-text-faint" />
      </div>
    );
  }

  const topicCount = subjects.reduce((total, subject) => total + countTopics(subject.topics), 0);

  return (
    <div className="flex flex-col gap-8">
      <header className="flex flex-col gap-3">
        <Link href="/" className="text-sm text-text-muted hover:text-text">
          &larr; All spaces
        </Link>
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="flex flex-col gap-2">
            <h1 className="text-2xl font-semibold tracking-tight text-text">{space.name}</h1>
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone="accent">{space.preparationType.name}</Badge>
              {space.status !== "ACTIVE" && <Badge>{space.status}</Badge>}
            </div>
            {space.description && (
              <p className="max-w-prose text-sm text-text-muted">{space.description}</p>
            )}
          </div>
        </div>
      </header>

      <section className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Stat
          label="Exam date"
          value={space.targetDate ? formatDate(space.targetDate) : "Not set"}
          detail={space.daysUntilTarget !== null ? countdown(space.daysUntilTarget) : undefined}
        />
        <Stat
          label="Target score"
          value={space.targetScore !== null ? `${space.targetScore}%` : "Not set"}
        />
        <Stat label="Subjects" value={String(subjects.length)} />
        <Stat label="Topics" value={String(topicCount)} />
      </section>

      <section className="flex flex-col gap-3">
        <div className="flex items-center justify-between">
          <h2 className="text-base font-semibold text-text">Curriculum</h2>
          <Link
            href={`/spaces/${spaceId}/curriculum`}
            className="text-sm font-medium text-accent hover:underline"
          >
            Edit curriculum
          </Link>
        </div>

        {subjects.length === 0 ? (
          <Card className="p-6 text-sm text-text-muted">
            No subjects yet.{" "}
            <Link
              href={`/spaces/${spaceId}/curriculum`}
              className="font-medium text-accent hover:underline"
            >
              Add the first one
            </Link>{" "}
            to start building this space.
          </Card>
        ) : (
          <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {subjects.map((subject) => (
              <li key={subject.id}>
                <Card className="flex h-full flex-col gap-2 p-4">
                  <div className="flex items-center gap-2">
                    <span
                      aria-hidden="true"
                      className="size-2 rounded-full"
                      style={{ background: subject.color ?? "var(--border-strong)" }}
                    />
                    <h3 className="text-sm font-semibold text-text">{subject.name}</h3>
                  </div>
                  <p className="text-xs text-text-faint">
                    {countTopics(subject.topics)}{" "}
                    {countTopics(subject.topics) === 1 ? "topic" : "topics"}
                  </p>
                </Card>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="flex flex-col gap-3">
        <div className="flex items-center justify-between">
          <h2 className="text-base font-semibold text-text">Question bank</h2>
          <Link
            href={`/spaces/${spaceId}/questions`}
            className="text-sm font-medium text-accent hover:underline"
          >
            Manage questions
          </Link>
        </div>
        <Card className="flex flex-wrap items-center gap-3 p-4">
          <p className="flex-1 text-sm text-text-muted">
            Write questions by hand, or import a CSV or JSON file you already have. Import
            checks the whole file and shows you what it would do before writing anything.
          </p>
          <Link
            href={`/spaces/${spaceId}/questions/import`}
            className="inline-flex h-9 items-center rounded-md border border-border bg-surface px-3 text-[13px] font-medium text-text transition-colors hover:border-border-strong"
          >
            Import
          </Link>
          <Link
            href={`/spaces/${spaceId}/questions/new`}
            className="inline-flex h-9 items-center rounded-md bg-accent px-3 text-[13px] font-medium text-accent-fg transition-colors hover:bg-accent-hover"
          >
            New question
          </Link>
        </Card>
      </section>

      <section className="flex flex-col gap-3">
        <h2 className="text-base font-semibold text-text">Study</h2>
        <Card className="flex flex-wrap items-center gap-3 p-4">
          <p className="flex-1 text-sm text-text-muted">
            Practice is untimed, and explains the answer as soon as you commit to one.
            Timed mocks, flashcard review and readiness follow in Phases 4 to 7.
          </p>
          <Link
            href={`/spaces/${spaceId}/practice`}
            className="inline-flex h-9 items-center rounded-md bg-accent px-3 text-[13px] font-medium text-accent-fg transition-colors hover:bg-accent-hover"
          >
            Start practising
          </Link>
        </Card>
      </section>
    </div>
  );
}

function Stat({ label, value, detail }: { label: string; value: string; detail?: string }) {
  return (
    <Card className="flex flex-col gap-1 p-4">
      <span className="font-mono text-[10px] tracking-wider text-text-faint uppercase">
        {label}
      </span>
      <span className="text-lg font-semibold text-text tabular-nums">{value}</span>
      {detail && <span className="text-xs text-text-muted">{detail}</span>}
    </Card>
  );
}

function countTopics(topics: Subject["topics"]): number {
  return topics.reduce((total, topic) => total + 1 + countTopics(topic.children), 0);
}

function formatDate(iso: string): string {
  return new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, {
    day: "numeric",
    month: "short",
    year: "numeric",
  });
}

function countdown(days: number): string {
  if (days < 0) return "date has passed";
  if (days === 0) return "today";
  if (days === 1) return "1 day away";
  return `${days} days away`;
}
