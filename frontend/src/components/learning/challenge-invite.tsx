"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { Alert, Button, Card } from "@/components/ui";
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
    <Card className="flex flex-col gap-3 border-accent/40 p-5">
      <p className="font-mono text-xs tracking-wider text-accent uppercase">30 days · Java + Spring Boot</p>
      <h2 className="text-xl font-semibold text-text">Write it. Test it. Explain it. Rebuild it.</h2>
      <p className="max-w-2xl text-sm text-text-muted">
        One guided exercise at a time. Spend 10–30 minutes on the core, or go deeper for
        1–2 hours. Resources, progressive hints and honest progress tracking are included.
      </p>
      {error && <Alert>{error}</Alert>}
      {next && <p className="text-sm font-medium text-text">Next: day {next.day} · {next.title}</p>}
      {guide && !next && <p className="text-sm text-success">All 30 cores complete. Rebuild an assisted task independently next.</p>}
      {spaceId ? (
        <Link href={`/spaces/${spaceId}/challenge`} className="text-sm font-medium text-accent hover:underline">
          Continue your challenge &rarr;
        </Link>
      ) : (
        <Button className="self-start" onClick={enroll} loading={busy}>Start the 30-day challenge</Button>
      )}
    </Card>
  );
}
