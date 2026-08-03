"use client";

/**
 * Shows an issued pass: the QR image, the window it is valid for, and where the email went.
 *
 * The QR arrives as a PNG from the portal's own `/api/pass` route, fetched as a blob so the
 * request can carry the access token — an `<img src>` cannot set an Authorization header. The
 * payload behind the image never reaches this component in any form (US-09.5.1 AC-3).
 *
 * The object URL is revoked when the dialog closes. Without that, every pass viewed in a session
 * would hold its image in memory until the tab was closed, which for this particular image is a
 * longer life than it should have.
 */
import { useCallback, useEffect, useState } from "react";
import { Dialog } from "@/components/ui/Dialog";
import { Button } from "@/components/ui/Button";
import { getAccessToken } from "@/lib/auth-store";
import { formatWindow } from "@/lib/datetime";

export type PassSubject = {
  visitorId: string;
  visitorName: string;
  validFrom?: string | null;
  validTo?: string | null;
  /** Where the notification was written, when the API reported one. */
  notifiedTo?: string | null;
};

export function PassDialog({
  subjects,
  onClose,
}: {
  /** Empty or absent closes the dialog. More than one gets a visitor selector. */
  subjects: readonly PassSubject[];
  onClose: () => void;
}) {
  const [imageUrl, setImageUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [selected, setSelected] = useState(0);

  // Clamped rather than trusted: the list can shrink between renders, and an index past its end
  // would blank the dialog with no explanation.
  const subject = subjects.length > 0 ? (subjects[Math.min(selected, subjects.length - 1)] ?? null) : null;
  const visitorId = subject?.visitorId ?? null;

  const load = useCallback(async (id: string): Promise<string> => {
    const response = await fetch("/api/pass", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        authorization: `Bearer ${getAccessToken() ?? ""}`,
      },
      body: JSON.stringify({ visitorId: id }),
    });
    if (!response.ok) {
      const problem = (await response.json().catch(() => null)) as { detail?: string } | null;
      throw new Error(problem?.detail ?? "The pass could not be shown.");
    }
    return URL.createObjectURL(await response.blob());
  }, []);

  useEffect(() => {
    if (!visitorId) {
      return;
    }
    let url: string | null = null;
    let cancelled = false;

    setLoading(true);
    setError(null);
    load(visitorId)
      .then((created) => {
        url = created;
        // A dialog closed mid-flight must not leak the object it no longer shows.
        if (cancelled) {
          URL.revokeObjectURL(created);
          return;
        }
        setImageUrl(created);
      })
      .catch((e: Error) => {
        if (!cancelled) setError(e.message);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
      setImageUrl(null);
      if (url) URL.revokeObjectURL(url);
    };
  }, [visitorId, load]);

  if (!subject) {
    return null;
  }

  return (
    <Dialog
      open
      onClose={onClose}
      title={subjects.length > 1 ? `Passes (${subjects.length})` : `Pass for ${subject.visitorName}`}
    >
      <div className="space-y-4">
        {subjects.length > 1 && (
          <div className="flex flex-wrap gap-2" role="group" aria-label="Choose a visitor">
            {subjects.map((s, i) => (
              <Button
                key={s.visitorId}
                variant={i === selected ? "primary" : "secondary"}
                onClick={() => setSelected(i)}
                aria-pressed={i === selected}
              >
                {s.visitorName}
              </Button>
            ))}
          </div>
        )}

        <div className="flex justify-center rounded-md border border-border bg-surface p-4">
          {loading && <p className="py-16 text-sm text-text-muted">Preparing the pass…</p>}
          {error && (
            <p role="alert" className="py-16 text-sm text-danger">
              {error}
            </p>
          )}
          {imageUrl && (
            /* eslint-disable-next-line @next/next/no-img-element -- a blob URL, not an asset */
            <img
              src={imageUrl}
              width={320}
              height={320}
              alt={`Entry pass QR code for ${subject.visitorName}`}
            />
          )}
        </div>

        <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
          <dt className="text-text-muted">Visitor</dt>
          <dd>{subject.visitorName}</dd>
          {subject.validFrom && subject.validTo && (
            <>
              <dt className="text-text-muted">Valid</dt>
              <dd>{formatWindow(subject.validFrom, subject.validTo)}</dd>
            </>
          )}
          {subject.notifiedTo && (
            <>
              <dt className="text-text-muted">Emailed to</dt>
              <dd>{subject.notifiedTo}</dd>
            </>
          )}
        </dl>

        <p className="text-xs text-text-muted">
          Show this code at the barrier. It is not sent by email — ask the visitor to open their
          pass here on arrival.
        </p>

        <div className="flex justify-end">
          <Button onClick={onClose}>Close</Button>
        </div>
      </div>
    </Dialog>
  );
}
