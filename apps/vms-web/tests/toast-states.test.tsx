/**
 * US-06.2.1 — toasts announce through a polite live region and auto-dismiss; the error state
 * shows the problem detail and correlation id, never the raw payload.
 */
import { cleanup, fireEvent, render, screen, act } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { TOAST_MS, ToastProvider, useToast } from "@/components/ui/Toast";
import { EmptyState, ErrorState, LoadingState } from "@/components/ui/states";
import { ApiError } from "@/lib/api";

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

function Trigger({ message }: { message: string }) {
  const { toast } = useToast();
  return (
    <button type="button" onClick={() => toast(message, "success")}>
      Notify
    </button>
  );
}

describe("Toast", () => {
  it("renders into a polite live region and can be dismissed manually", () => {
    render(
      <ToastProvider>
        <Trigger message="Building saved." />
      </ToastProvider>,
    );
    const region = screen.getByRole("status");
    expect(region).toHaveAttribute("aria-live", "polite");

    fireEvent.click(screen.getByRole("button", { name: "Notify" }));
    expect(region).toHaveTextContent("Building saved.");

    fireEvent.click(screen.getByRole("button", { name: "Dismiss notification" }));
    expect(region).not.toHaveTextContent("Building saved.");
  });

  it("auto-dismisses after its timeout", () => {
    vi.useFakeTimers();
    render(
      <ToastProvider>
        <Trigger message="Saved." />
      </ToastProvider>,
    );
    fireEvent.click(screen.getByRole("button", { name: "Notify" }));
    expect(screen.getByRole("status")).toHaveTextContent("Saved.");

    act(() => {
      vi.advanceTimersByTime(TOAST_MS + 1);
    });
    expect(screen.getByRole("status")).not.toHaveTextContent("Saved.");
  });
});

describe("ErrorState (AC-5)", () => {
  it("shows the problem detail and correlation id — not the raw payload", () => {
    const error = new ApiError(409, {
      error: "conflict",
      detail: "The role was changed by someone else. Review and retry.",
      correlationId: "c0ffee-1234",
      internalHint: "OptimisticLockException at RoleAdministration.java:42",
    });
    render(<ErrorState error={error} />);

    expect(
      screen.getByText("The role was changed by someone else. Review and retry."),
    ).toBeInTheDocument();
    expect(screen.getByText("c0ffee-1234")).toBeInTheDocument();
    // The raw body never renders — whatever else the payload carried stays out of the DOM.
    expect(document.body.textContent).not.toContain("OptimisticLockException");
    expect(document.body.textContent).not.toContain("internalHint");
  });

  it("a non-API error shows a generic message, never Error#message", () => {
    render(<ErrorState error={new Error("ECONNREFUSED 127.0.0.1:8081")} />);
    expect(document.body.textContent).not.toContain("ECONNREFUSED");
    expect(screen.getByRole("alert")).toHaveTextContent("Something went wrong");
  });

  it("renders the retry action when given one", () => {
    const retry = vi.fn();
    render(<ErrorState error={new ApiError(500, null)} retry={retry} />);
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(retry).toHaveBeenCalledTimes(1);
  });
});

describe("Loading and empty states", () => {
  it("LoadingState is a status region", () => {
    render(<LoadingState />);
    expect(screen.getByRole("status")).toHaveTextContent("Loading…");
  });

  it("EmptyState carries title, description and action", () => {
    render(
      <EmptyState
        title="No buildings yet"
        description="Create the first building to start."
        action={<button type="button">New building</button>}
      />,
    );
    expect(screen.getByText("No buildings yet")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "New building" })).toBeInTheDocument();
  });
});
