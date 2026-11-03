import { chromium } from "playwright";
import assert from "node:assert/strict";
import { mkdir, readFile } from "node:fs/promises";

// Browser-only fixtures: never written to the application or a user account.
const output = new URL("../../artifacts/previews/", import.meta.url);
await mkdir(output, { recursive: true });
const challenge = JSON.parse(await readFile(new URL("../../backend/src/main/resources/challenges/java-spring-30.json", import.meta.url), "utf8"));
const days = Array.isArray(challenge) ? challenge : challenge.days;
const progress = days.map((day) => ({ day: day.day, questionId: `question-${day.day}`, completed: false, independent: false, attempts: 0, assistance: null }));
const guide = { key: "java-spring-30-v1", days, progress, activity: [], inProgress: null };
const space = { id: "demo", name: "Java + Spring Boot", status: "ACTIVE", description: "My guided practice", config: { challengeKey: guide.key }, preparationType: { name: "Coding test" }, targetScore: null, targetDate: null };
const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHANNEL || "msedge", headless: true });
try {
  for (const width of [1440, 390]) {
    guide.progress = progress.map((entry) => ({ ...entry, completed: false, independent: false, attempts: 0, assistance: null }));
    guide.activity = [];
    const context = await browser.newContext({ viewport: { width, height: 1000 } });
    let signedIn = false;
    let googleEnabled = false;
    const failures = [];
    const page = await context.newPage();
    page.on("pageerror", (error) => failures.push(error.message));
    await context.route("**/api/v1/**", async (route) => {
      const path = new URL(route.request().url()).pathname;
      const headers = { "access-control-allow-origin": "http://localhost:3001", "access-control-allow-credentials": "true", "access-control-allow-headers": "authorization,content-type", "access-control-allow-methods": "GET,POST,OPTIONS" };
      if (route.request().method() === "OPTIONS") return route.fulfill({ status: 204, headers });
      let status = 200;
      let json;
      if (path.endsWith("/auth/providers")) json = { googleEnabled, googleClientId: googleEnabled ? "browser-test.apps.googleusercontent.com" : null };
      else if (path.endsWith("/auth/refresh")) { status = signedIn ? 200 : 401; json = signedIn ? { accessToken: "browser-fixture" } : { detail: "Signed out" }; }
      else if (path.endsWith("/users/me")) json = { id: "test", displayName: "Prosper", timezone: "Africa/Lagos" };
      else if (path.endsWith("/challenge")) json = guide;
      else if (path.endsWith("/spaces")) json = [space];
      else { status = 404; json = {}; }
      await route.fulfill({ status, headers, json });
    });
    const check = async (name) => {
      await page.evaluate(() => document.fonts.ready);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth), false, `${name}: horizontal overflow at ${width}`);
      await page.screenshot({ path: new URL(`${name}-${width}.png`, output).pathname.replace(/^\/(\w:)/, "$1"), fullPage: true });
    };
    await page.goto("http://localhost:3001");
    await page.getByRole("heading", { name: /Close the gap/ }).waitFor();
    await check("landing");
    await page.goto("http://localhost:3001/login");
    await page.getByText("Google sign-in is unavailable right now.").waitFor();
    await check("login");
    await page.goto("http://localhost:3001/register");
    await page.getByText("Google sign-in is unavailable right now.").waitFor();
    await check("register");
    // Exercise the configured provider branch without an external Google account or network.
    googleEnabled = true;
    await context.route("https://accounts.google.com/gsi/client", (route) => route.fulfill({ contentType: "text/javascript", body: "window.google={accounts:{id:{initialize(c){window.testGoogleClient=c.client_id},renderButton(p){const b=document.createElement('button');b.textContent='Continue with Google';p.appendChild(b)}}}};" }));
    await page.reload();
    await page.getByRole("button", { name: "Continue with Google" }).waitFor();
    assert.equal(await page.evaluate(() => window.testGoogleClient), "browser-test.apps.googleusercontent.com");
    signedIn = true;
    await page.goto("http://localhost:3001/spaces");
    await page.getByRole("heading", { name: /Good (morning|afternoon|evening)/ }).waitFor();
    await check("dashboard");
    await page.getByRole("link", { name: /Continue your challenge/ }).click();
    await page.getByRole("heading", { name: /Build with Java/ }).waitFor();
    await check("challenge");
    // An assisted completion produces a return suggestion; later independence creates a receipt.
    guide.progress[0] = { ...progress[0], completed: true, independent: false, attempts: 1, assistance: "HINTS" };
    guide.activity = [{ day: 1, at: "2026-11-01T10:00:00Z", completed: true, assistance: "HINTS", reflection: "Try rebuilding the loop from memory." }];
    await page.reload();
    await page.getByRole("link", { name: "Choose this rebuild" }).click();
    assert.equal(await page.locator("#exercise-brief").getByText("Day 1", { exact: true }).count(), 1);
    await check("rebuild");
    await page.evaluate(() => document.documentElement.classList.add("dark"));
    await check("challenge-dark");
    assert.deepEqual(failures, [], `Browser errors at ${width}`);
    console.log(`Layout and provider checks passed at ${width}px.`);
    await context.close();
  }
} finally { await browser.close(); }
