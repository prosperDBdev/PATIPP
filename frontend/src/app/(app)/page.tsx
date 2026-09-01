"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Alert, Badge, Card, EmptyState, Spinner } from "@/components/ui";
import { api, ApiError } from "@/lib/api/client";
import type { Space } from "@/lib/api/types";
import { useAuth } from "@/lib/auth/auth-context";

export default function SpacesPage() {
  const { user } = useAuth();
  const [spaces, setSpaces] = useState<Space[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // The cancelled flag stops a late response from setting state on an unmounted
    // component, which is what happens when a user navigates away mid-request.
    let cancelled = false;

    (async () => {
      try {
        const loaded = await api<Space[]>("/api/v1/spaces");
        if (!cancelled) setSpaces(loaded);
      } catch (caught) {
        if (!cancelled) {
          setError(caught instanceof ApiError ? caught.message : "Could not load your spaces.");
        }
      }
    })();

    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <div className="flex flex-col gap-8">
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex flex-col gap-1">
          <h1 className="text-2xl font-semibold tracking-tight text-text">
            {greeting()}, {user?.displayName.split(" ")[0]}
          </h1>
          <p className="text-sm text-text-muted">
            Each space is a self-contained world with its own material and its own progress.
          </p>
        </div>
        <Link
          href="/spaces/new"
          className="inline-flex h-10 items-center rounded-md bg-accent px-4 text-sm font-medium text-accent-fg shadow-sm transition-colors hover:bg-accent-hover"
        >
          New space
        </Link>
      </header>

      {error && <Alert>{error}</Alert>}

      {spaces === null && !error ? (
        <div className="grid place-items-center py-20">
          <Spinner className="size-5 text-text-faint" />
        </div>
      ) : spaces && spaces.length === 0 ? (
        <EmptyState
          title="No preparation spaces yet"
          description="Create one for what you are preparing for right now - a semester exam, an interview, a certification. You can add more later, and they stay completely independent."
          action={
            <Link
              href="/spaces/new"
              className="mt-1 rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-fg hover:bg-accent-hover"
            >
              Create your first space
            </Link>
          }
        />
      ) : (
        <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {spaces?.map((space) => (
            <li key={space.id}>
              <SpaceCard space={space} />
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function SpaceCard({ space }: { space: Space }) {
  return (
    <Link href={`/spaces/${space.id}`} className="block h-full">
      <Card className="flex h-full flex-col gap-3 p-5 transition-colors hover:border-border-strong">
        <div className="flex items-start justify-between gap-3">
          <h2 className="text-base leading-snug font-semibold text-text">{space.name}</h2>
          {space.status !== "ACTIVE" && <Badge>{space.status}</Badge>}
        </div>

        <Badge tone="accent">{space.preparationType.name}</Badge>

        {space.description && (
          <p className="line-clamp-2 text-sm text-text-muted">{space.description}</p>
        )}

        <div className="mt-auto flex flex-wrap items-center gap-x-4 gap-y-1 pt-2 text-xs text-text-faint">
          {space.targetDate && (
            <span className="font-mono tabular-nums">
              {formatCountdown(space.daysUntilTarget)}
            </span>
          )}
          {space.targetScore !== null && (
            <span className="font-mono tabular-nums">target {space.targetScore}%</span>
          )}
        </div>
      </Card>
    </Link>
  );
}

function formatCountdown(days: number | null): string {
  if (days === null) return "";
  if (days < 0) return "date passed";
  if (days === 0) return "today";
  if (days === 1) return "1 day left";
  return `${days} days left`;
}

function greeting(): string {
  const hour = new Date().getHours();
  if (hour < 12) return "Good morning";
  if (hour < 18) return "Good afternoon";
  return "Good evening";
}
