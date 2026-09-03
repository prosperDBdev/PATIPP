"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { Alert, Button, Field, Spinner, Textarea, cn } from "@/components/ui";
import { api, ApiError } from "@/lib/api/client";
import type { ImportReport, ImportRow, Subject } from "@/lib/api/types";

const CSV_TEMPLATE = `type,stem,difficulty,subject,topic,option1,option2,option3,correct,answer,acceptedAnswers,back,explanation,tags
MCQ,Which keyword declares a block-scoped constant?,EASY,JavaScript,,var,let,const,c,,,,const is block scoped and cannot be reassigned.,scope
TRUE_FALSE,let is function scoped.,EASY,JavaScript,,,,,,false,,,No - let is block scoped.,scope
SHORT_ANSWER,What runs asynchronous callbacks in JavaScript?,MEDIUM,JavaScript,,,,,,,event loop,,The event loop pulls from the task queue.,async
FLASHCARD,Closure,EASY,JavaScript,,,,,,,,A function together with its captured scope.,,memory`;

export default function ImportPage() {
  const { spaceId } = useParams<{ spaceId: string }>();
  const router = useRouter();

  const [format, setFormat] = useState<"CSV" | "JSON">("CSV");
  const [content, setContent] = useState("");
  const [defaultSubjectId, setDefaultSubjectId] = useState("");
  const [subjects, setSubjects] = useState<Subject[]>([]);

  const [report, setReport] = useState<ImportReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const loaded = await api<Subject[]>(`/api/v1/spaces/${spaceId}/curriculum`);
        if (!cancelled) setSubjects(loaded);
      } catch {
        if (!cancelled) setError("Could not load this space.");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [spaceId]);

  async function run(dryRun: boolean) {
    setError(null);
    setBusy(true);
    try {
      const result = await api<ImportReport>(`/api/v1/spaces/${spaceId}/questions/import`, {
        method: "POST",
        body: {
          format,
          content,
          defaultSubjectId: defaultSubjectId || null,
          dryRun,
        },
      });
      setReport(result);
      if (!dryRun && result.imported > 0) {
        // Nothing more to do here once the rows are in the bank.
        router.push(`/spaces/${spaceId}/questions`);
      }
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "Could not read that file.");
    } finally {
      setBusy(false);
    }
  }

  async function readFile(file: File) {
    const text = await file.text();
    setContent(text);
    setFormat(file.name.toLowerCase().endsWith(".json") ? "JSON" : "CSV");
    setReport(null);
  }

  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-6">
      <header className="flex flex-col gap-1.5">
        <Link
          href={`/spaces/${spaceId}/questions`}
          className="text-sm text-text-muted hover:text-text"
        >
          &larr; Back to questions
        </Link>
        <h1 className="text-2xl font-semibold tracking-tight text-text">Import questions</h1>
        <p className="max-w-prose text-sm text-text-muted">
          Nothing is written until you say so. Check the file first, read the report row by
          row, then import.
        </p>
      </header>

      {error && <Alert>{error}</Alert>}

      <div className="flex flex-wrap items-center gap-2">
        {(["CSV", "JSON"] as const).map((option) => (
          <button
            key={option}
            type="button"
            onClick={() => {
              setFormat(option);
              setReport(null);
            }}
            className={cn(
              "h-9 rounded-md border px-3 text-[13px] font-medium transition-colors",
              format === option
                ? "border-accent bg-accent-soft text-accent"
                : "border-border bg-surface text-text-muted hover:border-border-strong",
            )}
          >
            {option}
          </button>
        ))}

        <input
          ref={fileInput}
          type="file"
          accept=".csv,.json,text/csv,application/json"
          className="hidden"
          onChange={(e) => {
            const file = e.target.files?.[0];
            if (file) readFile(file);
          }}
        />
        <Button variant="secondary" size="sm" onClick={() => fileInput.current?.click()}>
          Choose a file
        </Button>

        {format === "CSV" && (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              setContent(CSV_TEMPLATE);
              setReport(null);
            }}
          >
            Paste an example
          </Button>
        )}

        <div className="ml-auto">
          <select
            value={defaultSubjectId}
            onChange={(e) => setDefaultSubjectId(e.target.value)}
            aria-label="Default subject"
            className="h-9 rounded-md border border-border bg-surface px-2 text-[13px] text-text hover:border-border-strong"
          >
            <option value="">Default subject: none</option>
            {subjects.map((subject) => (
              <option key={subject.id} value={subject.id}>
                Default: {subject.name}
              </option>
            ))}
          </select>
        </div>
      </div>

      <Field
        label={format === "CSV" ? "Paste your CSV" : "Paste your JSON"}
        htmlFor="content"
        hint={
          format === "CSV"
            ? "First row is the header. Subjects and topics are matched by name."
            : "An array of questions, or an object with a questions array. The same shape export produces."
        }
      >
        <Textarea
          id="content"
          rows={12}
          value={content}
          onChange={(e) => {
            setContent(e.target.value);
            setReport(null);
          }}
          className="font-mono text-xs"
          placeholder={format === "CSV" ? CSV_TEMPLATE : '{ "questions": [ ... ] }'}
        />
      </Field>

      <div className="flex flex-wrap gap-3">
        <Button onClick={() => run(true)} loading={busy} disabled={!content.trim()}>
          Check the file
        </Button>
        {report?.dryRun && report.valid > 0 && (
          <Button variant="secondary" onClick={() => run(false)} loading={busy}>
            Import {report.valid} question{report.valid === 1 ? "" : "s"}
          </Button>
        )}
      </div>

      {busy && !report && (
        <div className="grid place-items-center py-8">
          <Spinner className="size-5 text-text-faint" />
        </div>
      )}

      {report && <Report report={report} />}
    </div>
  );
}

function Report({ report }: { report: ImportReport }) {
  return (
    <section className="flex flex-col gap-3">
      <div className="grid grid-cols-2 gap-px overflow-hidden rounded-lg border border-border bg-border sm:grid-cols-4">
        <Stat label="Rows" value={report.totalRows} />
        <Stat label={report.dryRun ? "Ready" : "Imported"} value={report.dryRun ? report.valid : report.imported} tone="good" />
        <Stat label="Duplicates" value={report.duplicates} tone={report.duplicates ? "warn" : undefined} />
        <Stat label="Problems" value={report.invalid} tone={report.invalid ? "bad" : undefined} />
      </div>

      {report.dryRun && (
        <p className="text-sm text-text-muted">
          Nothing has been written yet. {report.valid > 0
            ? `Import will add ${report.valid} question${report.valid === 1 ? "" : "s"} and skip the rest.`
            : "There is nothing importable in this file yet."}
        </p>
      )}

      <div className="overflow-x-auto rounded-lg border border-border bg-surface">
        <table className="w-full min-w-[640px] text-left text-[13px]">
          <thead>
            <tr className="border-b border-border bg-surface-2">
              <Th>Line</Th>
              <Th>Outcome</Th>
              <Th>Type</Th>
              <Th>Question</Th>
              <Th>Problems</Th>
            </tr>
          </thead>
          <tbody>
            {report.rows.map((row) => (
              <Row key={row.line} row={row} />
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function Row({ row }: { row: ImportRow }) {
  const tone =
    row.outcome === "INVALID"
      ? "text-danger"
      : row.outcome === "DUPLICATE"
        ? "text-warning"
        : "text-success";

  return (
    <tr className="border-b border-border last:border-b-0">
      <td className="px-3 py-2 font-mono text-xs text-text-faint tabular-nums">{row.line}</td>
      <td className={cn("px-3 py-2 font-mono text-[11px] font-bold tracking-wide", tone)}>
        {row.outcome}
      </td>
      <td className="px-3 py-2 font-mono text-[11px] text-text-muted">{row.type}</td>
      <td className="max-w-xs truncate px-3 py-2 text-text">{row.stem || <em>(blank)</em>}</td>
      <td className="px-3 py-2 text-text-muted">
        {row.problems.length === 0 ? (
          "—"
        ) : (
          <ul className="flex flex-col gap-0.5">
            {row.problems.map((problem, index) => (
              <li key={index} className="text-xs">
                <span className="font-mono text-text-faint">{problem.field}</span>{" "}
                {problem.message}
              </li>
            ))}
          </ul>
        )}
      </td>
    </tr>
  );
}

function Th({ children }: { children: React.ReactNode }) {
  return (
    <th className="px-3 py-2 font-mono text-[10px] font-bold tracking-wider text-text-muted uppercase">
      {children}
    </th>
  );
}

function Stat({
  label,
  value,
  tone,
}: {
  label: string;
  value: number;
  tone?: "good" | "warn" | "bad";
}) {
  const colour =
    tone === "good"
      ? "text-success"
      : tone === "warn"
        ? "text-warning"
        : tone === "bad"
          ? "text-danger"
          : "text-text";

  return (
    <div className="bg-surface px-4 py-3">
      <div className={cn("text-xl font-semibold tabular-nums", colour)}>{value}</div>
      <div className="mt-0.5 font-mono text-[10px] tracking-wider text-text-faint uppercase">
        {label}
      </div>
    </div>
  );
}
