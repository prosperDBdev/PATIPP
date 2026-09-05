"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { Textarea, cn } from "@/components/ui";
import type { PresentedOption, ServedItem } from "@/lib/api/types";

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
  }
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
  { value: 1, label: "Again", hint: "Did not recall it" },
  { value: 2, label: "Hard", hint: "Recalled with effort" },
  { value: 3, label: "Good", hint: "Recalled it" },
  { value: 4, label: "Easy", hint: "Instant" },
] as const;

function FlashcardInput({ item, onChange, locked }: Omit<AnswerInputProps, "result">) {
  // Remounted per question via the key in the runner, so the card starts face-down again
  // without an effect resetting it.
  const [revealed, setRevealed] = useState(false);
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

      <div className="flex flex-col gap-2">
        <p className="text-[13px] font-medium text-text">How well did you recall it?</p>
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
          {GRADES.map((grade) => (
            <button
              key={grade.value}
              type="button"
              disabled={locked}
              onClick={() => onChange({ grade: grade.value })}
              className={cn(
                "rounded-lg border border-border bg-surface p-2.5 text-left transition-colors",
                "hover:border-accent disabled:cursor-default",
              )}
            >
              <span className="block text-[13px] font-medium text-text">
                <span className="mr-1.5 font-mono text-[10px] text-text-faint">
                  {grade.value}
                </span>
                {grade.label}
              </span>
              <span className="mt-0.5 block text-[11px] leading-snug text-text-faint">
                {grade.hint}
              </span>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
