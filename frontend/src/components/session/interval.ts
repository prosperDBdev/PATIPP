/**
 * Turning an interval in days into something a person reads at a glance.
 *
 * <p>Kept out of the components because both the buttons and the post-answer line need exactly
 * the same wording, and two implementations of "how long is 0.007 days" would drift apart.
 */

/**
 * "10m", "4d", "3w", "8mo".
 *
 * <p>Deliberately coarse. A review due in 34 days is "5w", because the difference between 34 and
 * 35 days is not a decision anyone is making, and precision the schedule does not have would
 * imply an accuracy it cannot deliver.
 */
export function formatInterval(days: number): string {
  if (!Number.isFinite(days) || days <= 0) return "now";

  const minutes = days * 24 * 60;
  if (minutes < 60) return `${Math.max(1, Math.round(minutes))}m`;
  if (minutes < 60 * 20) return `${Math.round(minutes / 60)}h`;
  if (days < 14) return `${Math.round(days)}d`;
  if (days < 60) return `${Math.round(days / 7)}w`;
  if (days < 365) return `${Math.round(days / 30)}mo`;
  return `${(days / 365).toFixed(days < 730 ? 1 : 0)}y`;
}

/** The same thing in a sentence, for after the answer. */
export function describeInterval(days: number): string {
  if (!Number.isFinite(days) || days <= 0) return "coming back shortly";

  const minutes = days * 24 * 60;
  if (minutes < 60) return `back in about ${Math.max(1, Math.round(minutes))} minutes`;
  if (minutes < 60 * 20) return `back in about ${Math.round(minutes / 60)} hours`;
  if (days < 2) return "back tomorrow";
  if (days < 14) return `back in ${Math.round(days)} days`;
  if (days < 60) return `back in ${Math.round(days / 7)} weeks`;
  if (days < 365) return `back in about ${Math.round(days / 30)} months`;
  return "back in about a year";
}

/** Why a grade was chosen for the learner, when they did not choose it themselves. */
export function explainGrade(grade: string, derived: boolean): string | null {
  if (!derived) return null;

  switch (grade) {
    case "AGAIN":
      return "Marked wrong, so it comes back soon.";
    case "HARD":
      // The single most important thing to explain. A correct answer scheduled as HARD looks
      // like a bug unless the learner is told why.
      return "Right, but slowly or with reported doubt — so it is not treated as solid recall yet.";
    case "EASY":
      return "Right, and fast. Pushed out further than usual.";
    default:
      return null;
  }
}
