"use client";

import { useMemo, useState } from "react";
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
}

export function AnswerInput({ item, onChange, locked, result }: AnswerInputProps) {
  switch (item.type) {
    case "MCQ":
      return <ChoiceInput item={item} onChange={onChange} locked={locked} result={result} single />;
    case "MULTI_SELECT":
      return <ChoiceInput item={item} onChange={onChange} locked={locked} result={result} single={false} />;
    case "TRUE_FALSE":
      return <TrueFalseInput onChange={onChange} locked={locked} result={result} />;
    case "SHORT_ANSWER":
      return <ShortAnswerInput onChange={onChange} locked={locked} />;
    case "FLASHCARD":
      return <FlashcardInput item={item} onChange={onChange} locked={locked} />;
  }
}

/* ------------------------------------------------------------------ choice */

function ChoiceInput({
  item,
  onChange,
  locked,
  result,
  single,
}: AnswerInputProps & { single: boolean }) {
  const options = (item.presentation.options as PresentedOption[] | undefined) ?? [];
  const correctCount = (item.presentation.correctCount as number | undefined) ?? undefined;
  // No reset effect: the runner gives this component a key of the question id, so React
  // unmounts and remounts it for each question and the state starts fresh by construction.
  const [selected, setSelected] = useState<string[]>([]);

  const correctIds = (result?.correctAnswer?.optionIds as string[] | undefined) ?? [];

  function toggle(id: string) {
    if (locked) return;
    const next = single
      ? [id]
      : selected.includes(id)
        ? selected.filter((value) => value !== id)
        : [...selected, id];
    setSelected(next);
    onChange(next.length > 0 ? { optionIds: next } : null);
  }

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
}: Omit<AnswerInputProps, "item">) {
  const [value, setValue] = useState<boolean | null>(null);
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

function ShortAnswerInput({ onChange, locked }: Omit<AnswerInputProps, "item" | "result">) {
  const [text, setText] = useState("");

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
