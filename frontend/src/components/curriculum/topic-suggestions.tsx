"use client";

import { useEffect, useState } from "react";
import { Alert, Button, Spinner, cn } from "@/components/ui";
import { api, ApiError } from "@/lib/api/client";
import type { SuggestedTopic, TopicSuggestions as Suggestions } from "@/lib/api/types";

/**
 * Offers starter topics for a subject, pre-selected, and adds the chosen ones in one request.
 *
 * <p>Pre-selected rather than pre-added. Accepting is one click either way, but the topics are
 * the user's curriculum and every mastery and readiness number is computed per topic - so a
 * list they did not actually choose would quietly distort their own results.
 *
 * <p>Renders nothing at all when the catalogue has no match, or once every suggestion has been
 * taken. An empty panel saying "no suggestions" would be worse than no panel.
 */
export function TopicSuggestionsPanel({
  spaceId,
  subjectId,
  subjectName,
  onAdded,
}: {
  spaceId: string;
  subjectId: string;
  subjectName: string;
  onAdded: () => Promise<void>;
}) {
  const [suggestions, setSuggestions] = useState<Suggestions | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [dismissed, setDismissed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    (async () => {
      try {
        const loaded = await api<Suggestions>(
          `/api/v1/spaces/${spaceId}/subjects/${subjectId}/topic-suggestions`,
        );
        if (cancelled) return;
        setSuggestions(loaded);
        // Everything checked by default: the common case is "yes, all of those".
        setSelected(new Set(loaded.topics.map((topic) => topic.name)));
      } catch {
        // A failed suggestion lookup must not break the editor. Manual entry still works,
        // so the panel simply does not appear.
        if (!cancelled) setSuggestions({ matched: false, matchedSubject: null, source: "", topics: [] });
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [spaceId, subjectId]);

  async function addSelected() {
    setBusy(true);
    setError(null);
    try {
      await api<unknown>(`/api/v1/spaces/${spaceId}/topics/bulk`, {
        method: "POST",
        body: { subjectId, names: [...selected] },
      });
      await onAdded();
      setDismissed(true);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "Could not add those topics.");
      setBusy(false);
    }
  }

  if (dismissed) return null;

  if (suggestions === null) {
    return (
      <div className="flex items-center gap-2 px-4 py-3 text-xs text-text-faint">
        <Spinner className="size-3.5" />
        Looking for starter topics…
      </div>
    );
  }

  if (!suggestions.matched || suggestions.topics.length === 0) {
    return null;
  }

  return (
    <div className="flex flex-col gap-3 border-b border-border bg-accent-soft/40 px-4 py-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <p className="text-[13px] text-text">
          <span className="font-medium">Suggested topics</span>{" "}
          <span className="text-text-muted">
            for {suggestions.matchedSubject}
            {/* Say so when an alias was matched, so "js" resolving to JavaScript is not a
                silent surprise. */}
            {suggestions.matchedSubject?.toLowerCase() !== subjectName.toLowerCase() && (
              <> — matched from &ldquo;{subjectName}&rdquo;</>
            )}
          </span>
        </p>
        <button
          type="button"
          onClick={() =>
            setSelected(
              selected.size === suggestions.topics.length
                ? new Set()
                : new Set(suggestions.topics.map((topic) => topic.name)),
            )
          }
          className="text-xs font-medium text-accent hover:underline"
        >
          {selected.size === suggestions.topics.length ? "Deselect all" : "Select all"}
        </button>
      </div>

      {error && <Alert>{error}</Alert>}

      <ul className="flex flex-wrap gap-1.5">
        {suggestions.topics.map((topic) => (
          <li key={topic.name}>
            <SuggestionChip
              topic={topic}
              checked={selected.has(topic.name)}
              onToggle={() =>
                setSelected((current) => {
                  const next = new Set(current);
                  if (!next.delete(topic.name)) next.add(topic.name);
                  return next;
                })
              }
            />
          </li>
        ))}
      </ul>

      <div className="flex items-center gap-2">
        <Button size="sm" onClick={addSelected} loading={busy} disabled={selected.size === 0}>
          Add {selected.size} topic{selected.size === 1 ? "" : "s"}
        </Button>
        <Button variant="ghost" size="sm" onClick={() => setDismissed(true)}>
          Not now
        </Button>
      </div>
    </div>
  );
}

function SuggestionChip({
  topic,
  checked,
  onToggle,
}: {
  topic: SuggestedTopic;
  checked: boolean;
  onToggle: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onToggle}
      aria-pressed={checked}
      // The description is the tooltip rather than always-visible text: eight topics each
      // with a subtitle is a wall, and the name alone is usually enough to decide.
      title={topic.description ?? undefined}
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs transition-colors",
        checked
          ? "border-accent bg-accent text-accent-fg"
          : "border-border bg-surface text-text-muted hover:border-border-strong",
      )}
    >
      <span aria-hidden="true" className="font-mono text-[10px]">
        {checked ? "✓" : "+"}
      </span>
      {topic.name}
    </button>
  );
}
