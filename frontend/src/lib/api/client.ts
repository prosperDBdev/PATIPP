import type { ProblemDetail } from "./types";

const BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8081";

/**
 * The access token lives in a module variable, never in localStorage or a readable cookie.
 *
 * <p>That is the whole point of the split: script cannot read it out of storage, and it
 * expires in fifteen minutes anyway. Losing it on a page reload is fine because the
 * httpOnly refresh cookie can mint a new one, which is what bootstrapSession does.
 */
let accessToken: string | null = null;

export function setAccessToken(token: string | null) {
  accessToken = token;
}

export function getAccessToken() {
  return accessToken;
}

/** An error carrying the server's problem+json, so callers can branch on `code`. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly problem: ProblemDetail | null;

  constructor(status: number, problem: ProblemDetail | null, fallback: string) {
    super(problem?.detail ?? fallback);
    this.name = "ApiError";
    this.status = status;
    this.problem = problem;
    this.code = problem?.code ?? "unknown";
  }

  /** Field errors from bean validation, keyed by field name. */
  fieldErrors(): Record<string, string> {
    const errors: Record<string, string> = {};
    for (const entry of this.problem?.errors ?? []) {
      errors[entry.field] = entry.message;
    }
    return errors;
  }
}

interface RequestOptions {
  method?: string;
  body?: unknown;
  /** Set for the auth endpoints themselves, so a failed refresh cannot recurse. */
  skipRefresh?: boolean;
  signal?: AbortSignal;
}

async function rawRequest(path: string, options: RequestOptions): Promise<Response> {
  const headers: Record<string, string> = {};
  if (options.body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  if (accessToken) {
    headers["Authorization"] = `Bearer ${accessToken}`;
  }

  return fetch(`${BASE_URL}${path}`, {
    method: options.method ?? "GET",
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    // Required so the browser attaches the refresh cookie on the auth endpoints.
    credentials: "include",
    signal: options.signal,
  });
}

/*
 * A single in-flight refresh, shared by every caller.
 *
 * Without this, a page that fires five requests on mount would, on an expired token, send
 * five refreshes at once. Four of them would present a token the first had already
 * rotated, the server would correctly read that as reuse, and it would log the user out of
 * everything. The shared promise is not an optimisation - it is what stops the app from
 * triggering its own theft detection.
 */
let refreshInFlight: Promise<boolean> | null = null;

function refreshSession(): Promise<boolean> {
  if (!refreshInFlight) {
    refreshInFlight = rawRequest("/api/v1/auth/refresh", {
      method: "POST",
      skipRefresh: true,
    })
      .then(async (response) => {
        if (!response.ok) {
          setAccessToken(null);
          return false;
        }
        const body = await response.json();
        setAccessToken(body.accessToken);
        return true;
      })
      .catch(() => {
        setAccessToken(null);
        return false;
      })
      .finally(() => {
        refreshInFlight = null;
      });
  }
  return refreshInFlight;
}

async function toApiError(response: Response): Promise<ApiError> {
  let problem: ProblemDetail | null = null;
  try {
    problem = (await response.json()) as ProblemDetail;
  } catch {
    // A non-JSON error body (a proxy timeout page, say) is still an error.
  }
  return new ApiError(response.status, problem, `Request failed (${response.status})`);
}

export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  let response = await rawRequest(path, options);

  if (response.status === 401 && !options.skipRefresh) {
    const refreshed = await refreshSession();
    if (refreshed) {
      response = await rawRequest(path, options);
    }
  }

  if (!response.ok) {
    throw await toApiError(response);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

/**
 * Restores a session on page load from the httpOnly refresh cookie.
 *
 * <p>Returns false when there is no valid cookie, which is the normal signed-out case and
 * not an error.
 */
export async function bootstrapSession(): Promise<boolean> {
  return refreshSession();
}
