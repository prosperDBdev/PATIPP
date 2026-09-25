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
  CODING: CodingEditor,
  DEBUGGING: DebuggingEditor,
  OUTPUT_PREDICTION: OutputPredictionEditor,
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
    case "CODING":
      return { language: "javascript", starterCode: "", referenceSolution: "", rubric: [""] };
    case "DEBUGGING":
      return { language: "javascript", code: "", defectLine: 1, defectSummary: "", rubric: [] };
    case "OUTPUT_PREDICTION":
      return { language: "javascript", code: "", expectedOutput: "", matchMode: "TRIMMED" };
  }
}

/* ------------------------------------------------------------------ code */

const LANGUAGES = ["javascript", "typescript", "java", "python", "sql", "html", "css", "text"];

/**
 * A monospace field that does not fight you.
 *
 * <p>Tab inserts a tab rather than moving focus, because a code field where Tab escapes is
 * unusable for the one thing it exists for. Spellcheck and autocapitalise are off for the
 * same reason.
 */
function CodeField({
  label,
  hint,
  value,
  rows = 10,
  error,
  onChange,
}: {
  label: string;
  hint?: string;
  value: string;
  rows?: number;
  error?: string;
  onChange: (next: string) => void;
}) {
  // Derived from the label so the field and its <label> stay associated without every
  // caller having to invent an id and keep the two in step.
  const id = "code-" + label.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/-+$/, "");

  return (
    <Field label={label} hint={hint} error={error} htmlFor={id}>
      <Textarea
        id={id}
        rows={rows}
        value={value}
        spellCheck={false}
        autoCapitalize="off"
        autoCorrect="off"
        className="font-mono text-[12.5px] leading-relaxed"
        onChange={(event) => onChange(event.target.value)}
        onKeyDown={(event) => {
          if (event.key !== "Tab" || event.shiftKey) return;
          event.preventDefault();
          const field = event.currentTarget;
          const { selectionStart: from, selectionEnd: to } = field;
          onChange(`${value.slice(0, from)}  ${value.slice(to)}`);
          // Put the caret after the inserted indent on the next frame, once React has
          // re-rendered with the new value.
          requestAnimationFrame(() => field.setSelectionRange(from + 2, from + 2));
        }}
      />
    </Field>
  );
}

function LanguageField({
  payload,
  onChange,
}: {
  payload: QuestionPayload;
  onChange: (payload: QuestionPayload) => void;
}) {
  const language = String(payload.language ?? "text");

  return (
    <Field
      label="Language"
      hint="Only used for how the snippet is displayed."
      htmlFor="code-language-text"
    >
      <div className="flex flex-wrap gap-1.5">
        {LANGUAGES.map((option) => (
          <button
            key={option}
            id={"code-language-" + option}
            type="button"
            aria-pressed={language === option}
            onClick={() => onChange({ ...payload, language: option })}
            className={cn(
              "rounded-full border px-2.5 py-1 font-mono text-[11px] transition-colors",
              language === option
                ? "border-accent bg-accent text-accent-fg"
                : "border-border bg-surface text-text-muted hover:border-border-strong",
            )}
          >
            {option}
          </button>
        ))}
      </div>
    </Field>
  );
}

/** A short list of criteria, which is what makes a self-grade mean anything. */
function RubricEditor({
  payload,
  onChange,
  errors,
  required,
}: EditorProps & { required: boolean }) {
  const rubric = (payload.rubric as string[] | undefined) ?? [];

  function update(next: string[]) {
    onChange({ ...payload, rubric: next });
  }

  return (
    <Field
      label={required ? "Rubric" : "Rubric (optional)"}
      hint="What a good answer does. You grade yourself against these, so write them as checks."
      error={errors.rubric}
      htmlFor="rubric-1"
    >
      <div className="flex flex-col gap-2">
        {rubric.map((item, index) => (
          <div key={index} className="flex items-center gap-2">
            <span className="font-mono text-[11px] text-text-faint">{index + 1}</span>
            <Input
              id={"rubric-" + (index + 1)}
              value={item}
              placeholder="e.g. Handles the empty input"
              onChange={(event) =>
                update(rubric.map((existing, i) => (i === index ? event.target.value : existing)))
              }
            />
            <button
              type="button"
              onClick={() => update(rubric.filter((_, i) => i !== index))}
              className="text-xs text-text-faint hover:text-danger"
              aria-label={`Remove criterion ${index + 1}`}
            >
              &times;
            </button>
          </div>
        ))}
        {rubric.length < 10 && (
          <Button
            type="button"
            variant="secondary"
            size="sm"
            onClick={() => update([...rubric, ""])}
          >
            Add criterion
          </Button>
        )}
      </div>
    </Field>
  );
}

/**
 * A problem solved by hand, then self-graded.
 *
 * <p>Nothing here runs the code. The reference solution and rubric are shown to the learner
 * only after they commit, which is what turns the rubric from a set of hints into a standard.
 */
function CodingEditor({ payload, onChange, errors }: EditorProps) {
  return (
    <div className="flex flex-col gap-4">
      <p className="rounded-md border border-border bg-surface-2 px-3 py-2 text-xs text-text-muted">
        You will solve this in your own editor and grade yourself against the rubric. PATIPP
        does not run code — what it does is schedule the problem to come back and fold the
        result into your readiness.
      </p>

      <LanguageField payload={payload} onChange={onChange} />

      <CodeField
        label="Starter code (optional)"
        hint="A signature or scaffold to begin from."
        value={String(payload.starterCode ?? "")}
        rows={6}
        error={errors.starterCode}
        onChange={(starterCode) => onChange({ ...payload, starterCode })}
      />

      <CodeField
        label="Reference solution"
        hint="Revealed after you answer, to grade yourself against."
        value={String(payload.referenceSolution ?? "")}
        rows={12}
        error={errors.referenceSolution}
        onChange={(referenceSolution) => onChange({ ...payload, referenceSolution })}
      />

      <Field
        label="Expected complexity (optional)"
        error={errors.complexity}
        htmlFor="coding-complexity"
      >
        <Input
          id="coding-complexity"
          value={String(payload.complexity ?? "")}
          placeholder="e.g. O(n log n) time, O(n) space"
          onChange={(event) => onChange({ ...payload, complexity: event.target.value })}
        />
      </Field>

      <RubricEditor payload={payload} onChange={onChange} errors={errors} required />
    </div>
  );
}

/** A snippet with a defect: the line is auto-graded, the reasoning self-graded. */
function DebuggingEditor({ payload, onChange, errors }: EditorProps) {
  const code = String(payload.code ?? "");
  const lineCount = code.length === 0 ? 1 : code.split("\n").length;

  return (
    <div className="flex flex-col gap-4">
      <LanguageField payload={payload} onChange={onChange} />

      <CodeField
        label="Code with the defect"
        hint="Kept exactly as you type it — indentation is part of the question."
        value={code}
        rows={12}
        error={errors.code}
        onChange={(next) => onChange({ ...payload, code: next })}
      />

      <Field
        label="Defect line"
        hint={`Counting from 1. This snippet has ${lineCount} line${lineCount === 1 ? "" : "s"}.`}
        error={errors.defectLine}
        htmlFor="defect-line"
      >
        <Input
          id="defect-line"
          type="number"
          min={1}
          max={lineCount}
          value={String(payload.defectLine ?? 1)}
          onChange={(event) =>
            onChange({ ...payload, defectLine: Number(event.target.value) || 1 })
          }
        />
      </Field>

      <Field
        label="What is wrong"
        hint="Revealed after you answer. You grade your own explanation against it."
        error={errors.defectSummary}
        htmlFor="defect-summary"
      >
        <Textarea
          id="defect-summary"
          rows={4}
          value={String(payload.defectSummary ?? "")}
          placeholder="The loop condition uses <= so it reads one past the end of the array."
          onChange={(event) => onChange({ ...payload, defectSummary: event.target.value })}
        />
      </Field>

      <CodeField
        label="The fix (optional)"
        value={String(payload.fix ?? "")}
        rows={6}
        error={errors.fix}
        onChange={(fix) => onChange({ ...payload, fix })}
      />

      <RubricEditor payload={payload} onChange={onChange} errors={errors} required={false} />
    </div>
  );
}

/** What does it print? Fully auto-graded, with no execution anywhere. */
function OutputPredictionEditor({ payload, onChange, errors }: EditorProps) {
  const matchMode = String(payload.matchMode ?? "TRIMMED");

  return (
    <div className="flex flex-col gap-4">
      <LanguageField payload={payload} onChange={onChange} />

      <CodeField
        label="Code"
        value={String(payload.code ?? "")}
        rows={10}
        error={errors.code}
        onChange={(code) => onChange({ ...payload, code })}
      />

      <CodeField
        label="Expected output"
        hint="Exactly what it prints. This is the answer key and stays on the server."
        value={String(payload.expectedOutput ?? "")}
        rows={6}
        error={errors.expectedOutput}
        onChange={(expectedOutput) => onChange({ ...payload, expectedOutput })}
      />

      <Field
        label="How strictly to compare"
        error={errors.matchMode}
        htmlFor="match-mode-TRIMMED"
      >
        <div className="flex flex-col gap-1.5">
          {[
            ["TRIMMED", "Forgive trailing spaces and blank lines at the ends"],
            ["EXACT", "Character for character, whitespace included"],
            ["LOOSE", "Ignore all whitespace and case"],
          ].map(([value, description]) => (
            <label key={value} className="flex items-start gap-2 text-[13px]">
              <input
                id={"match-mode-" + value}
                type="radio"
                name="matchMode"
                checked={matchMode === value}
                onChange={() => onChange({ ...payload, matchMode: value })}
                className="mt-0.5 accent-[var(--accent)]"
              />
              <span>
                <span className="font-mono text-[11px] text-text">{value}</span>
                <span className="ml-1.5 text-text-muted">{description}</span>
              </span>
            </label>
          ))}
        </div>
      </Field>
    </div>
  );
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
