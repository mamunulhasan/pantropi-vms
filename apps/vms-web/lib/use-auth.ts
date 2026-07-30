"use client";

import { useEffect, useSyncExternalStore } from "react";
import { getSnapshot, resumeSession, subscribe, type AuthState } from "./auth-store";

let resumeStarted = false;

/**
 * The auth store, as React state (US-06.3.1).
 *
 * `useSyncExternalStore` because the session is module state shared by non-React code (the API
 * client's refresh path) — a context would duplicate it. The first mounted consumer kicks off the
 * one resume attempt; `resuming` stays true until it settles so guards can hold rather than flash
 * a redirect at someone whose cookie was about to restore them.
 */
export function useAuth(): AuthState {
  const state = useSyncExternalStore(subscribe, getSnapshot, getSnapshot);

  useEffect(() => {
    if (!resumeStarted) {
      resumeStarted = true;
      void resumeSession();
    }
  }, []);

  return state;
}
