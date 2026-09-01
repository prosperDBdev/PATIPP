import type { Metadata, Viewport } from "next";
import { Inter, JetBrains_Mono } from "next/font/google";
import "./globals.css";
import { AuthProvider } from "@/lib/auth/auth-context";

const sans = Inter({
  variable: "--font-sans-stack",
  subsets: ["latin"],
  display: "swap",
});

const mono = JetBrains_Mono({
  variable: "--font-mono-stack",
  subsets: ["latin"],
  display: "swap",
});

export const metadata: Metadata = {
  title: "PATIPP",
  description:
    "One preparation platform that adapts to whatever you are preparing for.",
};

export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#f1f3f2" },
    { media: "(prefers-color-scheme: dark)", color: "#0b1110" },
  ],
};

/*
 * Runs before first paint so a dark-mode user never sees a white flash. It has to be
 * inline and blocking: anything deferred runs after the browser has already painted, which
 * is the flash we are avoiding. Wrapped in try/catch because localStorage throws outright
 * in some privacy modes, and a theme preference is not worth a blank page.
 */
const themeScript = `
try {
  var stored = localStorage.getItem('patipp-theme');
  var dark = stored === 'dark' || (!stored && matchMedia('(prefers-color-scheme: dark)').matches);
  if (dark) document.documentElement.classList.add('dark');
} catch (e) {}
`;

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en" className={`${sans.variable} ${mono.variable} h-full`} suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: themeScript }} />
      </head>
      <body className="min-h-full font-sans antialiased">
        <AuthProvider>{children}</AuthProvider>
      </body>
    </html>
  );
}
