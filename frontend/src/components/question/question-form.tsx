"use client";

import { useMemo, useState } from "react";
import { Alert, Button, Card, Field, Input, Textarea, cn } from "@/components/ui";
import { emptyPayloadFor, PayloadEditor } from "@/components/question/payload-editor";
import { humanType, title } from "@/components/question/labels";
import { ApiError } from "@/lib/api/client";
import type {
  DifficultyKey,
  Question,
  QuestionPayload,
  QuestionTypeKey,
  Subject,
  Topic,
} from "@/lib/api/types";

const TYPES: QuestionTypeKey[] = [
  "MCQ",
  "MULTI_SELECT",
  "TRUE_FALSE",
  "SHORT_ANSWER",
  "FLASHCARD",
];
const DIFFICULTIES: DifficultyKey[] = ["EASY", "MEDIUM", "HARD", "EXPERT"];

export interface QuestionFormValues {
  subjectId: string;
  topicId: string | null;
  type: QuestionTypeKey;
  difficulty: DifficultyKey;
  stem: string;
  explanation: string;
  payload: QuestionPayload;
  tags: string[];
  estimatedSeconds: number | null;
}

export function QuestionForm({
  subjects,
  existing,
  onSubmit,
  onCancel,
  submitLabel,
}: {
  subjects: Subject[];
  existing?: Question;
  onSubmit: (values: QuestionFormValues) => Promise<void>;
  onCancel: () => void;
  submitLabel: string;
}) {
  const [subjectId, setSubjectId] = useState(existing?.subjectId ?? subjects[0]?.id ?? "");
  const [topicId, setTopicId] = useState<string>(existing?.topicId ?? "");
  const [type, setType] = useState<QuestionTypeKey>(existing?.type ?? "MCQ");
  const [difficulty, setDifficulty] = useState<DifficultyKey>(existing?.difficulty ?? "MEDIUM");
  const [stem, setStem] = useState(existing?.stem ?? "");
  const [explanation, setExplanation] = useState(existing?.explanation ?? "");
  const [payload, setPayload] = useState<QuestionPayload>(
    existing?.payload ?? emptyPayloadFor(existing?.type ?? "MCQ"),
  );
  const [tagsText, setTagsText] = useState((existing?.tags ?? []).join(", "));
  const [estimate, setEstimate] = useState(
    existing?.estimatedSeconds ? String(existing.estimatedSeconds) : "",
  );

  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);

  // Topics of the selected subject only. Offering the whole space would let a question be
  // filed under a topic from a different subject, which the server rejects anyway.
  const topics = useMemo(() => {
    const subject = subjects.find((candidate) => candidate.id === subjectId);
    return subject ? flattenTopics(subject.topics) : [];
  }, [subjects, subjectId]);

  function changeType(next: QuestionTypeKey) {
    setType(next);
    // Reset the payload: keeping the old one would submit an MCQ's options as a flashcard.
    setPayload(emptyPayloadFor(next));
    setFieldErrors({});
  }

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    setFieldErrors({});
    setSubmitting(true);

    try {
      await onSubmit({
        subjectId,
        topicId: topicId || null,
        type,
        difficulty,
        stem: stem.trim(),
        explanation: explanation.trim(),
        payload,
        tags: tagsText
          .split(",")
          .map((tag) => tag.trim())
          .filter(Boolean),
        estimatedSeconds: estimate ? Number(estimate) : null,
      });
    } catch (caught) {
      if (caught instanceof ApiError) {
        const fields = caught.fieldErrors();
        if (Object.keys(fields).length > 0) {
          setFieldErrors(fields);
          // Payload errors are shown against their own inputs, but a summary avoids the
          // case where the offending field is scrolled off screen.
          setError(caught.message);
        } else {
          setError(caught.message);
        }
      } else {
        setError("Could not save the question.");
      }
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-6" noValidate>
      {error && <Alert>{error}</Alert>}

      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Subject" htmlFor="subject" error={fieldErrors.subjectId}>
          <select
            id="subject"
            value={subjectId}
            onChange={(e) => {
              setSubjectId(e.target.value);
              setTopicId("");
            }}
            required
            className="h-10 w-full rounded-md border border-border bg-surface px-3 text-sm text-text hover:border-border-strong"
          >
            {subjects.length === 0 && <option value="">No subjects yet</option>}
            {subjects.map((subject) => (
              <option key={subject.id} value={subject.id}>
                {subject.name}
              </option>
            ))}
          </select>
        </Field>

        <Field label="Topic" htmlFor="topic" hint="Optional, but it sharpens weak-topic detection later.">
          <select
            id="topic"
            value={topicId}
            onChange={(e) => setTopicId(e.target.value)}
            className="h-10 w-full rounded-md border border-border bg-surface px-3 text-sm text-text hover:border-border-strong"
          >
            <option value="">No topic</option>
            {topics.map((topic) => (
              <option key={topic.id} value={topic.id}>
                {topic.path}
              </option>
            ))}
          </select>
        </Field>
      </div>

      <div className="flex flex-col gap-2">
        <span className="text-[13px] font-medium text-text">Type</span>
        <div className="flex flex-wrap gap-2">
          {TYPES.map((option) => (
            <button
              key={option}
              type="button"
              onClick={() => changeType(option)}
              // Changing type on an existing question would invalidate every attempt
              // recorded against it, so the server treats it as immutable and so do we.
              disabled={Boolean(existing)}
              className={cn(
                "h-9 rounded-md border px-3 text-[13px] font-medium transition-colors",
                "disabled:cursor-not-allowed disabled:opacity-50",
                type === option
                  ? "border-accent bg-accent-soft text-accent"
                  : "border-border bg-surface text-text-muted hover:border-border-strong",
              )}
            >
              {humanType(option)}
            </button>
          ))}
        </div>
        {existing && (
          <p className="text-xs text-text-faint">
            The type cannot change after creation — attempts are recorded against it.
          </p>
        )}
      </div>

      <Field
        label={type === "FLASHCARD" ? "Card title" : "Question"}
        htmlFor="stem"
        error={fieldErrors.stem}
      >
        <Textarea
          id="stem"
          rows={3}
          value={stem}
          onChange={(e) => setStem(e.target.value)}
          placeholder={
            type === "FLASHCARD"
              ? "A short label for this card"
              : "Which keyword declares a block-scoped constant?"
          }
          required
        />
      </Field>

      <Card className="flex flex-col gap-4 p-4">
        <PayloadEditor type={type} payload={payload} onChange={setPayload} errors={fieldErrors} />
      </Card>

      <Field
        label="Explanation"
        htmlFor="explanation"
        hint="Shown after answering. This is where the learning actually happens."
      >
        <Textarea
          id="explanation"
          rows={3}
          value={explanation}
          onChange={(e) => setExplanation(e.target.value)}
          placeholder="Why the correct answer is correct."
        />
      </Field>

      <div className="grid gap-4 sm:grid-cols-3">
        <Field label="Difficulty" htmlFor="difficulty" hint="A starting estimate; it self-corrects.">
          <select
            id="difficulty"
            value={difficulty}
            onChange={(e) => setDifficulty(e.target.value as DifficultyKey)}
            className="h-10 w-full rounded-md border border-border bg-surface px-3 text-sm text-text hover:border-border-strong"
          >
            {DIFFICULTIES.map((option) => (
              <option key={option} value={option}>
                {title(option)}
              </option>
            ))}
          </select>
        </Field>

        <Field label="Tags" htmlFor="tags" hint="Comma separated.">
          <Input
            id="tags"
            value={tagsText}
            onChange={(e) => setTagsText(e.target.value)}
            placeholder="hooks, state"
          />
        </Field>

        <Field
          label="Estimated seconds"
          htmlFor="estimate"
          error={fieldErrors.estimatedSeconds}
          hint="Blank uses the default for the type."
        >
          <Input
            id="estimate"
            type="number"
            min={5}
            max={7200}
            value={estimate}
            onChange={(e) => setEstimate(e.target.value)}
            placeholder="60"
          />
        </Field>
      </div>

      <div className="flex gap-3">
        <Button type="submit" loading={submitting} disabled={!subjectId || !stem.trim()}>
          {submitLabel}
        </Button>
        <Button type="button" variant="secondary" onClick={onCancel}>
          Cancel
        </Button>
      </div>
    </form>
  );
}

/** Depth-first flatten so the dropdown shows the tree in reading order, labelled by path. */
function flattenTopics(topics: Topic[]): Topic[] {
  return topics.flatMap((topic) => [topic, ...flattenTopics(topic.children)]);
}
