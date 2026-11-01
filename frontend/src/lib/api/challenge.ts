import type { SessionListEntry } from "./types";

export interface ChallengeDay {
  day: number;
  title: string;
  subject: string;
  topic: string;
  core: string;
  stretch: string;
  checks: string[];
  resourceTitle: string;
  resourceUrl: string;
  readingFocus: string;
  revisitDay: number | null;
  timedMinutes: number | null;
}

export interface ChallengeProgress {
  day: number;
  questionId: string | null;
  completed: boolean;
  independent: boolean;
  assistance: string | null;
  attempts: number;
}

export interface ChallengeActivity {
  day: number;
  at: string;
  completed: boolean;
  assistance: string;
  reflection: string;
}

export interface ChallengeGuide {
  key: string;
  days: ChallengeDay[];
  progress: ChallengeProgress[];
  activity: ChallengeActivity[];
  inProgress: SessionListEntry | null;
}

/** Profile timezone, not UTC: a late evening practice belongs to the learner's day. */
export function practiceDate(instant: string | Date, timezone: string): string {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: timezone, year: "numeric", month: "2-digit", day: "2-digit",
  }).formatToParts(new Date(instant));
  const part = (type: string) => parts.find((entry) => entry.type === type)?.value;
  return `${part("year")}-${part("month")}-${part("day")}`;
}

export function practiceStats(activity: ChallengeActivity[], timezone: string, now = new Date()) {
  const dates = new Set(activity.map((entry) => practiceDate(entry.at, timezone)));
  const today = practiceDate(now, timezone);
  const previousDay = (date: string) => {
    const value = new Date(`${date}T12:00:00Z`);
    value.setUTCDate(value.getUTCDate() - 1);
    return value.toISOString().slice(0, 10);
  };
  let cursor = dates.has(today) ? today : previousDay(today);
  let streak = 0;
  while (dates.has(cursor)) { streak++; cursor = previousDay(cursor); }
  return { days: dates.size, streak, practisedToday: dates.has(today) };
}
