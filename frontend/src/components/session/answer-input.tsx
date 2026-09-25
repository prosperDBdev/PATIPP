"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { Textarea, cn } from "@/components/ui";
import { formatInterval } from "@/components/session/interval";
import type { IntervalPreview, PresentedOption, ServedItem } from "@/lib/api/types";

/**
 * The answer control for one question, chosen by format.
 *
 * <p>A lookup rather than conditionals in the runner, mirroring the sealed hierarchy on the
 * server. The runner never asks what type a question is; it hands the item here and gets an
 * answer object back in the shape the API expects.
 */
export interface AnswerInputProps {
  item: ServedItem;
  /** null while the answer is incomplete, which is what disables the submit button. */
  onChange: (answer: Record<string, unknown> | null) => void;
  /** Set once submitted, so the controls lock while feedback is on screen. */
  locked: boolean;
  /** Highlights correctness after submission. */
  result?: { correct: boolean; correctAnswer: Record<string, unknown> | null } | null;
  /**
   * What was previously answered, for a mode that lets you come back and change it. Read
   * once when the control mounts (the runner keys it by question), never watched afterwards:
   * the learner is editing it from that moment on.
   */
  initialAnswer?: Record<string, unknown> | null;
  /**
   * What each grade would schedule for this item, for the formats that ask for one.
   * Absent until it has loaded, and absent entirely for formats with no self-grade.
   */
  preview?: IntervalPreview;
}

export function AnswerInput(props: AnswerInputProps) {
  switch (props.item.type) {
    case "MCQ":
      return <ChoiceInput {...props} single />;
    case "MULTI_SELECT":
      return <ChoiceInput {...props} single={false} />;
    case "TRUE_FALSE":
      return <TrueFalseInput {...props} />;
    case "SHORT_ANSWER":
      return <ShortAnswerInput {...props} />;
    case "FLASHCARD":
      return <FlashcardInput {...props} />;
    case "CODING":
      return <CodingInput {...props} />;
    case "DEBUGGING":
      return <DebuggingInput {...props} />;
    case "OUTPUT_PREDICTION":
      return <OutputPredictionInput {...props} />;
  }
}

/* ------------------------------------------------------------------ code */

/** A read-only snippet with line numbers, so "line 14" means something specific. */
function CodeBlock({
  code,
  language,
  selectedLine,
  onSelectLine,
}: {
  code: string;
  language?: string;
  selectedLine?: number | null;
  onSelectLine?: (line: number) => void;
}) {
  const lines = code.replace(/\n$/, "").split("\n");
  const clickable = Boolean(onSelectLine);

  return (
    <div className="overflow-hidden rounded-lg border border-border bg-surface-2">
      {language && (
        <div className="border-b border-border px-3 py-1 font-mono text-[10px] tracking-wider text-text-faint uppercase">
          {language}
        </div>
      )}
      <div className="overflow-x-auto">
        <ol className="min-w-full py-1 font-mono text-[12.5px] leading-relaxed">
          {lines.map((line, index) => {
            const number = index + 1;
            const selected = selectedLine === number;

            return (
              <li key={number}>
                <button
                  type="button"
                  disabled={!clickable}
                  onClick={() => onSelectLine?.(number)}
                  aria-pressed={clickable ? selected : undefined}
                  className={cn(
                    "flex w-full items-start gap-3 px-3 py-px text-left",
                    clickable && "cursor-pointer hover:bg-accent-soft",
                    selected && "bg-accent-soft",
                    !clickable && "cursor-text",
                  )}
                >
                  <span
                    className={cn(
                      "w-6 shrink-0 text-right tabular-nums select-none",
                      selected ? "font-bold text-accent" : "text-text-faint",
                    )}
                  >
                    {number}
                  </span>
                  {/* pre-wrap, so indentation survives and long lines still wrap. */}
                  <span className="whitespace-pre text-text">{line || " "}</span>
                </button>
              </li>
            );
          })}
        </ol>
      </div>
    </div>
  );
}

/**
 * The self-grade control.
 *
 * <p>Shared by coding and debugging, and the same 1-4 scale a flashcard uses — which is what
 * lets a hand-solved problem enter the spaced-repetition scheduler with no special case.
 */
function SelfGrade({
  question,
  value,
  locked,
  onPick,
  preview,
}: {
  question: string;
  value: number | null;
  locked: boolean;
  onPick: (grade: number) => void;
  /** What each button would schedule. Absent until the preview has loaded. */
  preview?: IntervalPreview;
}) {
  return (
    <div className="flex flex-col gap-2">
      <p className="text-[13px] font-medium text-text">{question}</p>
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        {GRADES.map((grade) => {
          const days = preview?.[grade.key];

          return (
            <button
              key={grade.value}
              type="button"
              disabled={locked}
              aria-pressed={value === grade.value}
              onClick={() => onPick(grade.value)}
              className={cn(
                "rounded-lg border p-2.5 text-left transition-colors disabled:cursor-default",
                value === grade.value
                  ? "border-accent bg-accent-soft"
                  : "border-border bg-surface hover:border-accent",
              )}
            >
              <span className="flex items-baseline justify-between gap-1.5">
                <span className="text-[13px] font-medium text-text">
                  <span className="mr-1.5 font-mono text-[10px] text-text-faint">
                    {grade.value}
                  </span>
                  {grade.label}
                </span>
                {/* The interval this button would schedule, before it is pressed. It turns a
                    self-report from a guess into a decision with visible consequences. */}
                {days !== undefined && (
                  <span className="font-mono text-[11px] tabular-nums text-accent">
                    {formatInterval(days)}
                  </span>
                )}
              </span>
              <span className="mt-0.5 block text-[11px] leading-snug text-text-faint">
                {grade.hint}
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
}

/**
 * A coding problem: solve it elsewhere, then say honestly how it went.
 *
 * <p>The reveal is deliberately a separate step. Looking at the reference solution before
 * attempting it turns the exercise into reading comprehension, and the self-grade that follows
 * would be recording something that never happened.
 */
function CodingInput({ item, onChange, locked, initialAnswer, preview }: AnswerInputProps) {
  const [grade, setGrade] = useState<number | null>(
    () => (initialAnswer?.grade as number | undefined) ?? null,
  );
  const [attempted, setAttempted] = useState(() => initialAnswer?.grade !== undefined);

  const starter = item.presentation.starterCode as string | undefined;
  const language = item.presentation.language as string | undefined;
  const rubricCount = (item.presentation.rubricItemCount as number | undefined) ?? 0;

  if (!attempted) {
    return (
      <div className="flex flex-col gap-3">
        {starter && <CodeBlock code={starter} language={language} />}

        <div className="rounded-lg border border-dashed border-border-strong bg-surface-2 p-4">
          <p className="text-[13px] text-text">
            Solve this by hand in your editor, out loud if you can.
          </p>
          <p className="mt-1 text-xs text-text-muted">
            Narrating is most of what a technical interview is actually testing, and it is the
            part you cannot practise by reading. When you are done, reveal the reference
            solution
            {rubricCount > 0 && ` and ${rubricCount} rubric point${rubricCount === 1 ? "" : "s"}`}
            {" "}and mark yourself against it.
          </p>
          <button
            type="button"
            onClick={() => setAttempted(true)}
            className="mt-3 inline-flex h-9 items-center rounded-md bg-accent px-3 text-[13px] font-medium text-accent-fg hover:bg-accent-hover"
          >
            I have attempted it — show the solution
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      {starter && <CodeBlock code={starter} language={language} />}
      <p className="text-xs text-text-muted">
        The reference solution and rubric are below the question, once you submit. Grade
        yourself on what you actually produced, not what you now see.
      </p>
      <SelfGrade
        question="How did your solution go?"
        value={grade}
        locked={locked}
        preview={preview}
        onPick={(picked) => {
          setGrade(picked);
          onChange({ grade: picked });
        }}
      />
    </div>
  );
}

/** Find the bug: click the line, then grade your own explanation of it. */
function DebuggingInput({ item, onChange, locked, initialAnswer, preview }: AnswerInputProps) {
  const [line, setLine] = useState<number | null>(
    () => (initialAnswer?.line as number | undefined) ?? null,
  );
  const [grade, setGrade] = useState<number | null>(
    () => (initialAnswer?.grade as number | undefined) ?? null,
  );

  const code = String(item.presentation.code ?? "");
  const language = item.presentation.language as string | undefined;

  // Both halves are required: a line with no self-assessment is an incomplete answer, and
  // the submit button stays disabled until onChange has been given something whole.
  const emit = useCallback(
    (nextLine: number | null, nextGrade: number | null) => {
      onChange(nextLine !== null && nextGrade !== null
        ? { line: nextLine, grade: nextGrade }
        : null);
    },
    [onChange],
  );

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-1.5">
        <p className="text-[13px] font-medium text-text">Click the line with the defect</p>
        <CodeBlock
          code={code}
          language={language}
          selectedLine={line}
          onSelectLine={
            locked
              ? undefined
              : (picked) => {
                  setLine(picked);
                  emit(picked, grade);
                }
          }
        />
        {line !== null && (
          <p className="font-mono text-[11px] text-text-muted">line {line} selected</p>
        )}
      </div>

      <SelfGrade
        question="Could you explain why it is wrong?"
        value={grade}
        locked={locked}
        preview={preview}
        onPick={(picked) => {
          setGrade(picked);
          emit(line, picked);
        }}
      />
    </div>
  );
}

/** What does it print? Typed out, compared as strings, nothing executed. */
function OutputPredictionInput({ item, onChange, locked, initialAnswer }: AnswerInputProps) {
  const [text, setText] = useState(() => String(initialAnswer?.text ?? ""));

  const code = String(item.presentation.code ?? "");
  const language = item.presentation.language as string | undefined;
  const matchMode = String(item.presentation.matchMode ?? "TRIMMED");

  return (
    <div className="flex flex-col gap-3">
      <CodeBlock code={code} language={language} />

      <div className="flex flex-col gap-1.5">
        <label htmlFor="predicted-output" className="text-[13px] font-medium text-text">
          What does it print?
        </label>
        <Textarea
          id="predicted-output"
          rows={5}
          value={text}
          disabled={locked}
          spellCheck={false}
          autoCapitalize="off"
          autoCorrect="off"
          className="font-mono text-[12.5px]"
          placeholder="One line per line of output"
          onChange={(event) => {
            setText(event.target.value);
            onChange(event.target.value.trim() ? { text: event.target.value } : null);
          }}
        />
        <p className="text-[11px] text-text-faint">
          {matchMode === "EXACT"
            ? "Compared character for character, whitespace included."
            : matchMode === "LOOSE"
              ? "Whitespace and capitalisation are ignored."
              : "Trailing spaces and blank lines at the ends are forgiven."}
        </p>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ choice */

function ChoiceInput({
  item,
  onChange,
  locked,
  result,
  initialAnswer,
  single,
}: AnswerInputProps & { single: boolean }) {
  // Memoised because the `?? []` fallback would otherwise be a new array on every render,
  // making the keyboard effect below tear down and re-subscribe each time.
  const options = useMemo(
    () => (item.presentation.options as PresentedOption[] | undefined) ?? [],
    [item.presentation],
  );
  const correctCount = item.presentation.correctCount as number | undefined;
  // No reset effect: the runner gives this component a key of the question id, so React
  // unmounts and remounts it for each question and the state starts fresh by construction —
  // or, on a question already answered in an exam, from what was put down last time.
  const [selected, setSelected] = useState<string[]>(
    () => (initialAnswer?.optionIds as string[] | undefined) ?? [],
  );

  const correctIds = (result?.correctAnswer?.optionIds as string[] | undefined) ?? [];

  const toggle = useCallback(
    (id: string) => {
      if (locked) return;
      setSelected((current) => {
        const next = single
          ? [id]
          : current.includes(id)
            ? current.filter((value) => value !== id)
            : [...current, id];
        onChange(next.length > 0 ? { optionIds: next } : null);
        return next;
      });
    },
    [locked, single, onChange],
  );

  // Number keys pick an option, matching the badge beside each one. Together with Enter to
  // submit, a whole session can be worked through without reaching for the mouse — which is
  // most of what makes thirty questions in a row bearable.
  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (locked || event.metaKey || event.ctrlKey || event.altKey) return;
      if (event.target instanceof HTMLTextAreaElement || event.target instanceof HTMLInputElement) {
        return;
      }

      const index = Number(event.key) - 1;
      if (Number.isInteger(index) && index >= 0 && index < options.length) {
        event.preventDefault();
        toggle(options[index].id);
      }
    }

    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [locked, options, toggle]);

  return (
    <div className="flex flex-col gap-2">
      {!single && correctCount !== undefined && (
        <p className="text-xs text-text-muted">
          Choose {correctCount}. {/* Part of the question, and it gives nothing away. */}
        </p>
      )}

      <ul className="flex flex-col gap-2">
        {options.map((option, index) => {
          const chosen = selected.includes(option.id);
          const isCorrect = correctIds.includes(option.id);
          const showAsWrong = locked && chosen && !isCorrect;
          const showAsRight = locked && isCorrect;

          return (
            <li key={option.id}>
              <button
                type="button"
                onClick={() => toggle(option.id)}
                disabled={locked}
                aria-pressed={chosen}
                className={cn(
                  "flex w-full items-start gap-3 rounded-lg border p-3 text-left transition-colors",
                  "disabled:cursor-default",
                  showAsRight
                    ? "border-success bg-success-soft"
                    : showAsWrong
                      ? "border-danger bg-danger-soft"
                      : chosen
                        ? "border-accent bg-accent-soft"
                        : "border-border bg-surface hover:border-border-strong",
                )}
              >
                <span
                  aria-hidden="true"
                  className={cn(
                    "mt-0.5 grid size-5 shrink-0 place-items-center rounded font-mono text-[11px]",
                    chosen || showAsRight
                      ? "bg-accent text-accent-fg"
                      : "bg-surface-2 text-text-faint",
                    showAsRight && "bg-success text-white",
                    showAsWrong && "bg-danger text-white",
                  )}
                >
                  {/* Keyboard shortcut and identity in one. */}
                  {index + 1}
                </span>
                <span className="text-sm text-text">{option.text}</span>
              </button>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

/* ------------------------------------------------------------------ true / false */

function TrueFalseInput({
  onChange,
  locked,
  result,
  initialAnswer,
}: Omit<AnswerInputProps, "item">) {
  const [value, setValue] = useState<boolean | null>(
    () => (initialAnswer?.value as boolean | undefined) ?? null,
  );
  const correct = result?.correctAnswer?.value as boolean | undefined;

  return (
    <div className="flex gap-2">
      {[true, false].map((option) => {
        const chosen = value === option;
        const showAsRight = locked && correct === option;
        const showAsWrong = locked && chosen && correct !== option;

        return (
          <button
            key={String(option)}
            type="button"
            disabled={locked}
            onClick={() => {
              setValue(option);
              onChange({ value: option });
            }}
            className={cn(
              "h-12 flex-1 rounded-lg border text-sm font-medium transition-colors disabled:cursor-default",
              showAsRight
                ? "border-success bg-success-soft text-success"
                : showAsWrong
                  ? "border-danger bg-danger-soft text-danger"
                  : chosen
                    ? "border-accent bg-accent-soft text-accent"
                    : "border-border bg-surface text-text-muted hover:border-border-strong",
            )}
          >
            {option ? "True" : "False"}
          </button>
        );
      })}
    </div>
  );
}

/* ------------------------------------------------------------------ short answer */

function ShortAnswerInput({
  onChange,
  locked,
  initialAnswer,
}: Omit<AnswerInputProps, "item" | "result">) {
  const [text, setText] = useState(() => String(initialAnswer?.text ?? ""));

  return (
    <Textarea
      rows={3}
      value={text}
      disabled={locked}
      autoFocus
      onChange={(e) => {
        setText(e.target.value);
        onChange(e.target.value.trim() ? { text: e.target.value } : null);
      }}
      placeholder="Type your answer"
      aria-label="Your answer"
    />
  );
}

/* ------------------------------------------------------------------ flashcard */

const GRADES = [
  { value: 1, key: "AGAIN", label: "Again", hint: "Did not recall it" },
  { value: 2, key: "HARD", label: "Hard", hint: "Recalled with effort" },
  { value: 3, key: "GOOD", label: "Good", hint: "Recalled it" },
  { value: 4, key: "EASY", label: "Easy", hint: "Instant" },
] as const;

function FlashcardInput({ item, onChange, locked, preview }: AnswerInputProps) {
  // Remounted per question via the key in the runner, so the card starts face-down again
  // without an effect resetting it.
  const [revealed, setRevealed] = useState(false);
  const [grade, setGrade] = useState<number | null>(null);
  const back = useMemo(() => String(item.presentation.back ?? ""), [item.presentation]);
  const mnemonic = item.presentation.mnemonic as string | undefined;

  if (!revealed) {
    return (
      <button
        type="button"
        onClick={() => setRevealed(true)}
        className="w-full rounded-lg border border-dashed border-border-strong bg-surface-2 px-4 py-8 text-sm font-medium text-text-muted transition-colors hover:border-accent hover:text-text"
      >
        Show answer
        <span className="mt-1 block text-xs font-normal text-text-faint">
          Try to recall it first — the effort is what makes it stick.
        </span>
      </button>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="rounded-lg border border-border bg-surface-2 p-4">
        <p className="text-sm whitespace-pre-wrap text-text">{back}</p>
        {mnemonic && <p className="mt-2 text-xs text-text-muted">{mnemonic}</p>}
      </div>

      <SelfGrade
        question="How well did you recall it?"
        value={grade}
        locked={locked}
        preview={preview}
        onPick={(picked) => {
          setGrade(picked);
          onChange({ grade: picked });
        }}
      />
    </div>
  );
}
