/**
 * The one place a state becomes a coloured label (UI-7, prototype `.tag`).
 *
 * Lifted from `app/(tenant)/visits/page.tsx`, where `STATUS_CLASSES` mapped the four request
 * statuses to colours, and from the pill hand-rolled again in `visits/[id]`. Two copies of a
 * colour vocabulary is one copy too many: the moment they disagree, the same request reads as two
 * different things on two screens.
 *
 * <strong>Colour is never the only carrier.</strong> The label always states the status in words,
 * so the meaning survives a monochrome print and a colour-vision difference alike — the
 * accessibility checklist calls this out and the axe scan cannot detect it.
 *
 * The vocabulary is deliberately limited to states the API can actually report. The prototype also
 * shows `On-site`, `Awaiting tenant` and `Departed`; check-in and check-out are unbuilt, so those
 * are absent rather than rendered as something the system does not know.
 */
import type { RequestStatus } from "@/lib/visits-api";

/** Visual weights, not statuses — so a caller outside the request lifecycle can still use this. */
export type TagTone = "neutral" | "info" | "success" | "danger" | "warning" | "muted";

const TONE_CLASSES: Record<TagTone, string> = {
  neutral: "bg-surface-sunken text-text",
  info: "bg-surface-sunken text-text",
  success: "bg-success text-success-contrast",
  danger: "bg-danger text-danger-contrast",
  warning: "bg-warning text-warning-contrast",
  muted: "bg-surface-sunken text-text-muted",
};

/** The request lifecycle, matching `REQUEST_STATUSES` in lib/visits-api. */
const REQUEST_TONES: Record<RequestStatus, TagTone> = {
  submitted: "neutral",
  approved: "success",
  rejected: "danger",
  cancelled: "muted",
};

export function StatusTag({
  label,
  tone = "neutral",
  className,
}: {
  label: string;
  tone?: TagTone;
  className?: string;
}) {
  return (
    <span
      className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${TONE_CLASSES[tone]} ${className ?? ""}`}
    >
      {label}
    </span>
  );
}

/** The tone a request status carries, for callers that need it separately from the tag. */
export function requestTone(status: RequestStatus): TagTone {
  return REQUEST_TONES[status] ?? "neutral";
}

/** Active/inactive, the master-data vocabulary shared by all eight configuration tables. */
export function ActiveTag({ active }: { active: boolean }) {
  return <StatusTag label={active ? "Active" : "Inactive"} tone={active ? "success" : "muted"} />;
}
