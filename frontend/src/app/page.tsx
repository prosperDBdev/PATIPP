"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { Card, cn } from "@/components/ui";
import { ThemeToggle } from "@/components/theme-toggle";
import { useAuth } from "@/lib/auth/auth-context";

/**
 * The public front page.
 *
 * <p>Everything else in the application lives behind authentication, which meant a first-time
 * visitor got a spinner and then a login form — a password prompt for a product they have not
 * been told anything about. This page exists to answer "what is this and why would I use it?"
 * before asking for an account.
 *
 * <p>Signed-in visitors are sent straight to their spaces, so the marketing copy is never in the
 * way of someone who already knows.
 */
export default function LandingPage() {
  const { user, loading } = useAuth();
  const router = useRouter();

  useEffect(() => {
    // Only once the bootstrap refresh has settled. Redirecting while `loading` is still true
    // would bounce a signed-out visitor the moment their cookie exchange failed.
    if (!loading && user) {
      router.replace("/spaces");
    }
  }, [loading, user, router]);

  return (
    <div className="flex min-h-dvh flex-col">
      <header className="sticky top-0 z-20 border-b border-border bg-bg/85 backdrop-blur">
        <div className="mx-auto flex h-14 max-w-5xl items-center gap-4 px-4 sm:px-6">
          <span className="font-mono text-xs tracking-[0.18em] text-text uppercase">PATIPP</span>
          <div className="ml-auto flex items-center gap-2">
            <ThemeToggle />
            {/* Rendered only once auth has settled, so a signed-in visitor never sees
                "Sign in" flash before being redirected. */}
            {!loading && !user && (
              <>
                <Link
                  href="/login"
                  className="rounded-md px-3 py-1.5 text-[13px] font-medium text-text-muted transition-colors hover:text-text"
                >
                  Sign in
                </Link>
                <Link
                  href="/register"
                  className="inline-flex h-9 items-center rounded-md bg-accent px-3 text-[13px] font-medium text-accent-fg transition-colors hover:bg-accent-hover"
                >
                  Get started
                </Link>
              </>
            )}
          </div>
        </div>
      </header>

      <main className="mx-auto w-full max-w-5xl flex-1 px-4 sm:px-6">
        {/* ---------------------------------------------------------------- hero */}
        <section className="flex flex-col items-start gap-6 py-16 sm:py-24">
          <span className="rounded-full border border-border bg-surface px-3 py-1 font-mono text-[11px] tracking-wide text-text-muted">
            Personalized Adaptive Test &amp; Interview Preparation
          </span>

          <h1 className="max-w-3xl text-4xl leading-[1.1] font-semibold tracking-tight text-text sm:text-5xl">
            Preparation that adapts to what you are actually weak at.
          </h1>

          <p className="max-w-2xl text-base leading-relaxed text-text-muted sm:text-lg">
            PATIPP learns what you know from how you answer, serves you the questions that will
            teach you most, and tells you when to come back to them. For a semester exam, a
            technical interview, a certification, a coding test — the same engine, configured
            differently.
          </p>

          <div className="flex flex-wrap items-center gap-3 pt-2">
            <Link
              href="/register"
              className="inline-flex h-11 items-center rounded-md bg-accent px-5 text-sm font-medium text-accent-fg transition-colors hover:bg-accent-hover"
            >
              Create an account
            </Link>
            <Link
              href="/login"
              className="inline-flex h-11 items-center rounded-md border border-border bg-surface px-5 text-sm font-medium text-text transition-colors hover:border-border-strong"
            >
              Sign in
            </Link>
          </div>
        </section>

        {/* ------------------------------------------------------- the central idea */}
        <section className="border-t border-border py-14">
          <div className="grid gap-8 lg:grid-cols-[1fr_1.3fr] lg:gap-14">
            <div className="flex flex-col gap-3">
              <span className="font-mono text-[10px] tracking-wider text-text-faint uppercase">
                The idea
              </span>
              <h2 className="text-2xl font-semibold tracking-tight text-text">
                An exam is a mode, not the product.
              </h2>
            </div>

            <div className="flex flex-col gap-4 text-[15px] leading-relaxed text-text-muted">
              <p>
                Most study apps are quiz apps with features bolted on. PATIPP is built the other
                way round: one preparation engine, with an exam as one way of running a session
                alongside practice, review and interviews.
              </p>
              <p>
                You work inside a{" "}
                <strong className="font-medium text-text">preparation space</strong> — a semester,
                an interview, a certification — each with its own material, history and progress.
                A space is configuration, not a different app, which is why the same adaptive
                selection and the same readiness score work for all of them.
              </p>
            </div>
          </div>
        </section>

        {/* ------------------------------------------------------------- capabilities */}
        <section className="border-t border-border py-14">
          <h2 className="mb-8 text-2xl font-semibold tracking-tight text-text">
            What it actually does
          </h2>

          <div className="grid gap-4 sm:grid-cols-2">
            {CAPABILITIES.map((capability) => (
              <Card key={capability.title} className="flex flex-col gap-2 p-5">
                <span
                  aria-hidden="true"
                  className={cn("size-1.5 rounded-full", capability.tone)}
                />
                <h3 className="text-[15px] font-semibold text-text">{capability.title}</h3>
                <p className="text-[13px] leading-relaxed text-text-muted">{capability.body}</p>
              </Card>
            ))}
          </div>
        </section>

        {/* --------------------------------------------------------------- principles */}
        <section className="border-t border-border py-14">
          <div className="grid gap-8 lg:grid-cols-[1fr_1.3fr] lg:gap-14">
            <div className="flex flex-col gap-3">
              <span className="font-mono text-[10px] tracking-wider text-text-faint uppercase">
                How it behaves
              </span>
              <h2 className="text-2xl font-semibold tracking-tight text-text">
                Honest by design.
              </h2>
            </div>

            <ul className="flex flex-col gap-5">
              {PRINCIPLES.map((principle) => (
                <li key={principle.title} className="flex flex-col gap-1">
                  <h3 className="text-[15px] font-medium text-text">{principle.title}</h3>
                  <p className="text-[13px] leading-relaxed text-text-muted">{principle.body}</p>
                </li>
              ))}
            </ul>
          </div>
        </section>

        {/* -------------------------------------------------------------- closing cta */}
        <section className="border-t border-border py-16">
          <Card className="flex flex-col items-start gap-4 p-8">
            <h2 className="text-2xl font-semibold tracking-tight text-text">
              Start with one space.
            </h2>
            <p className="max-w-xl text-[15px] leading-relaxed text-text-muted">
              Add a subject, import or write some questions, and answer a dozen. That is enough
              for the engine to stop guessing and start adapting — and it will tell you plainly
              when it gets there.
            </p>
            <Link
              href="/register"
              className="mt-1 inline-flex h-11 items-center rounded-md bg-accent px-5 text-sm font-medium text-accent-fg transition-colors hover:bg-accent-hover"
            >
              Create an account
            </Link>
          </Card>
        </section>
      </main>

      <footer className="border-t border-border">
        <div className="mx-auto flex max-w-5xl flex-wrap items-center gap-x-4 gap-y-2 px-4 py-6 text-xs text-text-faint sm:px-6">
          <span className="font-mono tracking-[0.18em] uppercase">PATIPP</span>
          <span>Personalized Adaptive Test &amp; Interview Preparation Platform</span>
          <Link
            href="/login"
            className="ml-auto text-text-muted transition-colors hover:text-text"
          >
            Sign in
          </Link>
        </div>
      </footer>
    </div>
  );
}

/**
 * Written as what the feature does for the reader, not as a feature list. Each one names the
 * specific behaviour rather than the category, because "spaced repetition" means nothing to
 * someone who has not met it and "decides when to show it again" means something immediately.
 */
const CAPABILITIES = [
  {
    title: "It finds your weak areas and serves them",
    tone: "bg-danger",
    body:
      "An ability estimate per topic, updated after every answer, and a difficulty rating per "
      + "question that updates alongside it. Questions are picked to sit at roughly a 78% chance "
      + "of success — hard enough to be worth doing, not so hard you stop.",
  },
  {
    title: "It decides when to show something again",
    tone: "bg-flag",
    body:
      "Every answer schedules the next sighting of that question, timed for just before you "
      + "would have forgotten it. Each grade button shows the interval it would set, so you can "
      + "see what your answer costs before you give it.",
  },
  {
    title: "Mock exams that stay comparable",
    tone: "bg-accent",
    body:
      "Timed, answered in any order, marked only at the end, and weighted towards the subjects "
      + "that carry the most marks. Deliberately not adaptive — a mock that got easier when you "
      + "struggled could not be compared with the one you sat a fortnight ago.",
  },
  {
    title: "A readiness score that explains itself",
    tone: "bg-success",
    body:
      "Six components — coverage, accuracy, depth, retention, consistency and mock performance — "
      + "each shown with the weight applied to it, plus the single most valuable thing you could "
      + "do next and roughly what it is worth.",
  },
];

const PRINCIPLES = [
  {
    title: "It will not call you weak at something on two answers",
    body:
      "A topic with fewer than five attempts is reported as unassessed, never as a weakness. A "
      + "study plan built on noise is worse than no plan, because the first time it is obviously "
      + "wrong you stop believing the rest of it.",
  },
  {
    title: "Readiness is not your average quiz score",
    body:
      "An average rises when you answer easy questions about things you already know. Readiness "
      + "is scaled by how much evidence exists, so after twelve questions it says it is still "
      + "calibrating rather than claiming you are 88% ready.",
  },
  {
    title: "Guardrails, not just difficulty",
    body:
      "No single topic takes over a session, a winnable question arrives at least every six, and "
      + "after a bad run it eases off and works back up. A system that always pushes you to the "
      + "edge of failure is one you stop opening.",
  },
  {
    title: "Your questions stay yours",
    body:
      "Import from CSV or JSON, export the same shape back out, with subjects written by name "
      + "rather than by id. Nothing you write is trapped here.",
  },
];
