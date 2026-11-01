"use client";

import { useEffect, useRef, useState, useSyncExternalStore } from "react";

/**
 * Google's own "Continue with Google" button, wired to PATIPP's session.
 *
 * <p>The flow deliberately never leaves the page. Google Identity Services hands the browser
 * a signed ID token, which is posted once to the API and exchanged for a normal PATIPP
 * session — the same access token and rotating refresh cookie a password sign-in produces.
 * There is no redirect, and therefore no client secret anywhere in this application.
 *
 * <p>Rendering Google's own button rather than styling our own is not laziness: Google's
 * branding terms require their mark and wording, and their widget is what carries the
 * account-chooser behaviour people expect.
 */

const GIS_SRC = "https://accounts.google.com/gsi/client";
const CLIENT_ID = process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID;

/** Google's widget takes a pixel width and clamps at 400. */
const MIN_WIDTH = 200;
const MAX_WIDTH = 400;

interface CredentialResponse {
  credential?: string;
}

interface GoogleIdentityServices {
  accounts: {
    id: {
      initialize(config: {
        client_id: string;
        callback: (response: CredentialResponse) => void;
        auto_select?: boolean;
        cancel_on_tap_outside?: boolean;
      }): void;
      renderButton(
        parent: HTMLElement,
        options: {
          type?: "standard" | "icon";
          theme?: "outline" | "filled_blue" | "filled_black";
          size?: "small" | "medium" | "large";
          text?: "signin_with" | "signup_with" | "continue_with" | "signin";
          shape?: "rectangular" | "pill" | "circle" | "square";
          logo_alignment?: "left" | "center";
          width?: number;
        },
      ): void;
      disableAutoSelect(): void;
    };
  };
}

declare global {
  interface Window {
    google?: GoogleIdentityServices;
  }
}

/**
 * Loads Google's script once per page, however many buttons ask for it.
 *
 * <p>Module-level rather than per-component state, because the sign-in and registration
 * screens both mount one and a second <script> tag would re-initialise the library
 * underneath the first.
 */
let scriptPromise: Promise<void> | null = null;

function loadGoogleIdentityServices(): Promise<void> {
  if (scriptPromise) return scriptPromise;

  scriptPromise = new Promise<void>((resolve, reject) => {
    if (window.google?.accounts?.id) {
      resolve();
      return;
    }

    const existing = document.querySelector<HTMLScriptElement>(`script[src="${GIS_SRC}"]`);
    const script = existing ?? document.createElement("script");

    script.addEventListener("load", () => resolve());
    script.addEventListener("error", () =>
      // Ad blockers and strict DNS filtering both block this, and it is a normal enough
      // outcome to deserve a real message rather than a button that silently never appears.
      reject(new Error("Google's sign-in script could not be loaded.")),
    );

    if (!existing) {
      script.src = GIS_SRC;
      script.async = true;
      script.defer = true;
      document.head.appendChild(script);
    }
  }).catch((error: unknown) => {
    // Let the next mount retry rather than caching the failure forever; the usual cause is
    // a transient network problem.
    scriptPromise = null;
    throw error;
  });

  return scriptPromise;
}

/**
 * The current theme, read from the class the theme toggle sets on <html>.
 *
 * <p>Exposed as an external store rather than mirrored into state, because that is what it
 * is: something outside React that changes on its own. Subscribing with
 * {@link useSyncExternalStore} also keeps the server snapshot explicit, which matters here
 * because the document does not exist during the server render.
 */
function subscribeToTheme(onChange: () => void): () => void {
  const observer = new MutationObserver(onChange);
  observer.observe(document.documentElement, {
    attributes: true,
    attributeFilter: ["class"],
  });
  return () => observer.disconnect();
}

function readTheme(): boolean {
  return document.documentElement.classList.contains("dark");
}

export function GoogleSignInButton({
  onSignedIn,
  onError,
  text = "continue_with",
}: {
  onSignedIn: (idToken: string) => Promise<void>;
  onError: (message: string) => void;
  text?: "continue_with" | "signin_with" | "signup_with";
}) {
  const target = useRef<HTMLDivElement>(null);
  const [unavailable, setUnavailable] = useState(false);
  const [width, setWidth] = useState(0);

  // `false` on the server: the document has no class list there, and guessing dark would
  // make the first client paint disagree with the markup.
  const dark = useSyncExternalStore(subscribeToTheme, readTheme, () => false);

  // Kept in a ref so re-rendering the widget on a theme or width change does not need the
  // callbacks in the effect's dependencies, which would tear down and rebuild the button
  // every time the parent re-rendered.
  const handlers = useRef({ onSignedIn, onError });

  // Updated in an effect rather than during render: mutating a ref while rendering is not
  // safe under concurrent rendering, where a render can be thrown away. The initial value
  // above already covers the first mount, so the widget is never wired to a stale handler.
  useEffect(() => {
    handlers.current = { onSignedIn, onError };
  }, [onSignedIn, onError]);

  // Google's widget wants an explicit pixel width, so it cannot follow a fluid container on
  // its own. Measuring and re-rendering is what keeps it from being visibly narrower than
  // the password form beside it on a phone.
  //
  // ResizeObserver fires once when observation begins, so the first measurement arrives
  // through the same path as every later one and this effect never sets state itself.
  useEffect(() => {
    const parent = target.current?.parentElement;
    if (!parent) return;

    const observer = new ResizeObserver(([entry]) => {
      const available = Math.round(entry.contentRect.width);
      setWidth(Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, available)));
    });
    observer.observe(parent);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (!CLIENT_ID || !width) return;

    let cancelled = false;

    loadGoogleIdentityServices()
      .then(() => {
        if (cancelled || !target.current || !window.google) return;

        window.google.accounts.id.initialize({
          client_id: CLIENT_ID,
          callback: (response) => {
            if (!response.credential) {
              handlers.current.onError("Google did not return a sign-in token.");
              return;
            }
            void handlers.current.onSignedIn(response.credential);
          },
          // No silent sign-in. Landing on someone's dashboard because a browser remembered
          // a Google session is startling, and on a shared machine it is worse than that.
          auto_select: false,
          cancel_on_tap_outside: true,
        });

        target.current.replaceChildren();
        window.google.accounts.id.renderButton(target.current, {
          type: "standard",
          theme: dark ? "filled_black" : "outline",
          size: "large",
          text,
          shape: "rectangular",
          logo_alignment: "left",
          width,
        });
      })
      .catch(() => {
        if (!cancelled) setUnavailable(true);
      });

    return () => {
      cancelled = true;
    };
  }, [width, dark, text]);

  // Nothing configured, so nothing to offer. A dead button would be worse than none, and
  // this is the normal state for a local checkout without Google set up.
  if (!CLIENT_ID) return null;

  if (unavailable) {
    return (
      <p className="text-center text-[13px] leading-relaxed text-text-faint">
        Google sign-in could not load — an ad blocker or network filter usually causes this.
        You can still sign in with an email and password.
      </p>
    );
  }

  return <div ref={target} className="flex min-h-[44px] justify-center" />;
}
