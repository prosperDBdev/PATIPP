"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { Alert, Button, Card, EmptyState, Input, Spinner } from "@/components/ui";
import { api, ApiError } from "@/lib/api/client";
import type { Space, Subject, Topic } from "@/lib/api/types";

const MAX_DEPTH = 2;

export default function CurriculumPage() {
  const { spaceId } = useParams<{ spaceId: string }>();

  const [space, setSpace] = useState<Space | null>(null);
  const [subjects, setSubjects] = useState<Subject[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [newSubject, setNewSubject] = useState("");
  const [addingSubject, setAddingSubject] = useState(false);

  const loadCurriculum = useCallback(async () => {
    setSubjects(await api<Subject[]>(`/api/v1/spaces/${spaceId}/curriculum`));
  }, [spaceId]);

  useEffect(() => {
    (async () => {
      try {
        const [loadedSpace] = await Promise.all([
          api<Space>(`/api/v1/spaces/${spaceId}`),
          loadCurriculum(),
        ]);
        setSpace(loadedSpace);
      } catch (caught) {
        setError(
          caught instanceof ApiError && caught.status === 404
            ? "That space does not exist, or is not yours."
            : "Could not load the curriculum.",
        );
      }
    })();
  }, [spaceId, loadCurriculum]);

  async function addSubject(event: React.FormEvent) {
    event.preventDefault();
    const name = newSubject.trim();
    if (!name) return;

    setAddingSubject(true);
    setError(null);
    try {
      await api<Subject>(`/api/v1/spaces/${spaceId}/subjects`, {
        method: "POST",
        body: { name },
      });
      setNewSubject("");
      await loadCurriculum();
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "Could not add that subject.");
    } finally {
      setAddingSubject(false);
    }
  }

  async function archiveSubject(subjectId: string, name: string) {
    if (!confirm(`Archive "${name}" and all of its topics?`)) return;
    setError(null);
    try {
      await api<void>(`/api/v1/spaces/${spaceId}/subjects/${subjectId}`, { method: "DELETE" });
      await loadCurriculum();
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "Could not archive that subject.");
    }
  }

  if (error && !subjects) {
    return (
      <div className="flex flex-col gap-4">
        <Alert>{error}</Alert>
        <Link href="/" className="text-sm text-accent hover:underline">
          &larr; Back to spaces
        </Link>
      </div>
    );
  }

  if (!space || !subjects) {
    return (
      <div className="grid place-items-center py-20">
        <Spinner className="size-5 text-text-faint" />
      </div>
    );
  }

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-8">
      <header className="flex flex-col gap-1.5">
        <Link href={`/spaces/${spaceId}`} className="text-sm text-text-muted hover:text-text">
          &larr; {space.name}
        </Link>
        <h1 className="text-2xl font-semibold tracking-tight text-text">Curriculum</h1>
        <p className="text-sm text-text-muted">
          Subjects are what results are reported against. Topics nest up to three levels
          deep, so &ldquo;React &rarr; Hooks &rarr; useEffect&rdquo; is as specific as it gets.
        </p>
      </header>

      {error && <Alert>{error}</Alert>}

      <form onSubmit={addSubject} className="flex gap-2">
        <Input
          value={newSubject}
          onChange={(e) => setNewSubject(e.target.value)}
          placeholder="Add a subject, e.g. React Native"
          aria-label="New subject name"
          maxLength={120}
        />
        <Button type="submit" loading={addingSubject} disabled={!newSubject.trim()}>
          Add
        </Button>
      </form>

      {subjects.length === 0 ? (
        <EmptyState
          title="No subjects yet"
          description="Add the subjects this space covers. For a NIIT semester that might be HTML, CSS, JavaScript, React and React Native."
        />
      ) : (
        <ul className="flex flex-col gap-4">
          {subjects.map((subject) => (
            <li key={subject.id}>
              <SubjectSection
                spaceId={spaceId}
                subject={subject}
                onChanged={loadCurriculum}
                onArchive={() => archiveSubject(subject.id, subject.name)}
                onError={setError}
              />
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function SubjectSection({
  spaceId,
  subject,
  onChanged,
  onArchive,
  onError,
}: {
  spaceId: string;
  subject: Subject;
  onChanged: () => Promise<void>;
  onArchive: () => void;
  onError: (message: string) => void;
}) {
  const [newTopic, setNewTopic] = useState("");
  const [adding, setAdding] = useState(false);

  async function addTopic(event: React.FormEvent) {
    event.preventDefault();
    const name = newTopic.trim();
    if (!name) return;

    setAdding(true);
    try {
      await api<Topic>(`/api/v1/spaces/${spaceId}/topics`, {
        method: "POST",
        body: { subjectId: subject.id, name },
      });
      setNewTopic("");
      await onChanged();
    } catch (caught) {
      onError(caught instanceof ApiError ? caught.message : "Could not add that topic.");
    } finally {
      setAdding(false);
    }
  }

  return (
    <Card className="flex flex-col">
      <div className="flex items-center justify-between gap-3 border-b border-border px-4 py-3">
        <div className="flex items-center gap-2">
          <span
            aria-hidden="true"
            className="size-2 rounded-full"
            style={{ background: subject.color ?? "var(--border-strong)" }}
          />
          <h2 className="text-sm font-semibold text-text">{subject.name}</h2>
        </div>
        <Button variant="ghost" size="sm" onClick={onArchive}>
          Archive
        </Button>
      </div>

      <div className="flex flex-col gap-1 px-4 py-3">
        {subject.topics.length === 0 ? (
          <p className="py-1 text-sm text-text-faint">No topics yet.</p>
        ) : (
          <ul className="flex flex-col">
            {subject.topics.map((topic) => (
              <TopicRow
                key={topic.id}
                spaceId={spaceId}
                topic={topic}
                onChanged={onChanged}
                onError={onError}
              />
            ))}
          </ul>
        )}

        <form onSubmit={addTopic} className="mt-2 flex gap-2">
          <Input
            value={newTopic}
            onChange={(e) => setNewTopic(e.target.value)}
            placeholder={`Add a topic to ${subject.name}`}
            aria-label={`New topic in ${subject.name}`}
            className="h-9 text-[13px]"
            maxLength={120}
          />
          <Button type="submit" variant="secondary" size="sm" loading={adding} disabled={!newTopic.trim()}>
            Add topic
          </Button>
        </form>
      </div>
    </Card>
  );
}

function TopicRow({
  spaceId,
  topic,
  onChanged,
  onError,
}: {
  spaceId: string;
  topic: Topic;
  onChanged: () => Promise<void>;
  onError: (message: string) => void;
}) {
  const [addingChild, setAddingChild] = useState(false);
  const [childName, setChildName] = useState("");
  const [busy, setBusy] = useState(false);

  const canNest = topic.depth < MAX_DEPTH;

  async function addChild(event: React.FormEvent) {
    event.preventDefault();
    const name = childName.trim();
    if (!name) return;

    setBusy(true);
    try {
      await api<Topic>(`/api/v1/spaces/${spaceId}/topics`, {
        method: "POST",
        body: { parentTopicId: topic.id, name },
      });
      setChildName("");
      setAddingChild(false);
      await onChanged();
    } catch (caught) {
      onError(caught instanceof ApiError ? caught.message : "Could not add that subtopic.");
    } finally {
      setBusy(false);
    }
  }

  async function archive() {
    if (!confirm(`Archive "${topic.name}"${topic.children.length ? " and its subtopics" : ""}?`))
      return;
    try {
      await api<void>(`/api/v1/spaces/${spaceId}/topics/${topic.id}`, { method: "DELETE" });
      await onChanged();
    } catch (caught) {
      onError(caught instanceof ApiError ? caught.message : "Could not archive that topic.");
    }
  }

  return (
    <li>
      <div
        className="group flex items-center gap-2 rounded py-1.5 hover:bg-surface-2"
        style={{ paddingLeft: `${topic.depth * 20}px` }}
      >
        <span aria-hidden="true" className="text-text-faint">
          {topic.depth === 0 ? "•" : "–"}
        </span>
        <span className="text-[13px] text-text">{topic.name}</span>

        <span className="ml-auto flex items-center gap-1 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100">
          {canNest && (
            <Button variant="ghost" size="sm" onClick={() => setAddingChild((open) => !open)}>
              + Subtopic
            </Button>
          )}
          <Button variant="ghost" size="sm" onClick={archive}>
            Archive
          </Button>
        </span>
      </div>

      {addingChild && (
        <form
          onSubmit={addChild}
          className="flex gap-2 py-1.5"
          style={{ paddingLeft: `${(topic.depth + 1) * 20}px` }}
        >
          <Input
            value={childName}
            onChange={(e) => setChildName(e.target.value)}
            placeholder={`Subtopic of ${topic.name}`}
            aria-label={`New subtopic of ${topic.name}`}
            className="h-8 text-[13px]"
            autoFocus
            maxLength={120}
          />
          <Button type="submit" variant="secondary" size="sm" loading={busy} disabled={!childName.trim()}>
            Add
          </Button>
        </form>
      )}

      {topic.children.length > 0 && (
        <ul className="flex flex-col">
          {topic.children.map((child) => (
            <TopicRow
              key={child.id}
              spaceId={spaceId}
              topic={child}
              onChanged={onChanged}
              onError={onError}
            />
          ))}
        </ul>
      )}
    </li>
  );
}
