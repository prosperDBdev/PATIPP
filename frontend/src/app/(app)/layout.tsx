"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect } from "react";
import { Button, Spinner } from "@/components/ui";
import { ThemeToggle } from "@/components/theme-toggle";
import { useAuth } from "@/lib/auth/auth-context";

export default function AppLayout({ children }: { children: React.ReactNode }) {
  const { user, loading, logout } = useAuth();
  const router = useRouter();
  const pathname = usePathname();

  useEffect(() => {
    // Only redirect once the bootstrap refresh has settled, otherwise a signed-in user
    // gets bounced to /login on every reload before their cookie has been exchanged.
    if (!loading && !user) {
      router.replace("/login");
    }
  }, [loading, user, router]);

  if (loading || !user) {
    return (
      <div className="grid min-h-dvh place-items-center">
        <Spinner className="size-6 text-text-faint" />
        <span className="sr-only">Loading your session</span>
      </div>
    );
  }

  return (
    <div className="flex min-h-dvh flex-col">
      <header className="sticky top-0 z-20 border-b border-border bg-bg/85 backdrop-blur">
        <div className="mx-auto flex h-14 max-w-6xl items-center gap-4 px-4 sm:px-6">
          <Link
            href="/spaces"
            className="font-mono text-xs tracking-[0.18em] text-text uppercase"
          >
            PATIPP
          </Link>

          <nav className="ml-2 hidden items-center gap-1 sm:flex">
            <NavLink href="/spaces" current={pathname === "/spaces"}>
              Spaces
            </NavLink>
          </nav>

          <div className="ml-auto flex items-center gap-2">
            <ThemeToggle />
            <span className="hidden text-sm text-text-muted sm:inline">
              {user.displayName}
            </span>
            <Button
              variant="ghost"
              size="sm"
              onClick={async () => {
                await logout();
                router.replace("/login");
              }}
            >
              Sign out
            </Button>
          </div>
        </div>
      </header>

      <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-8 sm:px-6">{children}</main>
    </div>
  );
}

function NavLink({
  href,
  current,
  children,
}: {
  href: string;
  current: boolean;
  children: React.ReactNode;
}) {
  return (
    <Link
      href={href}
      aria-current={current ? "page" : undefined}
      className={`rounded-md px-2.5 py-1.5 text-sm transition-colors ${
        current ? "bg-surface-2 text-text" : "text-text-muted hover:text-text"
      }`}
    >
      {children}
    </Link>
  );
}
