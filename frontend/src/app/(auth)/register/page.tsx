"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { Alert, Button, Field, Input } from "@/components/ui";
import { GoogleSignInButton } from "@/components/google-sign-in-button";
import { ApiError } from "@/lib/api/client";
import { useAuth } from "@/lib/auth/auth-context";

const MIN_PASSWORD_LENGTH = 10;

export default function RegisterPage() {
  const router = useRouter();
  const { register, signInWithGoogle, user, loading } = useAuth();

  const [displayName, setDisplayName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!loading && user) {
      router.replace("/spaces");
    }
  }, [loading, user, router]);

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    setFieldErrors({});
    setSubmitting(true);
    try {
      await register({ email, password, displayName });
      router.replace("/spaces");
    } catch (caught) {
      if (caught instanceof ApiError) {
        // Field errors land under the inputs; anything else goes to the banner.
        const fields = caught.fieldErrors();
        if (Object.keys(fields).length > 0) {
          setFieldErrors(fields);
        } else {
          setError(caught.message);
        }
      } else {
        setError("Could not reach the server. Is the API running?");
      }
      setSubmitting(false);
    }
  }

  async function handleGoogle(idToken: string) {
    setError(null);
    setFieldErrors({});
    setSubmitting(true);
    try {
      // The same call the sign-in page makes. Whether this creates an account or finds an
      // existing one is the server's business, and deliberately not visible from here.
      await signInWithGoogle(idToken);
      router.replace("/spaces");
    } catch (caught) {
      setError(
        caught instanceof ApiError
          ? caught.message
          : "Could not reach the server. Is the API running?",
      );
      setSubmitting(false);
    }
  }

  const passwordTooShort = password.length > 0 && password.length < MIN_PASSWORD_LENGTH;

  return (
    <div className="flex flex-col gap-8">
      <div className="flex flex-col gap-1.5">
        <h1 className="text-2xl font-semibold tracking-tight text-text">
          Create your account
        </h1>
        <p className="text-sm text-text-muted">
          One place to prepare for exams, interviews and certifications.
        </p>
      </div>

      {error && <Alert>{error}</Alert>}

      <div className="flex flex-col gap-5">
        <GoogleSignInButton
          onSignedIn={handleGoogle}
          onError={setError}
          text="signup_with"
        />

        <div className="flex items-center gap-3" aria-hidden="true">
          <span className="h-px flex-1 bg-border" />
          <span className="text-[11px] tracking-wider text-text-faint uppercase">or</span>
          <span className="h-px flex-1 bg-border" />
        </div>
      </div>

      <form onSubmit={handleSubmit} className="flex flex-col gap-2" noValidate>
        <Field label="Name" htmlFor="displayName" error={fieldErrors.displayName}>
          <Input
            id="displayName"
            autoComplete="name"
            required
            autoFocus
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            placeholder="Ada Lovelace"
            invalid={Boolean(fieldErrors.displayName)}
          />
        </Field>

        <Field label="Email" htmlFor="email" error={fieldErrors.email}>
          <Input
            id="email"
            type="email"
            autoComplete="email"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="you@example.com"
            invalid={Boolean(fieldErrors.email)}
          />
        </Field>

        <Field
          label="Password"
          htmlFor="password"
          error={fieldErrors.password ?? (passwordTooShort ? "At least 10 characters." : undefined)}
          hint={`At least ${MIN_PASSWORD_LENGTH} characters.`}
        >
          <Input
            id="password"
            type="password"
            autoComplete="new-password"
            required
            minLength={MIN_PASSWORD_LENGTH}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder="A passphrase you will remember"
            invalid={Boolean(fieldErrors.password) || passwordTooShort}
          />
        </Field>

        <Button type="submit" loading={submitting} className="mt-2 w-full">
          Create account
        </Button>
      </form>

      <p className="text-center text-sm text-text-muted">
        Already have an account?{" "}
        <Link href="/login" className="font-medium text-accent hover:underline">
          Sign in
        </Link>
      </p>
    </div>
  );
}
