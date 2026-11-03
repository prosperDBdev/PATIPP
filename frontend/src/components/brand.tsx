import Image from "next/image";
import Link from "next/link";

export function Brand({ href = "/", light = false }: { href?: string; light?: boolean }) {
  return <Link href={href} className={`inline-flex shrink-0 items-center gap-2.5 ${light ? "text-[#e3eae8]" : "text-text"}`} aria-label="PATIPP home">
    <Image src="/icon.svg" alt="" width={36} height={36} className="rounded-xl" />
    <span className="text-base font-bold tracking-[.12em]">PATIPP<span className="text-[#dca149]">.</span></span>
  </Link>;
}

export function Glyph({ kind = "arrow", className = "size-5" }: { kind?: "arrow" | "code" | "repeat" | "clock" | "check" | "grid"; className?: string }) {
  const paths = {
    arrow: "M5 12h14M13 6l6 6-6 6",
    code: "m8 6-6 6 6 6m8-12 6 6-6 6m-3-15-2 18",
    repeat: "M20 7v5h-5M4 17v-5h5M6 7a7 7 0 0 1 12-1l2 3M4 15l2 3a7 7 0 0 0 12-1",
    clock: "M12 8v5l3 2M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0",
    check: "m5 12 4 4L19 6",
    grid: "M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z",
  };
  return <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" className={className}><path d={paths[kind]} /></svg>;
}
