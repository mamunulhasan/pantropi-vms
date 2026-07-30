"use client";

/**
 * The three non-content states every data screen has (US-06.2.1 AC-5).
 *
 * `ErrorState` is the deliberate one: it shows the problem body's `detail` (which the API writes
 * to be safe for users) and the correlation id to quote at support — and **never** the raw
 * payload, an exception class, or an `Error#message` that was written for a developer.
 */
import { type ReactNode } from "react";
import { ApiError } from "@/lib/api";
import { Button } from "./Button";

export function LoadingState({ label = "Loading…" }: { label?: string }) {
  return (
    <div role="status" className="flex items-center justify-center p-12">
      <p className="text-sm text-text-muted">{label}</p>
    </div>
  );
}

export function EmptyState({
  title,
  description,
  action,
}: {
  title: string;
  description?: string;
  /** Usually the create button — an empty list should say what to do about it. */
  action?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 p-12 text-center">
      <h2 className="text-base font-semibold text-text">{title}</h2>
      {description && <p className="max-w-md text-sm text-text-muted">{description}</p>}
      {action && <div className="mt-2">{action}</div>}
    </div>
  );
}

export function ErrorState({
  error,
  retry,
}: {
  error: unknown;
  /** Re-runs the failed load. Rendered as the only action — reload-the-app is not advice. */
  retry?: () => void;
}) {
  const apiError = error instanceof ApiError ? error : null;
  // A non-API Error's message was written for a developer log, not a user — never show it.
  const message = apiError?.detail ?? "Something went wrong loading this. Try again.";
  const correlationId = apiError?.correlationId ?? null;

  return (
    <div role="alert" className="flex flex-col items-center justify-center gap-2 p-12 text-center">
      <h2 className="text-base font-semibold text-text">{message}</h2>
      {correlationId && (
        <p className="text-sm text-text-muted">
          If this keeps happening, quote reference <code className="font-mono">{correlationId}</code>{" "}
          to support.
        </p>
      )}
      {retry && (
        <div className="mt-2">
          <Button variant="secondary" onClick={retry}>
            Try again
          </Button>
        </div>
      )}
    </div>
  );
}
