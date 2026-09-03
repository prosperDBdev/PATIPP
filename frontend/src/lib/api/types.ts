/** Mirrors the backend DTOs in com.patipp.*.api. */

export interface UserSummary {
  id: string;
  email: string;
  displayName: string;
  timezone: string;
}

export interface AuthResponse {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  user: UserSummary;
}

export interface Profile extends UserSummary {
  settings: Record<string, unknown>;
  createdAt: string;
  lastActiveAt: string | null;
}

/**
 * The declarative configuration that makes a preparation type a row rather than a
 * subclass. The frontend reads it to decide which question types and session modes to
 * offer, so adding a new type on the server needs no frontend change.
 */
export interface Blueprint {
  allowedQuestionTypes?: string[];
  sessionModes?: string[];
  scoringPolicy?: string;
  difficultyPolicy?: string;
  schedulerPolicy?: string;
  difficultyLadder?: string[];
  targetSuccessRate?: number;
  readinessWeights?: Record<string, number>;
  evaluationCriteria?: string[];
  defaults?: Record<string, unknown>;
  [key: string]: unknown;
}

export interface PreparationType {
  id: string;
  key: string;
  name: string;
  description: string | null;
  icon: string | null;
  system: boolean;
  blueprint: Blueprint;
}

export type SpaceStatus = "ACTIVE" | "PAUSED" | "COMPLETED" | "ARCHIVED";

export interface Space {
  id: string;
  name: string;
  description: string | null;
  status: SpaceStatus;
  targetDate: string | null;
  targetScore: number | null;
  daysUntilTarget: number | null;
  preparationType: PreparationType;
  config: Record<string, unknown>;
  /** The type blueprint with this space's config merged over it. Read this, not blueprint. */
  effectiveSettings: Blueprint;
  createdAt: string;
  updatedAt: string;
  archivedAt: string | null;
}

export interface Topic {
  id: string;
  subjectId: string;
  parentTopicId: string | null;
  name: string;
  description: string | null;
  position: number;
  weight: number;
  depth: number;
  path: string;
  children: Topic[];
}

export interface Subject {
  id: string;
  name: string;
  description: string | null;
  color: string | null;
  position: number;
  weight: number;
  createdAt: string;
  topics: Topic[];
}

/** RFC 7807 problem detail, as returned by GlobalExceptionHandler. */
export interface ProblemDetail {
  type: string;
  title: string;
  status: number;
  detail: string;
  code?: string;
  correlationId?: string;
  errors?: { field: string; message: string }[];
}

/* ------------------------------------------------------------------ questions */

export type QuestionTypeKey =
  | "MCQ"
  | "MULTI_SELECT"
  | "TRUE_FALSE"
  | "SHORT_ANSWER"
  | "FLASHCARD";

export type DifficultyKey = "EASY" | "MEDIUM" | "HARD" | "EXPERT";
export type QuestionStatusKey = "DRAFT" | "ACTIVE" | "ARCHIVED";

export interface QuestionOption {
  id: string;
  text: string;
  correct: boolean;
}

/**
 * The format-specific body. Untyped on purpose: the server owns the rules and returns
 * precise field errors, so mirroring that validation here would give two sources of truth
 * that drift apart.
 */
export type QuestionPayload = Record<string, unknown>;

export interface QuestionSummary {
  id: string;
  subjectId: string;
  topicId: string | null;
  type: QuestionTypeKey;
  difficulty: DifficultyKey;
  status: QuestionStatusKey;
  tags: string[];
  stem: string;
  estimatedSeconds: number;
  createdAt: string;
}

export interface Question extends Omit<QuestionSummary, "stem"> {
  source: string;
  version: number;
  stem: string;
  explanation: string | null;
  hints: string[];
  payload: QuestionPayload;
  updatedAt: string;
}

export interface QuestionPage {
  items: QuestionSummary[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface VersionSummary {
  id: string;
  version: number;
  stem: string;
  createdAt: string;
  current: boolean;
}

/* ------------------------------------------------------------------ import */

export type ImportOutcome = "VALID" | "IMPORTED" | "DUPLICATE" | "INVALID";

export interface FieldProblem {
  field: string;
  message: string;
}

export interface ImportRow {
  line: number;
  outcome: ImportOutcome;
  stem: string;
  type: string;
  problems: FieldProblem[];
  questionId: string | null;
}

export interface ImportReport {
  dryRun: boolean;
  totalRows: number;
  valid: number;
  duplicates: number;
  invalid: number;
  imported: number;
  rows: ImportRow[];
}

/* ------------------------------------------------------------------ topic suggestions */

export interface SuggestedTopic {
  name: string;
  description: string | null;
}

/**
 * @param matched       false when the catalogue does not know this subject — an ordinary
 *                      outcome, not an error. The user types their own topics as before.
 * @param matchedSubject the catalogue's spelling, which differs from what the user typed
 *                       when they used an alias like "js" or "react-native"
 */
export interface TopicSuggestions {
  matched: boolean;
  matchedSubject: string | null;
  source: string;
  topics: SuggestedTopic[];
}
