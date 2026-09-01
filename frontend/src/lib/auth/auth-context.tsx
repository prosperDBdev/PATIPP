"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { api, bootstrapSession, setAccessToken } from "@/lib/api/client";
import type { AuthResponse, Profile } from "@/lib/api/types";

interface AuthState {
  user: Profile | null;
  /** True until the initial refresh attempt settles. Guards against a signed-in flash. */
  loading: boolean;
  login: (email: string, password: string) => Promise<void>;
  register: (input: RegisterInput) => Promise<void>;
  logout: () => Promise<void>;
  refreshUser: () => Promise<void>;
}

export interface RegisterInput {
  email: string;
  password: string;
  displayName: string;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<Profile | null>(null);
  const [loading, setLoading] = useState(true);

  const loadProfile = useCallback(async () => {
    const profile = await api<Profile>("/api/v1/users/me");
    setUser(profile);
  }, []);

  useEffect(() => {
    let cancelled = false;

    // The access token is deliberately not persisted, so every page load starts signed out
    // and asks the refresh cookie whether that is actually true.
    (async () => {
      try {
        const restored = await bootstrapSession();
        if (restored && !cancelled) {
          await loadProfile();
        }
      } catch {
        setAccessToken(null);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [loadProfile]);

  const login = useCallback(async (email: string, password: string) => {
    const response = await api<AuthResponse>("/api/v1/auth/login", {
      method: "POST",
      body: { email, password },
      skipRefresh: true,
    });
    setAccessToken(response.accessToken);
    await loadProfile();
  }, [loadProfile]);

  const register = useCallback(async (input: RegisterInput) => {
    const response = await api<AuthResponse>("/api/v1/auth/register", {
      method: "POST",
      body: {
        ...input,
        // The browser knows this and the server uses it for streaks and "today".
        timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
      },
      skipRefresh: true,
    });
    setAccessToken(response.accessToken);
    await loadProfile();
  }, [loadProfile]);

  const logout = useCallback(async () => {
    try {
      await api<void>("/api/v1/auth/logout", { method: "POST", skipRefresh: true });
    } finally {
      // Clear locally even if the call failed: the user asked to be signed out, and
      // leaving them looking signed in would be worse than a stale server-side session.
      setAccessToken(null);
      setUser(null);
    }
  }, []);

  const value = useMemo<AuthState>(
    () => ({ user, loading, login, register, logout, refreshUser: loadProfile }),
    [user, loading, login, register, logout, loadProfile],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth must be used inside <AuthProvider>");
  }
  return context;
}
