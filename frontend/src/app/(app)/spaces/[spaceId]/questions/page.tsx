"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { Alert, Badge, Button, Card, EmptyState, Input, Spinner } from "@/components/ui";
import { humanType, title } from "@/components/question/labels";
import { api, ApiError } from "@/lib/api/client";
import type { QuestionPage, Subject } from "@/lib/api/types";

const TYPES = ["MCQ", "MULTI_SELECT", "TRUE_FALSE", "SHORT_ANSWER", "FLASHCARD"] as const;
const DIFFICULTIES = ["EASY", "MEDIUM", "HARD", "EXPERT"] as const;
const PAGE_SIZE = 25;

export default function QuestionsPage() {
  const { spaceId } = useParams<{ spaceId: string }>();

  const [page, setPage] = useState<QuestionPage | null>(null);
  const [subjects, setSubjects] = useState<Subject[]>([]);
  const [error, setError] = useState<string | null>(null);

  const [search, setSearch] = useState("");
  const [subjectId, setSubjectId] = useState("");
  const [type, setType] = useState("");
  const [difficulty, setDifficulty] = useState("");
  const [pageIndex, setPageIndex] = useState(0);

  const load = useCallback(async () => {
    const params = new URLSearchParams({ page: String(pageIndex), size: String(PAGE_SIZE) });
    if (search.trim()) params.set("search", search.trim());
    if (subjectId) params.set("subjectId", subjectId);
    if (type) params.set("type", type);
    if (difficulty) params.set("difficulty", difficulty);

    return api<QuestionPage>(`/api/v1/spaces/${spaceId}/questions?${params}`);
  }, [spaceId, pageIndex, search, subjectId, type, difficulty]);

  useEffect(() => {
    let cancelled = false;

    (async () => {
      try {
        const [loadedPage, loadedSubjects] = await Promise.all([
          load(),
          api<Subject[]>(`/api/v1/spaces/${spaceId}/curriculum`),
        ]);
        if (cancelled) return;
        setPage(loadedPage);
        setSubjects(loadedSubjects);
      } catch (caught) {
        if (cancelled) return;
        setError(
          caught instanceof ApiError && caught.status === 404
            ? "That space does not exist, or is not yours."
            : "Could not load the question bank.",
        );
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [spaceId, load]);

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

  const hasFilters = Boolean(search || subjectId || type || difficulty);

  return (
    <div className="flex flex-col gap-6">
      <header className="flex flex-col gap-3">
        <Link href={`/spaces/${spaceId}`} className="text-sm text-text-muted hover:text-text">
          &larr; Back to space
        </Link>
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div>
            <h1 className="text-2xl font-semibold tracking-tight text-text">Questions</h1>
            <p className="mt-1 text-sm text-text-muted">
              {page ? `${page.totalItems} in this space` : "Loading…"}
            </p>
          </div>
          <div className="flex gap-2">
            <Link
              href={`/spaces/${spaceId}/questions/import`}
              className="inline-flex h-10 items-center rounded-md border border-border bg-surface px-4 text-sm font-medium text-text transition-colors hover:border-border-strong"
            >
              Import
            </Link>
            <Link
              href={`/spaces/${spaceId}/questions/new`}
              className="inline-flex h-10 items-center rounded-md bg-accent px-4 text-sm font-medium text-accent-fg shadow-sm transition-colors hover:bg-accent-hover"
            >
              New question
            </Link>
          </div>
        </div>
      </header>

      <Card className="flex flex-wrap items-center gap-2 p-3">
        <Input
          value={search}
          onChange={(e) => {
            setSearch(e.target.value);
            setPageIndex(0);
          }}
          placeholder="Search question text…"
          aria-label="Search question text"
          className="h-9 min-w-50 flex-1 text-[13px]"
        />
        <Select
          value={subjectId}
          onChange={(value) => {
            setSubjectId(value);
            setPageIndex(0);
          }}
          label="Subject"
          options={subjects.map((subject) => ({ value: subject.id, label: subject.name }))}
        />
        <Select
          value={type}
          onChange={(value) => {
            setType(value);
            setPageIndex(0);
          }}
          label="Type"
          options={TYPES.map((value) => ({ value, label: humanType(value) }))}
        />
        <Select
          value={difficulty}
          onChange={(value) => {
            setDifficulty(value);
            setPageIndex(0);
          }}
          label="Difficulty"
          options={DIFFICULTIES.map((value) => ({ value, label: title(value) }))}
        />
        {hasFilters && (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              setSearch("");
              setSubjectId("");
              setType("");
              setDifficulty("");
              setPageIndex(0);
            }}
          >
            Clear
          </Button>
        )}
      </Card>

      {page === null ? (
        <div className="grid place-items-center py-20">
          <Spinner className="size-5 text-text-faint" />
        </div>
      ) : page.items.length === 0 ? (
        <EmptyState
          title={hasFilters ? "Nothing matches those filters" : "No questions yet"}
          description={
            hasFilters
              ? "Try widening the search, or clear the filters to see the whole bank."
              : "Add a question by hand, or import a CSV or JSON file you already have. Import shows you exactly what it would do before writing anything."
          }
          action={
            !hasFilters && (
              <Link
                href={`/spaces/${spaceId}/questions/import`}
                className="mt-1 rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-fg hover:bg-accent-hover"
              >
                Import questions
              </Link>
            )
          }
        />
      ) : (
        <>
          <ul className="flex flex-col gap-2">
            {page.items.map((question) => (
              <li key={question.id}>
                <Link href={`/spaces/${spaceId}/questions/${question.id}`}>
                  <Card className="flex flex-col gap-2 p-4 transition-colors hover:border-border-strong">
                    <p className="line-clamp-2 text-sm text-text">{question.stem}</p>
                    <div className="flex flex-wrap items-center gap-1.5">
                      <Badge tone="accent">{humanType(question.type)}</Badge>
                      <Badge>{title(question.difficulty)}</Badge>
                      {question.status !== "ACTIVE" && <Badge>{question.status}</Badge>}
                      <span className="ml-1 text-xs text-text-faint">
                        {subjectName(subjects, question.subjectId)}
                      </span>
                      {question.tags.map((tag) => (
                        <span key={tag} className="text-xs text-text-faint">
                          #{tag}
                        </span>
                      ))}
                    </div>
                  </Card>
                </Link>
              </li>
            ))}
          </ul>

          {page.totalPages > 1 && (
            <div className="flex items-center justify-between">
              <Button
                variant="secondary"
                size="sm"
                disabled={page.page === 0}
                onClick={() => setPageIndex((current) => current - 1)}
              >
                Previous
              </Button>
              <span className="font-mono text-xs text-text-muted tabular-nums">
                page {page.page + 1} of {page.totalPages}
              </span>
              <Button
                variant="secondary"
                size="sm"
                disabled={page.page + 1 >= page.totalPages}
                onClick={() => setPageIndex((current) => current + 1)}
              >
                Next
              </Button>
            </div>
          )}
        </>
      )}
    </div>
  );
}

function Select({
  value,
  onChange,
  label,
  options,
}: {
  value: string;
  onChange: (value: string) => void;
  label: string;
  options: { value: string; label: string }[];
}) {
  return (
    <select
      value={value}
      onChange={(e) => onChange(e.target.value)}
      aria-label={label}
      className="h-9 rounded-md border border-border bg-surface px-2 text-[13px] text-text hover:border-border-strong"
    >
      <option value="">{label}: any</option>
      {options.map((option) => (
        <option key={option.value} value={option.value}>
          {option.label}
        </option>
      ))}
    </select>
  );
}

function subjectName(subjects: Subject[], id: string): string {
  return subjects.find((subject) => subject.id === id)?.name ?? "";
}
