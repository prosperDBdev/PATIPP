"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { Alert, Button, Card, Field, Input, Spinner, Textarea } from "@/components/ui";
import { api, ApiError } from "@/lib/api/client";
import type { PreparationType, Space } from "@/lib/api/types";

export default function NewSpacePage() {
  const router = useRouter();

  const [types, setTypes] = useState<PreparationType[] | null>(null);
  const [typeId, setTypeId] = useState<string>("");
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [targetDate, setTargetDate] = useState("");
  const [targetScore, setTargetScore] = useState("");

  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    api<PreparationType[]>("/api/v1/preparation-types")
      .then((loaded) => {
        setTypes(loaded);
        // Preselect nothing: the type shapes the whole space, so it deserves a real choice.
      })
      .catch(() => setError("Could not load preparation types."));
  }, []);

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    setFieldErrors({});
    setSubmitting(true);

    try {
      const created = await api<Space>("/api/v1/spaces", {
        method: "POST",
        body: {
          preparationTypeId: typeId,
          name,
          description: description || null,
          targetDate: targetDate || null,
          targetScore: targetScore ? Number(targetScore) : null,
        },
      });
      router.replace(`/spaces/${created.id}`);
    } catch (caught) {
      if (caught instanceof ApiError) {
        const fields = caught.fieldErrors();
        if (Object.keys(fields).length > 0) setFieldErrors(fields);
        else setError(caught.message);
      } else {
        setError("Could not create the space.");
      }
      setSubmitting(false);
    }
  }

  const selected = types?.find((type) => type.id === typeId);

  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-8">
      <div className="flex flex-col gap-1.5">
        <Link href="/spaces" className="text-sm text-text-muted hover:text-text">
          &larr; Back to spaces
        </Link>
        <h1 className="text-2xl font-semibold tracking-tight text-text">
          New preparation space
        </h1>
        <p className="text-sm text-text-muted">
          The type decides which question types, session formats and scoring rules apply.
          You can change the details later.
        </p>
      </div>

      <form onSubmit={handleSubmit} className="flex flex-col gap-6" noValidate>
        {error && <Alert>{error}</Alert>}

        <fieldset className="flex flex-col gap-3">
          <legend className="mb-2 text-[13px] font-medium text-text">
            What are you preparing for?
          </legend>

          {types === null ? (
            <div className="grid place-items-center py-8">
              <Spinner className="size-5 text-text-faint" />
            </div>
          ) : (
            <div className="grid gap-2 sm:grid-cols-2">
              {types.map((type) => (
                <label
                  key={type.id}
                  className={`cursor-pointer rounded-lg border p-3.5 transition-colors ${
                    typeId === type.id
                      ? "border-accent bg-accent-soft"
                      : "border-border bg-surface hover:border-border-strong"
                  }`}
                >
                  <input
                    type="radio"
                    name="preparationType"
                    value={type.id}
                    checked={typeId === type.id}
                    onChange={() => setTypeId(type.id)}
                    className="sr-only"
                    required
                  />
                  <span className="block text-sm font-medium text-text">{type.name}</span>
                  {type.description && (
                    <span className="mt-1 block text-xs leading-relaxed text-text-muted">
                      {type.description}
                    </span>
                  )}
                </label>
              ))}
            </div>
          )}
        </fieldset>

        {/* Reading straight from the blueprint proves the type is data, not a hardcoded branch. */}
        {selected && (
          <Card className="flex flex-col gap-2 p-4 text-xs text-text-muted">
            <span className="font-mono text-[10px] tracking-wider text-text-faint uppercase">
              This type provides
            </span>
            <span>
              <strong className="text-text">Question types:</strong>{" "}
              {selected.blueprint.allowedQuestionTypes?.join(", ") ?? "any"}
            </span>
            <span>
              <strong className="text-text">Session modes:</strong>{" "}
              {selected.blueprint.sessionModes?.join(", ") ?? "any"}
            </span>
          </Card>
        )}

        <Field
          label="Name"
          htmlFor="name"
          error={fieldErrors.name}
          hint="For example: NIIT Semester 2 Exams"
        >
          <Input
            id="name"
            required
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="NIIT Semester 2 Exams"
            invalid={Boolean(fieldErrors.name)}
          />
        </Field>

        <Field label="Description" htmlFor="description" hint="Optional.">
          <Textarea
            id="description"
            rows={2}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="What this space covers"
          />
        </Field>

        <div className="grid gap-4 sm:grid-cols-2">
          <Field
            label="Target date"
            htmlFor="targetDate"
            error={fieldErrors.targetDate}
            hint="Optional. Drives the countdown."
          >
            <Input
              id="targetDate"
              type="date"
              value={targetDate}
              onChange={(e) => setTargetDate(e.target.value)}
            />
          </Field>

          <Field
            label="Target score"
            htmlFor="targetScore"
            error={fieldErrors.targetScore}
            hint="Optional. 0-100."
          >
            <Input
              id="targetScore"
              type="number"
              min={0}
              max={100}
              value={targetScore}
              onChange={(e) => setTargetScore(e.target.value)}
              placeholder="85"
            />
          </Field>
        </div>

        <div className="flex gap-3">
          <Button type="submit" loading={submitting} disabled={!typeId || !name}>
            Create space
          </Button>
          <Button type="button" variant="secondary" onClick={() => router.push("/spaces")}>
            Cancel
          </Button>
        </div>
      </form>
    </div>
  );
}
