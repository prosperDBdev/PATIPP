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
  | "FLASHCARD"
  | "CODING"
  | "DEBUGGING"
  | "OUTPUT_PREDICTION";

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

/* ------------------------------------------------------------------ sessions */

export type SessionModeKey = "PRACTICE" | "EXAM" | "INTERVIEW" | "FLASHCARD_REVIEW" | "DRILL";
export type SessionStatusKey = "IN_PROGRESS" | "SUBMITTED" | "ABANDONED" | "EXPIRED";

/**
 * The question as served. `presentation` is deliberately not the stored payload: the answer
 * key stays on the server, so there is nothing here to read out of the page.
 */
export interface ServedItem {
  position: number;
  questionId: string;
  type: QuestionTypeKey;
  difficulty: DifficultyKey;
  subjectId: string;
  topicId: string | null;
  estimatedSeconds: number;
  stem: string;
  hints: string[];
  presentation: Record<string, unknown>;
  /**
   * Why the engine chose this question. Recorded when the session was built, so it is the
   * actual reason rather than one reconstructed afterwards.
   */
  selectionReason: SelectionReason;
}

/**
 * @param reason machine key: WEAK_TOPIC, COVERAGE_GAP, DIFFICULTY_FIT, RECOVERY, DUE_REVIEW,
 *               CALIBRATION, or RANDOM / BLUEPRINT_WEIGHTED for the non-adaptive modes
 * @param why    the same thing in a sentence, safe to show — it explains the choice, never
 *               the answer
 */
export interface SelectionReason {
  reason?: string;
  why?: string;
  score?: number;
  expectation?: number;
  engine?: string;
  components?: {
    due?: number;
    weakness?: number;
    difficultyFit?: number;
    coverageGap?: number;
    freshness?: number;
    expectation?: number;
    targetRating?: number;
    itemRating?: number;
  };
}

/* ------------------------------------------------------------------ adaptive engine */

export type MasteryLevelKey =
  | "UNTOUCHED"
  | "UNASSESSED"
  | "WEAK"
  | "DEVELOPING"
  | "PROFICIENT"
  | "STRONG";

export interface Weakness {
  subjectId: string;
  subjectName: string;
  topicId: string | null;
  topicName: string | null;
  score: number;
  recentAccuracy: number;
  attempts: number;
  level: MasteryLevelKey;
}

/**
 * @param needsAssessment topics touched too little to judge. Deliberately separate from
 *                        `weakest`: "you have not measured this" is a different instruction
 *                        from "you are bad at this", and merging them would rank a topic
 *                        with two attempts beside one with sixty.
 * @param note            present only while the engine is admitting it does not know yet
 */
export interface Focus {
  weakest: Weakness[];
  needsAssessment: Weakness[];
  calibrating: boolean;
  totalAttempts: number;
  ability: number;
  note: string | null;
}

export interface TopicMastery {
  subjectId: string;
  subjectName: string;
  topicId: string | null;
  topicName: string | null;
  ability: number;
  accuracy: number;
  recentAccuracy: number;
  attempts: number;
  correct: number;
  questionsSeen: number;
  level: MasteryLevelKey;
  lastPracticedAt: string | null;
}

/** How a question stands in the navigation grid. Never whether it was correct. */
export type SessionItemStateKey =
  | "UNSEEN"
  | "VIEWED"
  | "ANSWERED"
  | "MARKED_FOR_REVIEW"
  | "SKIPPED";

export interface ItemSummary {
  position: number;
  state: SessionItemStateKey;
  answered: boolean;
}

export interface SessionResponse {
  id: string;
  mode: SessionModeKey;
  status: SessionStatusKey;
  startedAt: string;
  deadlineAt: string | null;
  submittedAt: string | null;
  totalItems: number;
  answeredCount: number;
  correctCount: number;
  activeMs: number;
  immediateFeedback: boolean;
  /** True for exams: questions may be visited in any order and revisited. */
  freeNavigation: boolean;
  /**
   * Milliseconds left, computed by the server from its own clock. Null when the session is
   * untimed. The countdown on screen is drawn from this, but the server decides when time is
   * actually up — a clock the browser owns is a clock the browser can change.
   */
  remainingMs: number | null;
  items: ItemSummary[];
  currentItem: ServedItem | null;
}

export interface AnswerResult {
  position: number;
  /** Null when the mode defers feedback — an exam withholds the verdict, not just the reason. */
  correct: boolean | null;
  score: number | null;
  note: string | null;
  explanation: string | null;
  correctAnswer: Record<string, unknown> | null;
  answeredCount: number;
  totalItems: number;
  sessionComplete: boolean;
  /** When this question comes back, and the grade that decided it. Always present. */
  review: ReviewOutcome | null;
  nextItem: ServedItem | null;
}

/**
 * @param derived true when the platform inferred the grade from correctness and timing rather
 *                than being told it. Shown, because a correct-but-slow answer scheduled as HARD
 *                is otherwise inexplicable.
 */
export interface ReviewOutcome {
  grade: "AGAIN" | "HARD" | "GOOD" | "EASY";
  derived: boolean;
  intervalDays: number;
  dueAt: string | null;
  phase: "NEW" | "LEARNING" | "REVIEW" | "RELEARNING" | "SUSPENDED";
  priority: "HIGH" | "MEDIUM" | "LOW" | "SUSPENDED";
}

/** What each answer would schedule, keyed by grade. Days. */
export type IntervalPreview = Partial<Record<ReviewOutcome["grade"], number>>;

export interface ReviewDebt {
  due: number;
  struggling: number;
  tracked: number;
  summary: string;
}

export interface ReviewItem {
  position: number;
  questionId: string;
  type: QuestionTypeKey;
  difficulty: DifficultyKey;
  stem: string;
  correct: boolean;
  score: number;
  note: string | null;
  explanation: string | null;
  yourAnswer: Record<string, unknown> | null;
  correctAnswer: Record<string, unknown> | null;
  timeSpentMs: number;
  selectionReason: Record<string, unknown>;
}

export interface SessionSummary {
  id: string;
  mode: SessionModeKey;
  status: SessionStatusKey;
  startedAt: string;
  submittedAt: string | null;
  activeMs: number;
  totalItems: number;
  answeredCount: number;
  correctCount: number;
  score: number | null;
  breakdown: Record<string, unknown> | null;
  items: ReviewItem[];
}

export interface SessionListEntry {
  id: string;
  mode: SessionModeKey;
  status: SessionStatusKey;
  startedAt: string;
  submittedAt: string | null;
  totalItems: number;
  answeredCount: number;
  correctCount: number;
  score: number | null;
}

export interface SessionAvailability {
  availableQuestions: number;
  suggestedLength: number;
}

/** A saved exam setup, so a mock you sit repeatedly is not rebuilt by hand each time. */
export interface ExamTemplate {
  id: string;
  name: string;
  length: number | null;
  durationMinutes: number | null;
  subjectIds: string[];
  types: QuestionTypeKey[];
  difficulties: DifficultyKey[];
  timesUsed: number;
  lastUsedAt: string | null;
  createdAt: string;
}

/** An option as shown to the learner: no correctness flag. */
export interface PresentedOption {
  id: string;
  text: string;
}

/* ------------------------------------------------------------------ readiness */

export type ConfidenceBandKey = "CALIBRATING" | "LOW" | "MODERATE" | "HIGH";

export type ReadinessComponentKey =
  | "coverage"
  | "accuracy"
  | "depth"
  | "retention"
  | "consistency"
  | "mock";

/**
 * @param raw     before the confidence factor. The gap between this and `score` is the
 *                explanation for an early figure that looks unfairly low, which is why both
 *                are sent rather than just the one.
 * @param weights what each component was multiplied by, so the score can be checked by hand.
 * @param drivers the changes worth reading since the last snapshot, largest first.
 */
export interface Readiness {
  score: number;
  raw: number;
  confidence: number;
  confidenceBand: ConfidenceBandKey;
  headline: string;
  components: Record<ReadinessComponentKey, number>;
  weights: Record<ReadinessComponentKey, number>;
  deltas: Partial<Record<ReadinessComponentKey | "total", number>>;
  drivers: string[];
  biggestLever: {
    component: ReadinessComponentKey;
    action: string;
    estimatedGain: number;
  } | null;
  streak: Streak;
  modelVersion: string;
}

/** @param answeredToday false while today is still open, so a streak is not shown as broken early */
export interface Streak {
  current: number;
  longest: number;
  answeredToday: boolean;
}

export interface ReadinessPoint {
  on: string;
  score: number;
  confidenceBand: ConfidenceBandKey;
  components: Record<string, number>;
}

export interface ActivityDay {
  on: string;
  answered: number;
  correct: number;
  studyMinutes: number;
  sessions: number;
}

export interface Activity {
  from: string;
  to: string;
  streak: Streak;
  days: ActivityDay[];
}
