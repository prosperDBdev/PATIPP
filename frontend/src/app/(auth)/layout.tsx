import { ThemeToggle } from "@/components/theme-toggle";

export default function AuthLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-dvh flex-col">
      <header className="flex items-center justify-between px-6 py-5">
        <span className="font-mono text-xs tracking-[0.18em] text-text-muted uppercase">
          PATIPP
        </span>
        <ThemeToggle />
      </header>

      <main className="flex flex-1 items-center justify-center px-6 pb-20">
        <div className="w-full max-w-sm">{children}</div>
      </main>
    </div>
  );
}
