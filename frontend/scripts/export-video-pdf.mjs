import { chromium } from "playwright";
import { fileURLToPath } from "node:url";

const source = new URL("../../docs/presentation/PATIPP-video-script.html", import.meta.url);
const destination = fileURLToPath(new URL("../../docs/presentation/PATIPP-video-script.pdf", import.meta.url));
const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHANNEL || "msedge", headless: true });
try {
  const page = await browser.newPage();
  await page.goto(source.href);
  await page.pdf({ path: destination, printBackground: true, preferCSSPageSize: true });
  for (const [index, section] of (await page.locator(".page").all()).entries()) {
    const overflow = await section.evaluate((element) => element.scrollHeight > element.clientHeight);
    if (overflow) throw new Error(`Script page ${index + 1} overflows. Shorten the text before recording.`);
  }
  console.log(`Exported ${destination}`);
} finally { await browser.close(); }
