/** Display names for the question vocabularies, kept out of page files so both can share them. */

const TYPE_LABELS: Record<string, string> = {
  MCQ: "Multiple choice",
  MULTI_SELECT: "Multiple select",
  TRUE_FALSE: "True / false",
  SHORT_ANSWER: "Short answer",
  FLASHCARD: "Flashcard",
  CODING: "Coding problem",
  DEBUGGING: "Find the bug",
  OUTPUT_PREDICTION: "What does it print?",
};

/** Formats the learner grades themselves, which the UI has to say plainly up front. */
export const SELF_GRADED_TYPES = new Set(["CODING", "DEBUGGING"]);

/** Formats whose body is a code snippet, so the editor offers a monospace field. */
export const CODE_TYPES = new Set(["CODING", "DEBUGGING", "OUTPUT_PREDICTION"]);

export function humanType(type: string): string {
  return TYPE_LABELS[type] ?? type;
}

/** EASY -> Easy. The API speaks in constants; a person should not have to. */
export function title(value: string): string {
  return value.charAt(0) + value.slice(1).toLowerCase();
}
