"use client";

import { Button, Field, Input, Textarea, cn } from "@/components/ui";
import type { QuestionOption, QuestionPayload, QuestionTypeKey } from "@/lib/api/types";

const MAX_OPTIONS = 8;

interface EditorProps {
  payload: QuestionPayload;
  onChange: (payload: QuestionPayload) => void;
  errors: Record<string, string>;
}

/**
 * Renders the right editor for a question format.
 *
 * <p>A lookup, not a chain of conditionals in the page. It mirrors the sealed hierarchy on
 * the server: adding a format means adding one entry here and one implementation there, and
 * nothing in the surrounding form has to know a new format exists.
 */
const EDITORS: Record<QuestionTypeKey, (props: EditorProps) => React.ReactElement> = {
  MCQ: (props) => <ChoiceEditor {...props} singleAnswer />,
  MULTI_SELECT: (props) => <ChoiceEditor {...props} singleAnswer={false} />,
  TRUE_FALSE: TrueFalseEditor,
  SHORT_ANSWER: ShortAnswerEditor,
  FLASHCARD: FlashcardEditor,
};

export function PayloadEditor({
  type,
  payload,
  onChange,
  errors,
}: EditorProps & { type: QuestionTypeKey }) {
  const Editor = EDITORS[type];
  return <Editor payload={payload} onChange={onChange} errors={errors} />;
}

/** The empty payload a format starts from, so switching type never leaves stale fields. */
export function emptyPayloadFor(type: QuestionTypeKey): QuestionPayload {
  switch (type) {
    case "MCQ":
    case "MULTI_SELECT":
      return {
        options: [
          { id: "a", text: "", correct: false },
          { id: "b", text: "", correct: false },
        ],
        shuffle: true,
      };
    case "TRUE_FALSE":
      return { answer: true };
    case "SHORT_ANSWER":
      return { acceptedAnswers: [""], matchMode: "NORMALIZED", requiredKeywords: [] };
    case "FLASHCARD":
      return { front: "", back: "" };
  }
}

/* ------------------------------------------------------------------ choice */

function ChoiceEditor({
  payload,
  onChange,
  errors,
  singleAnswer,
}: EditorProps & { singleAnswer: boolean }) {
  const options = (payload.options as QuestionOption[] | undefined) ?? [];

  function update(next: QuestionOption[]) {
    onChange({ ...payload, options: next });
  }

  function setCorrect(index: number, correct: boolean) {
    update(
      options.map((option, i) =>
        // Single-answer means selecting one clears the rest, which is what the server
        // enforces anyway. Doing it here means the form cannot be submitted into a
        // rejection the user could have been saved from.
        singleAnswer
          ? { ...option, correct: i === index && correct }
          : i === index
            ? { ...option, correct }
            : option,
      ),
    );
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-baseline justify-between">
        <span className="text-[13px] font-medium text-text">
          Options{" "}
          <span className="font-normal text-text-faint">
            {singleAnswer ? "— mark exactly one correct" : "— mark every correct one"}
          </span>
        </span>
        {errors.options && <span className="text-xs text-danger">{errors.options}</span>}
      </div>

      <ul className="flex flex-col gap-2">
        {options.map((option, index) => (
          <li key={option.id} className="flex items-center gap-2">
            <input
              type={singleAnswer ? "radio" : "checkbox"}
              name="correct-option"
              checked={option.correct}
              onChange={(e) => setCorrect(index, e.target.checked)}
              aria-label={`Option ${option.id} is correct`}
              className="size-4 accent-[var(--accent)]"
            />
            <span className="w-4 font-mono text-xs text-text-faint">{option.id}</span>
            <Input
              value={option.text}
              onChange={(e) =>
                update(
                  options.map((o, i) => (i === index ? { ...o, text: e.target.value } : o)),
                )
              }
              placeholder={`Option ${option.id}`}
              className="h-9 text-[13px]"
            />
            <Button
              type="button"
              variant="ghost"
              size="sm"
              disabled={options.length <= 2}
              onClick={() => update(options.filter((_, i) => i !== index))}
              aria-label={`Remove option ${option.id}`}
            >
              ✕
            </Button>
          </li>
        ))}
      </ul>

      <div>
        <Button
          type="button"
          variant="secondary"
          size="sm"
          disabled={options.length >= MAX_OPTIONS}
          onClick={() =>
            update([
              ...options,
              // Positional letters, matching what the server assigns when ids are omitted.
              { id: String.fromCharCode(97 + options.length), text: "", correct: false },
            ])
          }
        >
          Add option
        </Button>
      </div>

      <label className="flex items-center gap-2 text-[13px] text-text-muted">
        <input
          type="checkbox"
          checked={(payload.shuffle as boolean) ?? true}
          onChange={(e) => onChange({ ...payload, shuffle: e.target.checked })}
          className="size-4 accent-[var(--accent)]"
        />
        Shuffle options when served
        <span className="text-text-faint">
          — otherwise a bank whose answer is usually C teaches position, not content
        </span>
      </label>
    </div>
  );
}

/* ------------------------------------------------------------------ true/false */

function TrueFalseEditor({ payload, onChange }: EditorProps) {
  const answer = payload.answer === true || payload.answer === "true";

  return (
    <Field label="The statement is" htmlFor="tf-true">
      <div className="flex gap-2">
        {[true, false].map((value) => (
          <button
            key={String(value)}
            id={value ? "tf-true" : "tf-false"}
            type="button"
            onClick={() => onChange({ ...payload, answer: value })}
            className={cn(
              "h-10 flex-1 rounded-md border text-sm font-medium transition-colors",
              answer === value
                ? "border-accent bg-accent-soft text-accent"
                : "border-border bg-surface text-text-muted hover:border-border-strong",
            )}
          >
            {value ? "True" : "False"}
          </button>
        ))}
      </div>
    </Field>
  );
}

/* ------------------------------------------------------------------ short answer */

const MATCH_MODES = [
  { key: "NORMALIZED", label: "Forgiving", hint: "Ignores case, accents, punctuation and a leading “the”." },
  { key: "EXACT", label: "Exact", hint: "Character for character. For syntax." },
  { key: "KEYWORDS", label: "Keywords", hint: "Correct when every keyword appears." },
] as const;

function ShortAnswerEditor({ payload, onChange, errors }: EditorProps) {
  const mode = (payload.matchMode as string) ?? "NORMALIZED";
  const accepted = (payload.acceptedAnswers as string[] | undefined) ?? [];
  const keywords = (payload.requiredKeywords as string[] | undefined) ?? [];

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-2">
        <span className="text-[13px] font-medium text-text">Matching</span>
        <div className="grid gap-2 sm:grid-cols-3">
          {MATCH_MODES.map((option) => (
            <button
              key={option.key}
              type="button"
              onClick={() => onChange({ ...payload, matchMode: option.key })}
              className={cn(
                "rounded-md border p-2.5 text-left transition-colors",
                mode === option.key
                  ? "border-accent bg-accent-soft"
                  : "border-border bg-surface hover:border-border-strong",
              )}
            >
              <span className="block text-[13px] font-medium text-text">{option.label}</span>
              <span className="mt-0.5 block text-xs leading-snug text-text-muted">
                {option.hint}
              </span>
            </button>
          ))}
        </div>
      </div>

      {mode === "KEYWORDS" ? (
        <ListField
          label="Required keywords"
          hint="Every one of these must appear in the answer."
          values={keywords}
          error={errors.requiredKeywords}
          onChange={(next) => onChange({ ...payload, requiredKeywords: next })}
        />
      ) : (
        <ListField
          label="Accepted answers"
          hint="Any one of these counts as correct."
          values={accepted}
          error={errors.acceptedAnswers}
          onChange={(next) => onChange({ ...payload, acceptedAnswers: next })}
        />
      )}
    </div>
  );
}

function ListField({
  label,
  hint,
  values,
  error,
  onChange,
}: {
  label: string;
  hint: string;
  values: string[];
  error?: string;
  onChange: (values: string[]) => void;
}) {
  const rows = values.length === 0 ? [""] : values;

  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-baseline justify-between">
        <span className="text-[13px] font-medium text-text">{label}</span>
        {error && <span className="text-xs text-danger">{error}</span>}
      </div>
      <ul className="flex flex-col gap-2">
        {rows.map((value, index) => (
          <li key={index} className="flex items-center gap-2">
            <Input
              value={value}
              onChange={(e) =>
                onChange(rows.map((v, i) => (i === index ? e.target.value : v)))
              }
              className="h-9 text-[13px]"
            />
            <Button
              type="button"
              variant="ghost"
              size="sm"
              disabled={rows.length <= 1}
              onClick={() => onChange(rows.filter((_, i) => i !== index))}
              aria-label={`Remove entry ${index + 1}`}
            >
              ✕
            </Button>
          </li>
        ))}
      </ul>
      <div>
        <Button type="button" variant="secondary" size="sm" onClick={() => onChange([...rows, ""])}>
          Add another
        </Button>
      </div>
      <p className="text-xs text-text-faint">{hint}</p>
    </div>
  );
}

/* ------------------------------------------------------------------ flashcard */

function FlashcardEditor({ payload, onChange, errors }: EditorProps) {
  return (
    <div className="grid gap-4 sm:grid-cols-2">
      <Field label="Front" htmlFor="card-front" error={errors.front} hint="The prompt.">
        <Textarea
          id="card-front"
          rows={4}
          value={(payload.front as string) ?? ""}
          onChange={(e) => onChange({ ...payload, front: e.target.value })}
          placeholder="What is dependency injection?"
        />
      </Field>
      <Field label="Back" htmlFor="card-back" error={errors.back} hint="The answer.">
        <Textarea
          id="card-back"
          rows={4}
          value={(payload.back as string) ?? ""}
          onChange={(e) => onChange({ ...payload, back: e.target.value })}
          placeholder="Supplying a component's collaborators from outside it."
        />
      </Field>
    </div>
  );
}
