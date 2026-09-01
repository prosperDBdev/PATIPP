"use client";

import { useSyncExternalStore } from "react";
import { cn } from "@/components/ui";

type Theme = "light" | "dark";

/*
 * The theme lives on <html>, put there by the blocking script in layout.tsx before first
 * paint. That makes the DOM the source of truth, not React state - so this reads it with
 * useSyncExternalStore rather than mirroring it into state inside an effect.
 *
 * The practical payoff: React uses getServerSnapshot during hydration, so the server HTML
 * and the first client render agree and there is no mismatch, and the observer keeps the
 * button honest if anything else ever changes the class.
 */
function subscribe(onChange: () => void): () => void {
  const observer = new MutationObserver(onChange);
  observer.observe(document.documentElement, {
    attributes: true,
    attributeFilter: ["class"],
  });
  return () => observer.disconnect();
}

function getSnapshot(): Theme {
  return document.documentElement.classList.contains("dark") ? "dark" : "light";
}

function getServerSnapshot(): Theme {
  // The server cannot know the preference; the pre-paint script has already applied it
  // by the time anyone sees the page.
  return "light";
}

export function ThemeToggle() {
  const theme = useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot);

  function toggle() {
    const next: Theme = theme === "dark" ? "light" : "dark";
    // Mutating the class is the state change; useSyncExternalStore picks it up.
    document.documentElement.classList.toggle("dark", next === "dark");
    try {
      localStorage.setItem("patipp-theme", next);
    } catch {
      // Private browsing can refuse storage. The toggle still works for this session.
    }
  }

  return (
    <button
      type="button"
      onClick={toggle}
      aria-label={theme === "dark" ? "Switch to light theme" : "Switch to dark theme"}
      className={cn(
        "grid size-8 place-items-center rounded-md text-text-muted",
        "hover:bg-surface-2 hover:text-text transition-colors",
      )}
    >
      {theme === "dark" ? (
        <svg viewBox="0 0 24 24" className="size-4" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
          <circle cx="12" cy="12" r="4" />
          <path
            strokeLinecap="round"
            d="M12 2v2m0 16v2M4.9 4.9l1.4 1.4m11.4 11.4 1.4 1.4M2 12h2m16 0h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"
          />
        </svg>
      ) : (
        <svg viewBox="0 0 24 24" className="size-4" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z"
          />
        </svg>
      )}
    </button>
  );
}
