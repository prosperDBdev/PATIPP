"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { Alert, EmptyState, Spinner } from "@/components/ui";
import { QuestionForm, type QuestionFormValues } from "@/components/question/question-form";
import { api } from "@/lib/api/client";
import type { Question, Subject } from "@/lib/api/types";

export default function NewQuestionPage() {
  const { spaceId } = useParams<{ spaceId: string }>();
  const router = useRouter();

  const [subjects, setSubjects] = useState<Subject[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    (async () => {
      try {
        const loaded = await api<Subject[]>(`/api/v1/spaces/${spaceId}/curriculum`);
        if (!cancelled) setSubjects(loaded);
      } catch {
        if (!cancelled) setError("Could not load this space.");
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [spaceId]);

  async function create(values: QuestionFormValues) {
    const created = await api<Question>(`/api/v1/spaces/${spaceId}/questions`, {
      method: "POST",
      body: {
        subjectId: values.subjectId,
        topicId: values.topicId,
        type: values.type,
        difficulty: values.difficulty,
        stem: values.stem,
        explanation: values.explanation || null,
        payload: values.payload,
        tags: values.tags,
        estimatedSeconds: values.estimatedSeconds,
      },
    });
    router.replace(`/spaces/${spaceId}/questions/${created.id}`);
  }

  if (error) {
    return <Alert>{error}</Alert>;
  }

  if (!subjects) {
    return (
      <div className="grid place-items-center py-20">
        <Spinner className="size-5 text-text-faint" />
      </div>
    );
  }

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
      <header className="flex flex-col gap-1.5">
        <Link
          href={`/spaces/${spaceId}/questions`}
          className="text-sm text-text-muted hover:text-text"
        >
          &larr; Back to questions
        </Link>
        <h1 className="text-2xl font-semibold tracking-tight text-text">New question</h1>
      </header>

      {subjects.length === 0 ? (
        <EmptyState
          title="Add a subject first"
          description="A question belongs to a subject, so the curriculum needs at least one before you can write anything."
          action={
            <Link
              href={`/spaces/${spaceId}/curriculum`}
              className="mt-1 rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-fg hover:bg-accent-hover"
            >
              Edit curriculum
            </Link>
          }
        />
      ) : (
        <QuestionForm
          subjects={subjects}
          onSubmit={create}
          onCancel={() => router.push(`/spaces/${spaceId}/questions`)}
          submitLabel="Create question"
        />
      )}
    </div>
  );
}
