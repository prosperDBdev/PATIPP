"use client";

import { forwardRef } from "react";

export function cn(...parts: Array<string | false | null | undefined>) {
  return parts.filter(Boolean).join(" ");
}

/* ------------------------------------------------------------------ Button */

type ButtonVariant = "primary" | "secondary" | "ghost" | "danger";
type ButtonSize = "sm" | "md";

const buttonVariants: Record<ButtonVariant, string> = {
  primary:
    "bg-accent text-accent-fg hover:bg-accent-hover disabled:hover:bg-accent shadow-sm",
  secondary:
    "bg-surface text-text border border-border hover:border-border-strong hover:bg-surface-2",
  ghost: "text-text-muted hover:text-text hover:bg-surface-2",
  danger: "bg-danger-soft text-danger border border-danger/30 hover:bg-danger/15",
};

const buttonSizes: Record<ButtonSize, string> = {
  sm: "h-8 px-3 text-[13px]",
  md: "h-10 px-4 text-sm",
};

interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  loading?: boolean;
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = "primary", size = "md", loading = false, disabled, className, children, ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      // A loading button stays disabled so a double-click cannot submit twice.
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      className={cn(
        "inline-flex items-center justify-center gap-2 rounded-md font-medium",
        "transition-colors duration-150 select-none",
        "disabled:opacity-50 disabled:cursor-not-allowed",
        buttonVariants[variant],
        buttonSizes[size],
        className,
      )}
      {...rest}
    >
      {loading && <Spinner className="size-3.5" />}
      {children}
    </button>
  );
});

/* ------------------------------------------------------------------ Spinner */

export function Spinner({ className }: { className?: string }) {
  return (
    <svg
      className={cn("animate-spin", className ?? "size-4")}
      viewBox="0 0 24 24"
      fill="none"
      aria-hidden="true"
    >
      <circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="3" opacity="0.25" />
      <path
        d="M22 12a10 10 0 0 1-10 10"
        stroke="currentColor"
        strokeWidth="3"
        strokeLinecap="round"
      />
    </svg>
  );
}

/* ------------------------------------------------------------------ Field */

interface FieldProps {
  label: string;
  htmlFor: string;
  error?: string;
  hint?: string;
  children: React.ReactNode;
}

export function Field({ label, htmlFor, error, hint, children }: FieldProps) {
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={htmlFor} className="text-[13px] font-medium text-text">
        {label}
      </label>
      {children}
      {/* aria-live so a screen reader announces validation failures as they appear. */}
      <p className="min-h-4 text-xs" aria-live="polite">
        {error ? (
          <span className="text-danger">{error}</span>
        ) : hint ? (
          <span className="text-text-faint">{hint}</span>
        ) : null}
      </p>
    </div>
  );
}

/* ------------------------------------------------------------------ Input */

interface InputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  invalid?: boolean;
}

export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  { invalid, className, ...rest },
  ref,
) {
  return (
    <input
      ref={ref}
      aria-invalid={invalid || undefined}
      className={cn(
        "h-10 w-full rounded-md border bg-surface px-3 text-sm text-text",
        "placeholder:text-text-faint transition-colors",
        invalid ? "border-danger" : "border-border hover:border-border-strong",
        className,
      )}
      {...rest}
    />
  );
});

export const Textarea = forwardRef<
  HTMLTextAreaElement,
  React.TextareaHTMLAttributes<HTMLTextAreaElement>
>(function Textarea({ className, ...rest }, ref) {
  return (
    <textarea
      ref={ref}
      className={cn(
        "w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-text",
        "placeholder:text-text-faint hover:border-border-strong transition-colors resize-y",
        className,
      )}
      {...rest}
    />
  );
});

/* ------------------------------------------------------------------ Card */

export function Card({ className, children }: { className?: string; children: React.ReactNode }) {
  return (
    <div className={cn("rounded-lg border border-border bg-surface", className)}>{children}</div>
  );
}

/* ------------------------------------------------------------------ Alert */

export function Alert({ children }: { children: React.ReactNode }) {
  return (
    <div
      role="alert"
      className="rounded-md border border-danger/30 bg-danger-soft px-3 py-2 text-[13px] text-danger"
    >
      {children}
    </div>
  );
}

/* ------------------------------------------------------------------ Badge */

export function Badge({
  children,
  tone = "neutral",
}: {
  children: React.ReactNode;
  tone?: "neutral" | "accent" | "success";
}) {
  const tones = {
    neutral: "bg-surface-2 text-text-muted border-border",
    accent: "bg-accent-soft text-accent border-accent/30",
    success: "bg-success-soft text-success border-success/30",
  };
  return (
    <span
      className={cn(
        "inline-flex items-center rounded border px-1.5 py-0.5 font-mono text-[10px] tracking-wide uppercase",
        tones[tone],
      )}
    >
      {children}
    </span>
  );
}

/* ------------------------------------------------------------------ Empty state */

export function EmptyState({
  title,
  description,
  action,
}: {
  title: string;
  description: string;
  action?: React.ReactNode;
}) {
  return (
    <div className="flex flex-col items-center gap-3 rounded-lg border border-dashed border-border px-6 py-14 text-center">
      <h3 className="text-base font-semibold text-text">{title}</h3>
      <p className="max-w-sm text-sm text-text-muted">{description}</p>
      {action}
    </div>
  );
}
