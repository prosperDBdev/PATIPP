"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { Alert, Button, Field, Input } from "@/components/ui";
import { GoogleSignInButton } from "@/components/google-sign-in-button";
import { ApiError } from "@/lib/api/client";
import { useAuth } from "@/lib/auth/auth-context";

export default function LoginPage() {
  const router = useRouter();
  const { login, signInWithGoogle, user, loading } = useAuth();

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [passwordAttempted, setPasswordAttempted] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!loading && user) {
      router.replace("/spaces");
    }
  }, [loading, user, router]);

  function describe(caught: unknown): string {
    return caught instanceof ApiError
      ? caught.message
      : "Could not reach the server. Is the API running?";
  }

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await login(email, password);
      router.replace("/spaces");
    } catch (caught) {
      // The server refuses to say whether the address exists, and neither do we.
      setError(describe(caught));
      // Shown after ANY failed password attempt, never only when the account really is
      // Google-only. That is the whole point: it rescues someone who signed up with Google
      // and forgot, without telling a stranger probing addresses which ones exist.
      setPasswordAttempted(true);
      setSubmitting(false);
    }
  }

  async function handleGoogle(idToken: string) {
    setError(null);
    setPasswordAttempted(false);
    setSubmitting(true);
    try {
      await signInWithGoogle(idToken);
      router.replace("/spaces");
    } catch (caught) {
      setError(describe(caught));
      setSubmitting(false);
    }
  }

  return (
    <div className="flex flex-col gap-8">
      <div className="flex flex-col gap-1.5">
        <h1 className="text-2xl font-semibold tracking-tight text-text">Welcome back</h1>
        <p className="text-sm text-text-muted">Pick up where you left off.</p>
      </div>

      {error && (
        <Alert>
          {error}
          {passwordAttempted && (
            <span className="mt-1 block text-[13px] opacity-90">
              Signed up with Google? Use the button above instead.
            </span>
          )}
        </Alert>
      )}

      <div className="flex flex-col gap-5">
        <GoogleSignInButton onSignedIn={handleGoogle} onError={setError} />

        <div className="flex items-center gap-3" aria-hidden="true">
          <span className="h-px flex-1 bg-border" />
          <span className="text-[11px] tracking-wider text-text-faint uppercase">or</span>
          <span className="h-px flex-1 bg-border" />
        </div>
      </div>

      <form onSubmit={handleSubmit} className="flex flex-col gap-2" noValidate>
        <Field label="Email" htmlFor="email">
          <Input
            id="email"
            type="email"
            autoComplete="email"
            required
            autoFocus
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="you@example.com"
          />
        </Field>

        <Field label="Password" htmlFor="password">
          <Input
            id="password"
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder="Your password"
          />
        </Field>

        <Button type="submit" loading={submitting} className="mt-2 w-full">
          Sign in
        </Button>
      </form>

      <p className="text-center text-sm text-text-muted">
        No account yet?{" "}
        <Link href="/register" className="font-medium text-accent hover:underline">
          Create one
        </Link>
      </p>
    </div>
  );
}
