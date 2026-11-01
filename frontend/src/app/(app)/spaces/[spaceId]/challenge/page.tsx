"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { Alert, Badge, Button, Card, Spinner, cn } from "@/components/ui";
import { api } from "@/lib/api/client";
import { useAuth } from "@/lib/auth/auth-context";
import { practiceStats, type ChallengeGuide } from "@/lib/api/challenge";
import type { SessionResponse } from "@/lib/api/types";

export default function ChallengePage() {
  const { spaceId } = useParams<{ spaceId: string }>();
  const router = useRouter();
  const { user } = useAuth();
  const [guide, setGuide] = useState<ChallengeGuide | null>(null);
  const [selectedDay, setSelectedDay] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api<ChallengeGuide>(`/api/v1/spaces/${spaceId}/challenge`).then((loaded) => {
      if (!cancelled) setGuide(loaded);
    }).catch((caught) => {
      if (!cancelled) setError(caught instanceof Error ? caught.message : "Could not load your guide.");
    });
    return () => { cancelled = true; };
  }, [spaceId]);

  async function start(day: number, timed = false) {
    setBusy(true); setError(null);
    try {
      const session = await api<SessionResponse>(
        `/api/v1/spaces/${spaceId}/challenge/days/${day}/start?timed=${timed}`, { method: "POST" });
      router.push(`/spaces/${spaceId}/${session.mode === "EXAM" ? "exam" : "practice"}/${session.id}`);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Could not start the exercise.");
      setBusy(false);
    }
  }

  if (!guide) return <div className="p-8">{error ? <Alert>{error}</Alert> : <Spinner />}</div>;

  const next = guide.progress.find((entry) => !entry.completed)?.day ?? 30;
  const day = guide.days[(selectedDay ?? next) - 1];
  const progress = guide.progress.find((entry) => entry.day === day.day);
  const completed = guide.progress.filter((entry) => entry.completed).length;
  const independent = guide.progress.filter((entry) => entry.independent).length;
  const stats = practiceStats(guide.activity, user?.timezone ?? "Africa/Lagos");
  const earlier = day.revisitDay ? guide.days[day.revisitDay - 1] : null;
  const recent = [...guide.activity].reverse().slice(0, 5);

  return (
    <div className="flex flex-col gap-6">
      <header className="flex flex-col gap-2">
        <Link href={`/spaces/${spaceId}`} className="text-sm text-text-muted hover:text-text">&larr; Preparation space</Link>
        <p className="font-mono text-xs text-accent uppercase">Your 30-day challenge</p>
        <h1 className="text-2xl font-semibold text-text">Build with Java and Spring Boot</h1>
        <p className="max-w-2xl text-sm text-text-muted">
          Write in your IDE, check the behaviour, then explain your decisions. On a busy day,
          log a small attempt and return to finish. Missed days never erase your work.
        </p>
      </header>

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label="Core exercises completed" value={`${completed}/30`} />
        <Stat label="Completed independently" value={`${independent}/30`} />
        <Stat label="Days with practice" value={String(stats.days)} />
        <Stat label="Current practice streak" value={`${stats.streak} ${stats.streak === 1 ? "day" : "days"}`} />
      </div>
      <p className="text-xs text-text-muted">
        {stats.practisedToday ? "You have logged practice today." : "A short coding attempt is enough to practise today."}
        {" "}Dates follow your profile timezone ({user?.timezone ?? "Africa/Lagos"}). Completion is self-reported after local checks.
      </p>
      {error && <Alert>{error}</Alert>}

      {guide.inProgress && (
        <Card className="flex flex-wrap items-center justify-between gap-3 p-4">
          <p className="text-sm text-text">You have an unfinished session. Resume it before starting another.</p>
          <Link className="text-sm font-medium text-accent hover:underline"
            href={`/spaces/${spaceId}/${guide.inProgress.mode === "EXAM" ? "exam" : "practice"}/${guide.inProgress.id}`}>
            Resume session &rarr;
          </Link>
        </Card>
      )}

      <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,1fr)_280px]">
        <Card className="flex flex-col gap-5 p-5">
          <div className="flex flex-wrap gap-2">
            <Badge tone="accent">Day {day.day}</Badge><Badge>{day.subject}</Badge>
            {progress?.completed && <Badge>Core completed</Badge>}
            {progress?.independent && <Badge>Independent</Badge>}
          </div>
          <h2 className="text-xl font-semibold text-text">{day.title}</h2>
          <section className="flex flex-col gap-2">
            <h3 className="text-sm font-semibold text-text">10–30 minute core</h3>
            <p className="text-sm whitespace-pre-wrap text-text-muted">{day.core}</p>
          </section>
          {earlier && <p className="rounded-md bg-surface-2 p-3 text-sm text-text-muted">
            Recall day {earlier.day}: {earlier.title}. Explain that idea from memory before solving this variation.
          </p>}
          <section className="flex flex-col gap-2">
            <h3 className="text-sm font-semibold text-text">Read only what you need</h3>
            <a href={day.resourceUrl} target="_blank" rel="noreferrer" className="text-sm text-accent hover:underline">{day.resourceTitle} ↗</a>
            <p className="text-sm text-text-muted">{day.readingFocus}</p>
            <p className="text-xs text-text-faint">Try recalling first. Keep reference solutions closed while you attempt the task.</p>
          </section>
          <section className="flex flex-col gap-2">
            <h3 className="text-sm font-semibold text-text">Expected behaviour</h3>
            <ul className="list-disc space-y-2 pl-5 text-sm text-text-muted">{day.checks.map((check) => <li key={check}>{check}</li>)}</ul>
            <p className="text-xs text-text-faint">Run these checks in your IDE or HTTP client. PATIPP records your assessment; it does not run your code.</p>
          </section>
          <details className="rounded-md border border-border p-3 text-sm text-text-muted">
            <summary className="cursor-pointer font-medium text-text">Have 1–2 hours? Extend the exercise</summary>
            <p className="mt-3">{day.stretch}</p>
            <p className="mt-2">Add tests, narrate the request path, and rebuild one part from a blank file.</p>
          </details>
          <div className="flex flex-wrap gap-3">
            <Button onClick={() => start(day.day)} loading={busy} disabled={Boolean(guide.inProgress) || !progress?.questionId}>
              {progress?.attempts ? "Practise again" : "Attempt in my IDE"}
            </Button>
            {day.timedMinutes && <Button variant="secondary" disabled={Boolean(guide.inProgress) || busy}
              onClick={() => start(day.day, true)}>Timed rehearsal · {day.timedMinutes} min</Button>}
          </div>
          {day.timedMinutes && <p className="text-xs text-text-muted">Choose timed rehearsal after you understand the concept. Hints and solutions stay unavailable until it ends.</p>}
          {!progress?.questionId && <Alert>This exercise is missing. Return to Spaces and start the challenge again to restore missing exercises.</Alert>}
          {completed === 30 && <p className="text-sm text-success">All 30 cores are complete. Revisit assisted tasks and try rebuilding them independently.</p>}
        </Card>

        <aside className="flex flex-col gap-4">
          <Card className="p-4">
            <h2 className="mb-3 text-sm font-semibold text-text">Your path · choose any day</h2>
            <ol className="flex max-h-[650px] flex-col gap-1 overflow-auto">
              {guide.days.map((entry, index) => <li key={entry.day}>
                <button onClick={() => setSelectedDay(entry.day)} aria-current={entry.day === day.day ? "step" : undefined}
                  className={cn("flex w-full gap-2 rounded p-2 text-left text-xs hover:bg-surface-2",
                    entry.day === day.day ? "bg-accent-soft text-accent" : "text-text-muted")}>
                  <span className="shrink-0 font-mono">{guide.progress[index]?.completed ? "✓" : entry.day.toString().padStart(2, "0")}</span>
                  <span>{entry.title}</span>
                </button>
              </li>)}
            </ol>
          </Card>
          <Card className="flex flex-col gap-2 p-4 text-sm text-text-muted">
            <h2 className="font-semibold text-text">A routine that fits today</h2>
            <p>Recall → write → check → explain → log.</p>
            <p>Conceptual help counts as hints. Copied or generated code counts as solution help. Documentation lookup is normal.</p>
            <p>Thirty days builds evidence of fluency. Use the independent rebuilds to decide what still needs work.</p>
          </Card>
        </aside>
      </div>

      {recent.length > 0 && <section className="flex flex-col gap-3">
        <h2 className="text-base font-semibold text-text">Recent practice</h2>
        {recent.map((entry, index) => <Card key={`${entry.at}-${index}`} className="flex flex-col gap-1 p-4 text-sm">
          <p className="text-text">Day {entry.day} · {entry.completed ? "Core completed" : "Practised; core unfinished"} · {entry.assistance.toLowerCase()}</p>
          <p className="text-xs text-text-faint">{new Date(entry.at).toLocaleString(undefined, { timeZone: user?.timezone ?? "Africa/Lagos" })}</p>
          {entry.reflection && <p className="whitespace-pre-wrap text-text-muted">{entry.reflection}</p>}
        </Card>)}
      </section>}
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return <Card className="flex flex-col gap-1 p-4"><span className="text-xs text-text-muted">{label}</span><span className="text-xl font-semibold text-text">{value}</span></Card>;
}
