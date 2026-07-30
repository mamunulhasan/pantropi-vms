/**
 * US-06.2.1 AC-6 — the dialog focus contract: trap in, Escape out, restore back, and the
 * confirm variant never default-focuses the destructive control.
 */
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Button } from "@/components/ui/Button";
import { ConfirmDialog, Dialog } from "@/components/ui/Dialog";

afterEach(cleanup);

function Harness({ children }: { children: (close: () => void) => React.ReactNode }) {
  const [open, setOpen] = useState(false);
  return (
    <div>
      <Button onClick={() => setOpen(true)}>Open</Button>
      <Dialog open={open} onClose={() => setOpen(false)} title="Example">
        {children(() => setOpen(false))}
      </Dialog>
    </div>
  );
}

describe("Dialog", () => {
  it("moves focus inside on open", () => {
    render(
      <Harness>
        {() => (
          <div>
            <Button>First</Button>
            <Button>Second</Button>
          </div>
        )}
      </Harness>,
    );
    fireEvent.click(screen.getByRole("button", { name: "Open" }));
    expect(screen.getByRole("button", { name: "First" })).toHaveFocus();
  });

  it("Tab from the last focusable wraps to the first; Shift+Tab from the first wraps back", () => {
    render(
      <Harness>
        {() => (
          <div>
            <Button>First</Button>
            <Button>Last</Button>
          </div>
        )}
      </Harness>,
    );
    fireEvent.click(screen.getByRole("button", { name: "Open" }));
    const first = screen.getByRole("button", { name: "First" });
    const last = screen.getByRole("button", { name: "Last" });

    last.focus();
    fireEvent.keyDown(last, { key: "Tab" });
    expect(first).toHaveFocus();

    fireEvent.keyDown(first, { key: "Tab", shiftKey: true });
    expect(last).toHaveFocus();
  });

  it("Escape closes and focus returns to the opener", () => {
    render(<Harness>{() => <Button>Inside</Button>}</Harness>);
    const opener = screen.getByRole("button", { name: "Open" });

    opener.focus();
    fireEvent.click(opener);
    expect(screen.getByRole("dialog")).toBeInTheDocument();

    fireEvent.keyDown(screen.getByRole("button", { name: "Inside" }), { key: "Escape" });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    // A keyboard user lands back where they were, not at the top of the document.
    expect(opener).toHaveFocus();
  });

  it("is labelled by its title", () => {
    render(<Harness>{() => <Button>Inside</Button>}</Harness>);
    fireEvent.click(screen.getByRole("button", { name: "Open" }));
    expect(screen.getByRole("dialog", { name: "Example" })).toBeInTheDocument();
  });
});

describe("ConfirmDialog (AC-6)", () => {
  it("default focus is the cancel button — never the destructive control", () => {
    render(
      <ConfirmDialog
        open
        title="Deactivate building"
        description={<>Deactivate building <strong>Westgate Tower</strong>?</>}
        confirmLabel="Deactivate"
        destructive
        onConfirm={() => {}}
        onCancel={() => {}}
      />,
    );
    expect(screen.getByRole("button", { name: "Cancel" })).toHaveFocus();
    expect(screen.getByRole("button", { name: "Deactivate" })).not.toHaveFocus();
  });

  it("confirm and cancel report to their own callbacks", () => {
    const onConfirm = vi.fn();
    const onCancel = vi.fn();
    render(
      <ConfirmDialog
        open
        title="Remove"
        description="Remove holiday 2026-12-25?"
        onConfirm={onConfirm}
        onCancel={onCancel}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "Confirm" }));
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onCancel).toHaveBeenCalledTimes(1);
  });
});
