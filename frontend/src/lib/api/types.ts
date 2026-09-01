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
