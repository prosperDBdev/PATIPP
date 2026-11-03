"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect } from "react";
import { Button, Spinner } from "@/components/ui";
import { ThemeToggle } from "@/components/theme-toggle";
import { useAuth } from "@/lib/auth/auth-context";
import { Brand, Glyph } from "@/components/brand";

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
        <div className="mx-auto flex h-18 max-w-[1440px] items-center gap-4 px-4 sm:px-6">
          <Brand href="/spaces" />

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

      <div className="mx-auto flex w-full max-w-[1440px] flex-1">
        <aside className="app-sidebar hidden flex-col gap-3 border-r border-border p-6 lg:flex">
          <p className="eyebrow mb-3 text-text-faint">Workspace</p>
          <NavLink href="/spaces" current={pathname === "/spaces"}><span className="flex items-center gap-3"><Glyph kind="grid" className="size-4" /> My spaces</span></NavLink>
          <NavLink href="/spaces/new" current={pathname === "/spaces/new"}>+ New space</NavLink>
          <div className="mt-auto rounded-2xl bg-surface-2 p-4"><Glyph kind="repeat" className="mb-3 size-5 text-accent" /><p className="text-sm font-medium text-text">Make it yours.</p><p className="mt-2 text-xs leading-relaxed text-text-muted">Recall. Write. Check. Explain. Then rebuild without help.</p></div>
        </aside>
        <main className="min-w-0 flex-1 px-4 py-8 sm:px-8 lg:py-10">{children}</main>
      </div>
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
