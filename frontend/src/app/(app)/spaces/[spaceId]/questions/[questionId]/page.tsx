"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { Alert, Badge, Button, Card, Spinner } from "@/components/ui";
import { QuestionForm, type QuestionFormValues } from "@/components/question/question-form";
import { api, ApiError } from "@/lib/api/client";
import type { Question, Subject, VersionSummary } from "@/lib/api/types";

export default function EditQuestionPage() {
  const { spaceId, questionId } = useParams<{ spaceId: string; questionId: string }>();
  const router = useRouter();

  const [question, setQuestion] = useState<Question | null>(null);
  const [subjects, setSubjects] = useState<Subject[]>([]);
  const [versions, setVersions] = useState<VersionSummary[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    (async () => {
      try {
        const [loadedQuestion, loadedSubjects, loadedVersions] = await Promise.all([
          api<Question>(`/api/v1/spaces/${spaceId}/questions/${questionId}`),
          api<Subject[]>(`/api/v1/spaces/${spaceId}/curriculum`),
          api<VersionSummary[]>(`/api/v1/spaces/${spaceId}/questions/${questionId}/versions`),
        ]);
        if (cancelled) return;
        setQuestion(loadedQuestion);
        setSubjects(loadedSubjects);
        setVersions(loadedVersions);
      } catch (caught) {
        if (cancelled) return;
        setError(
          caught instanceof ApiError && caught.status === 404
            ? "That question does not exist, or is not yours."
            : "Could not load this question.",
        );
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [spaceId, questionId]);

  async function save(values: QuestionFormValues) {
    const updated = await api<Question>(
      `/api/v1/spaces/${spaceId}/questions/${questionId}`,
      {
        method: "PATCH",
        body: {
          subjectId: values.subjectId,
          topicId: values.topicId,
          difficulty: values.difficulty,
          stem: values.stem,
          explanation: values.explanation || null,
          payload: values.payload,
          tags: values.tags,
          estimatedSeconds: values.estimatedSeconds,
        },
      },
    );
    setQuestion(updated);
    setVersions(
      await api<VersionSummary[]>(`/api/v1/spaces/${spaceId}/questions/${questionId}/versions`),
    );
    setNotice(
      updated.version > 1
        ? `Saved as version ${updated.version}. Earlier versions are kept.`
        : "Saved.",
    );
  }

  async function archive() {
    if (!confirm("Archive this question? It stays in your history and can be restored.")) {
      return;
    }
    try {
      await api<void>(`/api/v1/spaces/${spaceId}/questions/${questionId}`, { method: "DELETE" });
      router.replace(`/spaces/${spaceId}/questions`);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "Could not archive that question.");
    }
  }

  if (error && !question) {
    return (
      <div className="flex flex-col gap-4">
        <Alert>{error}</Alert>
        <Link href={`/spaces/${spaceId}/questions`} className="text-sm text-accent hover:underline">
          &larr; Back to questions
        </Link>
      </div>
    );
  }

  if (!question) {
    return (
      <div className="grid place-items-center py-20">
        <Spinner className="size-5 text-text-faint" />
      </div>
    );
  }

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
      <header className="flex flex-col gap-3">
        <Link
          href={`/spaces/${spaceId}/questions`}
          className="text-sm text-text-muted hover:text-text"
        >
          &larr; Back to questions
        </Link>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2">
            <h1 className="text-2xl font-semibold tracking-tight text-text">Edit question</h1>
            <Badge>v{question.version}</Badge>
            <Badge tone="accent">{question.source}</Badge>
          </div>
          <Button variant="danger" size="sm" onClick={archive}>
            Archive
          </Button>
        </div>
      </header>

      {notice && (
        <div
          role="status"
          className="rounded-md border border-success/30 bg-success-soft px-3 py-2 text-[13px] text-success"
        >
          {notice}
        </div>
      )}
      {error && <Alert>{error}</Alert>}

      <QuestionForm
        subjects={subjects}
        existing={question}
        onSubmit={save}
        onCancel={() => router.push(`/spaces/${spaceId}/questions`)}
        submitLabel="Save changes"
      />

      {versions.length > 1 && (
        <section className="flex flex-col gap-2">
          <h2 className="text-base font-semibold text-text">History</h2>
          <p className="text-sm text-text-muted">
            Editing the wording creates a version rather than overwriting one, so an attempt
            recorded in January still points at the text it was answered against.
          </p>
          <Card className="divide-y divide-[var(--border)]">
            {versions.map((version) => (
              <div key={version.id} className="flex items-baseline gap-3 px-4 py-2.5">
                <span className="font-mono text-xs text-text-faint tabular-nums">
                  v{version.version}
                </span>
                <span className="flex-1 truncate text-[13px] text-text">{version.stem}</span>
                {version.current && <Badge tone="success">current</Badge>}
                <span className="font-mono text-[11px] text-text-faint">
                  {new Date(version.createdAt).toLocaleDateString()}
                </span>
              </div>
            ))}
          </Card>
        </section>
      )}
    </div>
  );
}
