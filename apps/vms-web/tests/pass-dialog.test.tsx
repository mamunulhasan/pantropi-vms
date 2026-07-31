/**
 * US-09.5.1 — the pass dialog.
 *
 * Two things are worth pinning here, and neither is cosmetic.
 *
 * The **payload never reaches this component**. It asks `/api/pass` for an image and renders a blob
 * URL; the QR content stays on the portal server. The test asserts the request body carries only a
 * visitor id, so a future change that "simplifies" this by fetching the payload and rendering
 * client-side fails here rather than silently undoing AC-3.
 *
 * The **selector only appears for a group**. A single-visitor approval — the common case — must look
 * exactly as it did before multi-visitor support existed.
 */
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { PassDialog, type PassSubject } from "@/components/visits/PassDialog";

afterEach(cleanup);

const ADA: PassSubject = {
  visitorId: "11111111-1111-1111-1111-111111111111",
  visitorName: "Ada Lovelace",
  validFrom: "2030-06-01T09:00:00Z",
  validTo: "2030-06-01T11:00:00Z",
};

const ALAN: PassSubject = {
  visitorId: "22222222-2222-2222-2222-222222222222",
  visitorName: "Alan Turing",
  validFrom: "2030-06-01T09:00:00Z",
  validTo: "2030-06-01T11:00:00Z",
};

let requests: { url: string; body: unknown }[] = [];

beforeEach(() => {
  requests = [];
  // jsdom implements neither of these; the component's whole job is turning one into the other.
  URL.createObjectURL = vi.fn(() => "blob:fake-pass");
  URL.revokeObjectURL = vi.fn();

  vi.stubGlobal("fetch", async (url: string, init: RequestInit) => {
    requests.push({ url, body: JSON.parse(String(init.body)) });
    return {
      ok: true,
      blob: async () => new Blob(["png"], { type: "image/png" }),
    } as unknown as Response;
  });
});

describe("PassDialog", () => {
  it("renders the QR as an image and never handles the payload itself", async () => {
    render(<PassDialog subjects={[ADA]} onClose={() => {}} />);

    await waitFor(() => expect(screen.getByRole("img")).toBeTruthy());
    expect(screen.getByRole("img").getAttribute("src")).toBe("blob:fake-pass");

    // A visitor id goes out; an image comes back. Nothing here ever sees the scannable secret.
    expect(requests).toHaveLength(1);
    expect(requests[0]?.body).toEqual({ visitorId: ADA.visitorId });
    expect(requests[0]?.url).toBe("/api/pass");
  });

  it("shows no selector for a single visitor", async () => {
    render(<PassDialog subjects={[ADA]} onClose={() => {}} />);

    await waitFor(() => expect(screen.getByRole("img")).toBeTruthy());
    expect(screen.queryByRole("group", { name: "Choose a visitor" })).toBeNull();
    expect(screen.getByText("Pass for Ada Lovelace")).toBeTruthy();
  });

  it("offers a selector for a group and fetches the chosen visitor's pass", async () => {
    render(<PassDialog subjects={[ADA, ALAN]} onClose={() => {}} />);

    await waitFor(() => expect(requests).toHaveLength(1));
    expect(screen.getByRole("group", { name: "Choose a visitor" })).toBeTruthy();
    // The first is shown by default, so exactly one request has been made so far.
    expect(requests[0]?.body).toEqual({ visitorId: ADA.visitorId });

    fireEvent.click(screen.getByRole("button", { name: "Alan Turing" }));

    await waitFor(() => expect(requests).toHaveLength(2));
    expect(requests[1]?.body).toEqual({ visitorId: ALAN.visitorId });
  });

  it("reports a refusal instead of showing a broken image", async () => {
    vi.stubGlobal("fetch", async () => ({
      ok: false,
      json: async () => ({ detail: "The pass is requested and cannot be shown yet" }),
    }) as unknown as Response);

    render(<PassDialog subjects={[ADA]} onClose={() => {}} />);

    await waitFor(() =>
      expect(screen.getByRole("alert").textContent).toContain("cannot be shown yet"),
    );
    expect(screen.queryByRole("img")).toBeNull();
  });

  it("renders nothing when there is no pass to show", () => {
    const { container } = render(<PassDialog subjects={[]} onClose={() => {}} />);
    expect(container.textContent).toBe("");
  });
});
