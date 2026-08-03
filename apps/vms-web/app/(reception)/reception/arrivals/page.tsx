"use client";

/**
 * The arrival desk (US-12.1.1, US-12.1.2, US-12.2.1, US-12.2.2) — FR-VMS-04 (SRS B1).
 *
 * Search, confirmation and the two lifecycle actions on one screen, because they are one moment:
 * somebody is standing there. Splitting them across pages would make a receptionist navigate while
 * a visitor waits.
 *
 * <strong>The check-in control is absent, not disabled, unless the visitor is appointed</strong>
 * (T-12.1.2.2). A disabled button invites the question "why not, and who can?"; an absent one plus
 * the stated reason answers it. `mayCheckIn` comes from the API rather than being re-derived here,
 * so a second screen cannot reach a different conclusion from the same record.
 *
 * Keyboard-first (NFR-USA-01): the search box autofocuses, Enter searches, and every row action is
 * a real button in tab order. The common path needs no mouse.
 */
import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/Button";
import { PageHeader } from "@/components/ui/PageHeader";
import { StatusTag, type TagTone } from "@/components/ui/StatusTag";
import { EmptyState, ErrorState, LoadingState } from "@/components/ui/states";
import { useToast } from "@/components/ui/Toast";
import { formatInstant, formatWindow } from "@/lib/datetime";
import {
  ArrivalsApi,
  OUTCOME_LABELS,
  arrivalError,
  isStale,
  type Arrival,
  type ConfirmationOutcome,
  type OutstandingCard,
} from "@/lib/arrivals-api";

export default function ArrivalsPage() {
  return (
    <Suspense fallback={<LoadingState />}>
      <ArrivalsScreen />
    </Suspense>
  );
}

/** Appointed is the only green. Early and already-arrived are warnings, not failures. */
const OUTCOME_TONES: Record<ConfirmationOutcome, TagTone> = {
  APPOINTED: "success",
  NOT_APPOINTED: "danger",
  EARLY: "warning",
  ELAPSED: "warning",
  ALREADY_ARRIVED: "warning",
};

function ArrivalsScreen() {
  const { toast } = useToast();
  const [draft, setDraft] = useState("");
  const [rows, setRows] = useState<Arrival[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [cards, setCards] = useState<{ name: string; cards: OutstandingCard[] } | null>(null);
  const searchRef = useRef<HTMLInputElement>(null);

  const load = useCallback((terms: string) => {
    setLoading(true);
    ArrivalsApi.search(terms)
      .then((found) => {
        setRows(found);
        setError(null);
      })
      .catch(setError)
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    load("");
  }, [load]);

  async function move(row: Arrival, direction: "in" | "out") {
    setBusyId(row.visitorId);
    try {
      const result =
        direction === "in"
          ? await ArrivalsApi.checkIn(row.visitorId)
          : await ArrivalsApi.checkOut(row.visitorId);

      toast(
        direction === "in"
          ? `${row.fullName} checked in at ${formatInstant(result.at)}.`
          : `${row.fullName} checked out at ${formatInstant(result.at)}.`,
        "success",
      );
      // The last moment anyone will see this person, so the card is asked for now (AC-2). It never
      // blocks the departure — the visitor is leaving either way.
      if (result.outstandingCards.length > 0) {
        setCards({ name: row.fullName, cards: result.outstandingCards });
      }
      load(draft);
    } catch (e) {
      toast(arrivalError(e), "error");
      if (isStale(e)) {
        load(draft);
      }
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div>
      <PageHeader
        kicker="Reception"
        title="Arrivals"
        description="Find an arriving visitor, confirm they are appointed, and record them in or out."
      />

      <form
        role="search"
        className="mt-4 flex flex-wrap items-end gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          load(draft);
        }}
      >
        <div className="min-w-0 flex-1">
          <label htmlFor="arrival-search" className="block text-sm font-medium text-text">
            Find a visitor
          </label>
          <input
            id="arrival-search"
            ref={searchRef}
            type="search"
            autoFocus
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            placeholder="Name, company, host, phone or booking reference"
            className="mt-1 w-full rounded-md border border-border bg-surface-raised px-3 py-2 text-sm text-text"
          />
        </div>
        <Button type="submit" busy={loading}>
          Search
        </Button>
        {draft && (
          <Button
            variant="secondary"
            onClick={() => {
              setDraft("");
              load("");
              searchRef.current?.focus();
            }}
          >
            Clear
          </Button>
        )}
      </form>

      {cards && (
        <div role="alert" className="mt-4 rounded-lg border border-warning bg-surface-raised p-4">
          <h2 className="text-sm font-semibold text-text">
            {cards.name} still holds {cards.cards.length === 1 ? "a card" : "cards"}
          </h2>
          <ul className="mt-2 space-y-1 text-sm text-text-muted">
            {cards.cards.map((c) => (
              <li key={c.issuanceId}>
                {c.acsCardId}
                {c.issuedAt && <> · issued {formatInstant(c.issuedAt)}</>}
              </li>
            ))}
          </ul>
          <p className="mt-2 text-xs text-text-muted">
            The check-out is recorded. Ask for the card before they leave.
          </p>
          <div className="mt-3">
            <Button variant="secondary" onClick={() => setCards(null)}>
              Dismiss
            </Button>
          </div>
        </div>
      )}

      {error ? (
        <div className="mt-4">
          <ErrorState error={error} retry={() => load(draft)} />
        </div>
      ) : loading && rows === null ? (
        <LoadingState label="Looking up arrivals…" />
      ) : rows && rows.length === 0 ? (
        <div className="mt-4">
          <EmptyState
            title={draft ? "Nobody matches that" : "Nobody is due"}
            // AC-4 of US-12.1.1: a no-result is a route into walk-in registration, not a dead end.
            description={
              draft
                ? "Check the spelling, or register them as a walk-in from the Pre-register screen."
                : "Visitors appear here once they are pre-registered for today."
            }
          />
        </div>
      ) : (
        <ul className="mt-4 space-y-2">
          {rows?.map((row) => (
            <li
              key={row.visitorId}
              className="rounded-lg border border-border bg-surface-raised p-4"
            >
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <h2 className="text-base font-semibold text-text">{row.fullName}</h2>
                    <StatusTag
                      label={OUTCOME_LABELS[row.outcome]}
                      tone={OUTCOME_TONES[row.outcome]}
                    />
                  </div>
                  <p className="mt-1 text-sm text-text-muted">
                    {[row.company, row.visitorType].filter(Boolean).join(" · ") || "No company"}
                  </p>
                  <dl className="mt-2 grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 text-sm">
                    <dt className="text-text-muted">Host</dt>
                    <dd className="text-text">
                      {row.host ?? "—"}
                      {row.tenant && <span className="text-text-muted"> · {row.tenant}</span>}
                    </dd>
                    {row.appointmentFrom && row.appointmentTo && (
                      <>
                        <dt className="text-text-muted">Expected</dt>
                        <dd className="text-text">
                          {formatWindow(row.appointmentFrom, row.appointmentTo)}
                        </dd>
                      </>
                    )}
                    {row.phone && (
                      <>
                        <dt className="text-text-muted">Phone</dt>
                        <dd className="text-text">{row.phone}</dd>
                      </>
                    )}
                    {row.checkedInAt && (
                      <>
                        <dt className="text-text-muted">Checked in</dt>
                        <dd className="text-text">{formatInstant(row.checkedInAt)}</dd>
                      </>
                    )}
                  </dl>
                  {row.reason && <p className="mt-2 text-sm text-text-muted">{row.reason}</p>}
                </div>

                <div className="flex shrink-0 flex-wrap gap-2">
                  {/* Absent, not disabled: a disabled control invites "why not, and who can?" */}
                  {row.mayCheckIn && (
                    <Button onClick={() => move(row, "in")} busy={busyId === row.visitorId}>
                      Check in
                    </Button>
                  )}
                  {(row.visitorStatus === "checked_in" || row.visitorStatus === "inside") && (
                    <Button
                      variant="secondary"
                      onClick={() => move(row, "out")}
                      busy={busyId === row.visitorId}
                    >
                      Check out
                    </Button>
                  )}
                </div>
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
