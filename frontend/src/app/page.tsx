"use client";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { Brand, Glyph } from "@/components/brand";
import { ThemeToggle } from "@/components/theme-toggle";
import { useAuth } from "@/lib/auth/auth-context";

export default function LandingPage() {
  const { user, loading } = useAuth();
  const router = useRouter();
  useEffect(() => { if (!loading && user) router.replace("/spaces"); }, [loading, user, router]);
  return <div className="min-h-dvh">
    <header className="mx-auto flex h-20 max-w-7xl items-center justify-between gap-3 px-5 sm:px-8">
      <Brand /><nav className="flex items-center gap-2 sm:gap-5" aria-label="Main navigation">
        <Link href="#the-loop" className="hidden text-sm text-text-muted sm:inline">How it works</Link><ThemeToggle />
        {!loading && !user && <Link href="/login" className="text-sm font-medium text-text">Sign in ↗</Link>}
      </nav>
    </header>
    <main className="mx-auto max-w-7xl px-5 pb-16 sm:px-8">
      <section className="grid items-center gap-10 pb-14 pt-8 lg:grid-cols-[1.1fr_1fr] lg:gap-16 lg:py-20">
        <div className="studio-enter flex flex-col items-start gap-7">
          <span className="eyebrow flex items-center gap-2 text-accent"><span className="size-1.5 rounded-full bg-accent" />Your practice. Your progress.</span>
          <h1 className="display-title max-w-2xl text-5xl text-text sm:text-6xl xl:text-7xl">Close the gap.<br /><span className="text-accent">Build it yourself.</span></h1>
          <p className="max-w-lg text-base leading-relaxed text-text-muted sm:text-lg">Turn the things you struggle with into the things you can explain. Focused practice, useful hints, and a reason to try again.</p>
          <div className="flex flex-wrap items-center gap-5"><Link href="/register" className="gold-button">Find your starting point <Glyph /></Link><Link href="#the-loop" className="text-sm font-medium text-text-muted">Explore the practice loop ↓</Link></div>
          <p className="text-xs text-text-muted">A small session counts. A missed day isn’t the end.</p>
        </div>
        <div className="ink-panel studio-enter p-6 sm:p-8">
          <div className="mb-8 flex items-center justify-between"><span className="eyebrow text-[#97a6a3]">A look inside your practice</span><Glyph kind="code" className="size-6 text-[#dca149]" /></div>
          <span className="rounded-full border border-[#354644] px-3 py-1 text-xs text-[#dca149]">Java + Spring Boot</span>
          <h2 className="mt-5 text-2xl font-semibold tracking-tight">One task. A little more confidence.</h2>
          <p className="mt-3 text-sm leading-relaxed text-[#97a6a3]">Example exercise: read a JSON catalogue, then add and delete books through your own API.</p>
          <div className="my-7 overflow-hidden rounded-xl border border-[#354644] bg-[#0b1110]">
            <div className="flex items-center gap-1.5 border-b border-[#23302e] px-4 py-3"><span className="size-2 rounded-full bg-[#354644]" /><span className="size-2 rounded-full bg-[#354644]" /><span className="size-2 rounded-full bg-[#dca149]" /><span className="ml-auto font-mono text-[10px] text-[#97a6a3]">YOUR IDE</span></div>
            <div className="p-5 font-mono text-xs leading-8">{["GET     /books", "POST    /books", "DELETE  /books/{id}"].map(route => <p key={route} className="whitespace-pre"><span className="text-[#dca149]">{route.slice(0, 8)}</span>{route.slice(8)}</p>)}</div>
          </div>
          <div className="grid grid-cols-3 gap-3 border-t border-[#354644] pt-5">{["Try it first", "Check it locally", "Explain it back"].map((step, index) => <div key={step}><p className="eyebrow mb-2 text-[#dca149]">0{index + 1}</p><p className="text-xs">{step}</p></div>)}</div>
        </div>
      </section>
      <section className="grid gap-px overflow-hidden rounded-2xl border border-border bg-border sm:grid-cols-3">{[
        ["A clear next step", "Spend less time deciding what to study."], ["Help, when you need it", "Reveal a hint without skipping the attempt."], ["Evidence you can revisit", "See assisted practice become independent work."],
      ].map(([title, detail]) => <div key={title} className="bg-surface p-6"><h2 className="text-sm font-semibold text-text">{title}</h2><p className="mt-2 text-sm text-text-muted">{detail}</p></div>)}</section>
      <section id="the-loop" className="scroll-mt-8 py-16 sm:py-24">
        <div className="mb-9 flex flex-col justify-between gap-4 sm:flex-row sm:items-end"><div><p className="eyebrow mb-3 text-accent">The practice loop</p><h2 className="display-title text-3xl text-text sm:text-4xl">Understanding comes from doing.</h2></div><p className="max-w-sm text-sm leading-relaxed text-text-muted">For coding, exams, or interview preparation. Keep your material and progress together in a preparation space.</p></div>
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">{[
          ["01", "Find your gap", "Your answers help identify weak areas and topics you haven’t assessed."],
          ["02", "Make an attempt", "Write code in your IDE or answer a practice question. Give yourself a first try."],
          ["03", "Get unstuck", "Use progressive hints, check the expected behaviour, and record the help you used."],
          ["04", "Come back stronger", "Review what’s due and rebuild a task with less help. Keep the evidence of that progress."],
        ].map(([number, title, detail]) => <article key={number} className="chapter-block studio-card p-6"><span className="eyebrow text-accent">{number}</span><h3 className="mb-3 mt-8 text-lg font-semibold tracking-tight text-text">{title}</h3><p className="text-sm leading-relaxed text-text-muted">{detail}</p></article>)}</div>
      </section>
      <section className="ink-panel grid gap-8 p-7 sm:p-10 lg:grid-cols-[1.4fr_1fr]">
        <div><p className="eyebrow text-[#dca149]">Built for real life</p><h2 className="display-title mb-4 mt-4 text-3xl sm:text-4xl">Your next good attempt<br />doesn’t need a perfect day.</h2><p className="max-w-xl text-sm leading-relaxed text-[#97a6a3]">The Java and Spring Boot guide has 30 focused exercises. Try a 10–30 minute core on a busy day, or spend longer on an extension. Return to your saved progress when you can.</p></div>
        <div className="flex flex-col items-start justify-center gap-4 border-t border-[#354644] pt-7 lg:border-l lg:border-t-0 lg:pl-9 lg:pt-0"><Glyph kind="repeat" className="size-8 text-[#dca149]" /><p className="text-lg font-medium">From “I needed a hint”<br />to “I built this myself.”</p><Link href="/register" className="gold-button">Begin with one exercise <Glyph /></Link></div>
      </section>
      <p className="mt-5 text-xs leading-relaxed text-text-muted">Coding checks and independence are self-reported after working in your IDE. PATIPP doesn’t execute your code or promise mastery in 30 days.</p>
    </main>
    <footer className="border-t border-border"><div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-4 px-5 py-7 text-xs text-text-muted sm:px-8"><span>Personalized Adaptive Test &amp; Interview Preparation Platform</span><Link href="/privacy" className="hover:text-text">Privacy policy ↗</Link></div></footer>
  </div>;
}
