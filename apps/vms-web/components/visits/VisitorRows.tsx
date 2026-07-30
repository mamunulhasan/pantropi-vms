"use client";

/**
 * The visitor editor — one row per person on a request (VJ-1).
 *
 * Bounds mirror the server's so a mistake is caught before a round trip, but the server stays the
 * authority: it re-validates and normalises every field (email lower-cased, phone stripped of
 * punctuation), so what comes back may differ from what was typed, and that is correct.
 *
 * There is no visitor-type selector. Choosing one needs the id from `GET /api/v1/admin/visitor-types`,
 * which requires `masterdata.view` — a permission the TENANT role does not hold. Rather than
 * present a control that cannot be populated, or invent an endpoint, the field is omitted and the
 * gap is recorded in the traceability matrix.
 */
import { Field, Input } from "@/components/ui/Field";
import { Button } from "@/components/ui/Button";
import { type VisitorPayload } from "@/lib/visits-api";

export const MAX_VISITORS = 50;

export function emptyVisitor(): VisitorPayload {
  return { fullName: "", email: "", phone: "", company: "" };
}

export function VisitorRows({
  visitors,
  onChange,
  disabled = false,
}: {
  visitors: VisitorPayload[];
  onChange: (next: VisitorPayload[]) => void;
  disabled?: boolean;
}) {
  function update(index: number, patch: Partial<VisitorPayload>) {
    onChange(visitors.map((v, i) => (i === index ? { ...v, ...patch } : v)));
  }

  return (
    <div>
      <ul className="space-y-4">
        {visitors.map((visitor, index) => (
          <li key={index} className="rounded-lg border border-border p-4">
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-medium text-text">Visitor {index + 1}</h3>
              {visitors.length > 1 && (
                <Button
                  variant="ghost"
                  disabled={disabled}
                  onClick={() => onChange(visitors.filter((_, i) => i !== index))}
                >
                  Remove
                </Button>
              )}
            </div>

            <div className="mt-3 grid gap-3 sm:grid-cols-2">
              <Field label="Full name" required>
                <Input
                  value={visitor.fullName}
                  onChange={(e) => update(index, { fullName: e.target.value })}
                  required
                  maxLength={200}
                  disabled={disabled}
                  autoComplete="off"
                />
              </Field>
              <Field label="Company">
                <Input
                  value={visitor.company ?? ""}
                  onChange={(e) => update(index, { company: e.target.value })}
                  maxLength={200}
                  disabled={disabled}
                  autoComplete="off"
                />
              </Field>
              <Field label="Email" hint="Optional. Used to notify the visitor.">
                <Input
                  type="email"
                  value={visitor.email ?? ""}
                  onChange={(e) => update(index, { email: e.target.value })}
                  maxLength={320}
                  disabled={disabled}
                  autoComplete="off"
                />
              </Field>
              <Field label="Phone" hint="Optional. 6–20 digits, may start with +.">
                <Input
                  type="tel"
                  value={visitor.phone ?? ""}
                  onChange={(e) => update(index, { phone: e.target.value })}
                  maxLength={40}
                  disabled={disabled}
                  autoComplete="off"
                />
              </Field>
            </div>
          </li>
        ))}
      </ul>

      <div className="mt-3 flex items-center gap-3">
        <Button
          variant="secondary"
          disabled={disabled || visitors.length >= MAX_VISITORS}
          onClick={() => onChange([...visitors, emptyVisitor()])}
        >
          Add another visitor
        </Button>
        <span className="text-sm text-text-muted">
          {visitors.length} of {MAX_VISITORS}
        </span>
      </div>
    </div>
  );
}

/**
 * Client-side shape checks, mirroring the server's bounds so the common mistakes are caught
 * without a round trip. Returns one message per problem, naming the visitor by position — the API
 * cannot do that, because its `{error:"invalid", detail:"visitor email"}` names the field kind and
 * not which row it came from.
 */
export function validateVisitors(visitors: VisitorPayload[]): string[] {
  const problems: string[] = [];
  if (visitors.length === 0) {
    problems.push("Name at least one visitor.");
  }
  if (visitors.length > MAX_VISITORS) {
    problems.push(`A request can carry at most ${MAX_VISITORS} visitors.`);
  }
  visitors.forEach((v, i) => {
    const who = `Visitor ${i + 1}`;
    if (!v.fullName.trim()) {
      problems.push(`${who}: a full name is required.`);
    }
    const email = (v.email ?? "").trim();
    if (email && !/^[^\s@]+@[^\s@.]+(\.[^\s@.]+)+$/.test(email)) {
      problems.push(`${who}: that email address is not valid.`);
    }
    const phone = (v.phone ?? "").replace(/[\s().-]/g, "");
    if (phone && !/^\+?\d{6,20}$/.test(phone)) {
      problems.push(`${who}: a phone number needs 6 to 20 digits.`);
    }
  });
  return problems;
}

/** Strips blanks to null, so an untouched optional field is absent rather than empty. */
export function cleanVisitors(visitors: VisitorPayload[]): VisitorPayload[] {
  return visitors.map((v) => ({
    fullName: v.fullName.trim(),
    email: v.email?.trim() || null,
    phone: v.phone?.trim() || null,
    company: v.company?.trim() || null,
  }));
}
