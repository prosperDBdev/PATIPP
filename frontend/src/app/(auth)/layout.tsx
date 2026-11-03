import { ThemeToggle } from "@/components/theme-toggle";
import { Brand, Glyph } from "@/components/brand";

export default function AuthLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="grid min-h-dvh p-3 lg:grid-cols-2 lg:gap-6 lg:p-5">
      <aside className="ink-panel hidden flex-col justify-between p-10 lg:flex xl:p-14">
        <Brand light />
        <div className="py-16">
          <p className="eyebrow mb-6 text-[#dca149]">A little practice. Real progress.</p>
          <h1 className="display-title max-w-lg text-6xl xl:text-7xl">Less guessing.<br />More <span className="text-[#dca149]">&ldquo;I&apos;ve got this.&rdquo;</span></h1>
          <p className="mt-6 max-w-sm text-base leading-relaxed text-[#b2c2bc]">A place to work on the things you find difficult, one honest attempt at a time.</p>
          <div className="mt-10 max-w-sm rounded-2xl border border-white/15 bg-white/5 p-5"><Glyph kind="repeat" className="mb-3 size-6 text-[#dca149]" /><p className="font-medium">Understanding is a good start.<br />Rebuilding it yourself is the next step.</p></div>
        </div>
        <p className="eyebrow text-[#b2c2bc]">Recall / Write / Check / Explain / Repeat</p>
      </aside>
      <div className="flex flex-col">
        <header className="flex items-center justify-between p-4 lg:justify-end"><div className="lg:hidden"><Brand /></div><ThemeToggle /></header>
        <main className="flex flex-1 items-center justify-center px-4 py-12 sm:px-10"><div className="w-full max-w-sm">{children}</div></main>
        <p className="px-6 pb-6 text-center text-xs text-text-faint">Small sessions count. Start where you are.</p>
      </div>
    </div>
  );
}
