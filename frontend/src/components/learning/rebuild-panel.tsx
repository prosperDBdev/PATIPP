import { Glyph } from "@/components/brand";
import { rebuildExperience, type ChallengeGuide } from "@/lib/api/challenge";

export function RebuildPanel({ guide, onSelect }: { guide: ChallengeGuide; onSelect: (day: number) => void }) {
  const { target, bookmark, receipts } = rebuildExperience(guide);
  return <section className="chapter-block grid gap-6 p-5 sm:p-6 md:grid-cols-2" aria-label="Return and rebuild">
    <div>
      <p className="eyebrow flex items-center gap-2 text-accent"><Glyph kind="repeat" className="size-4" /> Return &amp; rebuild</p>
      <h2 className="mt-3 text-xl font-semibold tracking-tight text-text">Turn yesterday&apos;s help into your own skill.</h2>
      <p className="mt-2 text-sm leading-relaxed text-text-muted">{target ? `Day ${target.day}: ${target.title}. You completed this with help. Try rebuilding it from a blank file with references closed.` : "After you complete an exercise with help, come back and try it independently. Your progress stays here, even when life interrupts."}</p>
      {target && <a href="#exercise-brief" onClick={() => onSelect(target.day)} className="mt-4 inline-flex items-center gap-2 text-sm font-semibold text-accent">Choose this rebuild <Glyph className="size-4" /></a>}
    </div>
    <div className="rounded-xl bg-surface-2 p-4">
      {bookmark ? <><p className="eyebrow text-text-faint">Your last note · day {bookmark.day}</p><p className="mt-3 whitespace-pre-wrap text-sm leading-relaxed text-text">“{bookmark.reflection}”</p></> : <><p className="eyebrow text-text-faint">Leave a way back in</p><p className="mt-3 text-sm leading-relaxed text-text-muted">At the end of an attempt, leave a note: what clicked, what confused you, and the next small step. It will be waiting here.</p></>}
      <div className="mt-4 border-t border-border pt-3">
        <p className="text-sm font-semibold text-text">{receipts.length} independent {receipts.length === 1 ? "rebuild" : "rebuilds"} after help</p>
        {receipts.length > 0 && <p className="mt-1 text-xs text-text-muted">Days {receipts.map((receipt) => receipt.day).join(", ")} · completed with help, then completed independently.</p>}
        <p className="mt-1 text-xs text-text-faint">Based on your recorded checks and assistance, not automatic code verification.</p>
      </div>
    </div>
  </section>;
}
