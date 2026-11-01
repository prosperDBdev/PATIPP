import Link from "next/link";
import type { Metadata } from "next";
import { ThemeToggle } from "@/components/theme-toggle";

/**
 * The public privacy policy.
 *
 * <p>Required by Google before an OAuth consent screen can be published, and linked from
 * the consent screen itself, so it must stay reachable without signing in. It lives at the
 * top level rather than inside the (app) route group for exactly that reason.
 *
 * <p>Written from the schema rather than from a template. Every category below corresponds
 * to a real table, which is the only way this stays true: a policy that describes a
 * plausible application instead of this one is worse than none, because it is a promise
 * nobody is checking.
 */
export const metadata: Metadata = {
  title: "Privacy — PATIPP",
  description: "What PATIPP stores, why, and who else can see it.",
};

// The address privacy and deletion requests go to. A role alias on the domain this site is
// already served from, rather than a personal inbox: this page is public, and an address
// published here reaches search indexes and cannot be recalled.
//
// It must be given a forwarding rule at the DNS or email provider for ebitimi.dev before it
// receives anything. The deletion section below tells people to write to "the address
// below", and a policy that names a route it does not actually answer is worse than one
// that promises nothing.
const CONTACT_EMAIL = "privacy@ebitimi.dev";

const LAST_UPDATED = "26 September 2026";

export default function PrivacyPage() {
  return (
    <div className="flex min-h-dvh flex-col">
      <header className="sticky top-0 z-20 border-b border-border bg-bg/85 backdrop-blur">
        <div className="mx-auto flex h-14 max-w-3xl items-center gap-4 px-4 sm:px-6">
          <Link
            href="/"
            className="font-mono text-xs tracking-[0.18em] text-text uppercase transition-opacity hover:opacity-70"
          >
            PATIPP
          </Link>
          <div className="ml-auto">
            <ThemeToggle />
          </div>
        </div>
      </header>

      <main className="mx-auto w-full max-w-3xl flex-1 px-4 py-14 sm:px-6">
        <h1 className="text-3xl font-semibold tracking-tight text-text sm:text-4xl">
          Privacy
        </h1>
        <p className="mt-3 text-sm text-text-faint">Last updated {LAST_UPDATED}</p>

        <p className="mt-8 text-[15px] leading-relaxed text-text-muted">
          PATIPP is a study tool. It stores what it needs to decide which question to show
          you next and when to show it again, and nothing beyond that. It has no
          advertising, no third-party analytics and no trackers, and your study data is
          never sold or shared for marketing.
        </p>

        {SECTIONS.map((section) => (
          <section key={section.title} className="mt-12">
            <h2 className="text-lg font-semibold tracking-tight text-text">
              {section.title}
            </h2>
            <div className="mt-3 flex flex-col gap-4 text-[15px] leading-relaxed text-text-muted">
              {section.body.map((paragraph) => (
                <p key={paragraph}>{paragraph}</p>
              ))}
            </div>
            {section.items ? (
              <ul className="mt-4 flex flex-col gap-2 text-[15px] leading-relaxed text-text-muted">
                {section.items.map((item) => (
                  <li key={item.label} className="flex flex-col gap-0.5">
                    <span className="font-medium text-text">{item.label}</span>
                    <span>{item.detail}</span>
                  </li>
                ))}
              </ul>
            ) : null}
          </section>
        ))}

        <section className="mt-12 border-t border-border pt-8">
          <h2 className="text-lg font-semibold tracking-tight text-text">Contact</h2>
          <p className="mt-3 text-[15px] leading-relaxed text-text-muted">
            Questions about any of this, or a request to export or delete your data, go to{" "}
            <a
              href={`mailto:${CONTACT_EMAIL}`}
              className="text-accent underline underline-offset-4 hover:text-accent-hover"
            >
              {CONTACT_EMAIL}
            </a>
            .
          </p>
        </section>
      </main>

      <footer className="border-t border-border">
        <div className="mx-auto flex max-w-3xl flex-wrap items-center gap-x-4 gap-y-2 px-4 py-6 text-xs text-text-faint sm:px-6">
          <span className="font-mono tracking-[0.18em] uppercase">PATIPP</span>
          <Link href="/" className="ml-auto text-text-muted transition-colors hover:text-text">
            Back to the home page
          </Link>
        </div>
      </footer>
    </div>
  );
}

const SECTIONS: {
  title: string;
  body: string[];
  items?: { label: string; detail: string }[];
}[] = [
  {
    title: "What is stored",
    body: [
      "Your account, the material you put into it, and a record of how you answered. "
        + "The last of these is the only part that cannot be recreated, which is why it is "
        + "kept as an append-only log.",
    ],
    items: [
      {
        label: "Account",
        detail:
          "Your email address, a display name, and a timezone. If you register with a "
          + "password, only a bcrypt hash of it is stored — never the password itself.",
      },
      {
        label: "Google sign-in, if you use it",
        detail:
          "Google's permanent account identifier for you, and the email address Google "
          + "has verified. PATIPP asks Google for nothing else: no contacts, no Drive, no "
          + "Gmail, no profile beyond your name. It cannot read anything in your Google "
          + "account, and it never receives your Google password.",
      },
      {
        label: "Your study material",
        detail:
          "The subjects, topics and questions you write or import, inside the preparation "
          + "spaces you create.",
      },
      {
        label: "Your answers",
        detail:
          "Each answer, whether it was correct, how long it took, and how confident you "
          + "said you were. Everything else the app shows you — mastery, review schedules, "
          + "readiness, streaks — is calculated from this and can be rebuilt from it.",
      },
      {
        label: "Sessions",
        detail:
          "A hash of each active sign-in token, its expiry, and the browser user-agent "
          + "string it was issued to, so that you can sign out of a device and so a stolen "
          + "session can be detected and revoked.",
      },
    ],
  },
  {
    title: "What is not stored",
    body: [
      "No advertising or analytics scripts are loaded by the site, so there is no "
        + "cross-site tracking and no profile is built about you anywhere else. No payment "
        + "details, because nothing is sold. No location beyond the timezone you choose "
        + "yourself. Your password is never stored in a form anyone can read, including us.",
    ],
  },
  {
    title: "Cookies",
    body: [
      "One cookie, used to keep you signed in. It holds a session token, is marked "
        + "HttpOnly so no script on the page can read it, Secure so it only travels over "
        + "HTTPS, and SameSite=Strict so your browser will not attach it to a request that "
        + "started on another site. It is replaced each time it is used and deleted when "
        + "you sign out. There are no analytics or advertising cookies to consent to.",
    ],
  },
  {
    title: "Who else can see it",
    body: [
      "PATIPP has no staff, no data brokers and no advertising partners. It does rely on "
        + "three infrastructure providers, which process data only in order to run the "
        + "service, and one identity provider that you choose to use.",
    ],
    items: [
      {
        label: "Neon",
        detail: "Hosts the database, in the United States (us-east-1).",
      },
      {
        label: "Render",
        detail:
          "Runs the API server. Its logs may briefly contain request metadata such as IP "
          + "addresses, as any web server's do.",
      },
      {
        label: "Vercel",
        detail: "Serves the website itself.",
      },
      {
        label: "Google",
        detail:
          "Only if you choose to sign in with Google. Google tells PATIPP your account "
          + "identifier, verified email and name; PATIPP tells Google nothing about your "
          + "studying.",
      },
    ],
  },
  {
    title: "How long it is kept",
    body: [
      "Your account and study data are kept for as long as the account exists, because a "
        + "study history that is quietly trimmed would make the progress figures wrong. "
        + "Sign-in tokens expire on their own and are removed once they do.",
    ],
  },
  {
    title: "Your data is portable, and can be deleted",
    body: [
      "Questions can be exported to CSV or JSON from inside the app at any time, in the "
        + "same shape they import in, so nothing you write is trapped here.",
      "Account deletion is not yet a button in the app. Email the address below and the "
        + "account and everything attached to it will be removed. This is stated plainly "
        + "rather than dressed up as a self-service feature that does not exist yet.",
    ],
  },
  {
    title: "Changes",
    body: [
      "If what is collected changes, this page changes with it and the date at the top "
        + "moves. It is versioned in the same repository as the application, so what it "
        + "said on any given day is a matter of record.",
    ],
  },
];
