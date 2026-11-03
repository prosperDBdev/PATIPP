"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { Alert, Button } from "@/components/ui";
import { Glyph } from "@/components/brand";
import { api } from "@/lib/api/client";
import type { ChallengeGuide } from "@/lib/api/challenge";

export function ChallengeInvite({ spaceId }: { spaceId?: string }) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [guide, setGuide] = useState<ChallengeGuide | null>(null);

  useEffect(() => {
    if (!spaceId) return;
    let cancelled = false;
    api<ChallengeGuide>(`/api/v1/spaces/${spaceId}/challenge`).then((loaded) => {
      if (!cancelled) setGuide(loaded);
    }).catch(() => { /* The guide link remains available if its preview cannot load. */ });
    return () => { cancelled = true; };
  }, [spaceId]);
  const next = guide?.days.find((day) => !guide.progress.find((entry) => entry.day === day.day)?.completed);

  async function enroll() {
    setBusy(true); setError(null);
    try {
      const result = await api<{ spaceId: string }>("/api/v1/challenges/java-spring/enroll", { method: "POST" });
      router.push(`/spaces/${result.spaceId}/challenge`);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Could not start the challenge. Please retry.");
      setBusy(false);
    }
  }

  return (
    <section className="ink-panel grid gap-8 p-6 sm:p-9 lg:grid-cols-[1.2fr_1fr]">
      <div className="flex flex-col items-start gap-4">
      <p className="eyebrow text-[#dca149]">The Java + Spring Boot challenge</p>
      <h2 className="display-title max-w-md text-4xl sm:text-5xl">Build a skill.<br />Then make it yours.</h2>
      <p className="max-w-md text-sm leading-relaxed text-[#b2c2bc]">
        One guided exercise at a time. Spend 10–30 minutes on the core, or go deeper for
        1–2 hours. Resources, progressive hints and honest progress tracking are included.
      </p>
      {error && <Alert>{error}</Alert>}
      {spaceId ? (
        <Link href={`/spaces/${spaceId}/challenge`} className="gold-button">
          Continue your challenge &rarr;
        </Link>
      ) : (
        <Button className="gold-button" onClick={enroll} loading={busy}>Start the 30-day challenge</Button>
      )}
      </div>
      <div className="flex flex-col justify-between rounded-2xl border border-white/15 bg-white/5 p-5 sm:p-6">
        <div className="flex items-center justify-between"><p className="eyebrow text-[#b2c2bc]">{next ? `Up next · day ${next.day}` : guide ? "Your next chapter" : "Your starting point"}</p><Glyph kind="code" className="size-6 text-[#dca149]" /></div>
        <h3 className="my-6 text-2xl font-medium tracking-tight">{next?.title ?? (guide ? "Return. Rebuild. Repeat." : "From Java foundations to your own book API.")}</h3>
        <div className="flex flex-wrap gap-2 border-t border-white/15 pt-4 text-xs text-[#b2c2bc]"><span>30 guided exercises</span><span aria-hidden="true">/</span><span>10–30 min core</span><span aria-hidden="true">/</span><span>Your IDE</span></div>
        <p className="mt-3 text-xs text-[#b2c2bc]">{guide ? `${guide.progress.filter((day) => day.completed).length} completed · ${guide.progress.filter((day) => day.independent).length} independent` : "Start when you can. Missed days never erase your work."}</p>
      </div>
    </section>
  );
}
