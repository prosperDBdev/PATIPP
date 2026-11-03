import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import ts from "typescript";

// Compile the pure helper for Node 20+ without adding a test runtime dependency.
const source = readFileSync(new URL("../src/lib/api/challenge.ts", import.meta.url), "utf8");
const { outputText } = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ES2020, target: ts.ScriptTarget.ES2022 } });
const { practiceDate, practiceStats, rebuildExperience } = await import(`data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`);

const attempt = (at) => ({ day: 1, at, completed: false, assistance: "INDEPENDENT", reflection: "" });

test("rebuild evidence requires a later independent completion and survives unordered history", () => {
  const history = [
    { ...attempt("2026-11-03T10:00:00Z"), completed: true },
    { ...attempt("2026-11-01T10:00:00Z"), completed: true, assistance: "HINTS" },
    { ...attempt("2026-11-02T10:00:00Z"), reflection: "Check duplicate IDs next." },
  ];
  const guide = { activity: history, progress: [], days: [] };
  assert.equal(rebuildExperience(guide).receipts.length, 1);
  assert.equal(rebuildExperience(guide).bookmark.reflection, "Check duplicate IDs next.");
  assert.equal(rebuildExperience({ ...guide, activity: history.slice(0, 1) }).receipts.length, 0);
  assert.equal(rebuildExperience({ ...guide, activity: [history[0], { ...history[1], completed: false }] }).receipts.length, 0);
  assert.equal(rebuildExperience({ ...guide, activity: [{ ...history[0], at: history[1].at }, history[1]] }).receipts.length, 0);
});

test("rebuild suggestions exclude independently completed exercises and handle an empty start", () => {
  const guide = { activity: [], days: [{ day: 1 }, { day: 2 }], progress: [
    { day: 1, completed: true, independent: true }, { day: 2, completed: true, independent: false },
  ] };
  assert.equal(rebuildExperience(guide).target.day, 2);
  assert.deepEqual(rebuildExperience({ activity: [], days: [], progress: [] }), { target: null, bookmark: null, receipts: [] });
});

test("practice dates respect the profile timezone at midnight", () => {
  assert.equal(practiceDate("2026-11-01T23:30:00Z", "Africa/Lagos"), "2026-11-02");
  assert.equal(practiceDate("2026-11-01T02:30:00Z", "America/Los_Angeles"), "2026-10-31");
});

test("unfinished attempts count as practice; repeated same-day attempts count once", () => {
  const result = practiceStats([attempt("2026-11-01T10:00:00Z"), attempt("2026-11-01T12:00:00Z")],
    "Africa/Lagos", new Date("2026-11-01T13:00:00Z"));
  assert.deepEqual(result, { days: 1, streak: 1, practisedToday: true });
});

test("a streak remains available today and breaks after a missed day", () => {
  const activity = [attempt("2026-10-30T10:00:00Z"), attempt("2026-10-31T10:00:00Z")];
  assert.equal(practiceStats(activity, "Africa/Lagos", new Date("2026-11-01T10:00:00Z")).streak, 2);
  assert.equal(practiceStats(activity, "Africa/Lagos", new Date("2026-11-02T10:00:00Z")).streak, 0);
});

test("streak arithmetic crosses month and daylight-saving boundaries by calendar date", () => {
  const activity = [attempt("2026-10-31T18:00:00Z"), attempt("2026-11-01T18:00:00Z")];
  assert.equal(practiceStats(activity, "America/New_York", new Date("2026-11-02T18:00:00Z")).streak, 2);
  assert.equal(practiceStats([], "Africa/Lagos", new Date("2026-11-01T10:00:00Z")).streak, 0);
});
