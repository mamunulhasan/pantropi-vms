/**
 * UI-7 — the four primitives lifted out of the screens when the prototype was integrated.
 *
 * These are small, so the temptation is to assert their markup. The assertions here are about the
 * contracts screens depend on instead: that a heading level can be moved without touching a
 * caller's copy, that an unknown number is not rendered as zero, that a status is legible without
 * colour, and that the segmented filter is a real radio group rather than a row of buttons.
 */
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { PageHeader } from "@/components/ui/PageHeader";
import { SegmentedControl } from "@/components/ui/SegmentedControl";
import { StatTiles } from "@/components/ui/StatTiles";
import { ActiveTag, StatusTag, requestTone } from "@/components/ui/StatusTag";

afterEach(cleanup);

describe("PageHeader", () => {
  it("renders an h1 by default and an h2 when a layout already owns the h1", () => {
    const { rerender } = render(<PageHeader title="Approvals" />);
    expect(screen.getByRole("heading", { level: 1, name: "Approvals" })).toBeTruthy();

    // The configuration area's layout owns the page h1; its tables must not add a second.
    rerender(<PageHeader title="Buildings" level={2} />);
    expect(screen.getByRole("heading", { level: 2, name: "Buildings" })).toBeTruthy();
    expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
  });

  it("shows the kicker and description, and hosts an actions slot", () => {
    render(
      <PageHeader
        kicker="Reception"
        title="Front desk"
        description="Everything registered at this desk today."
        actions={<button type="button">New</button>}
      />,
    );
    expect(screen.getByText("Reception")).toBeTruthy();
    expect(screen.getByText("Everything registered at this desk today.")).toBeTruthy();
    expect(screen.getByRole("button", { name: "New" })).toBeTruthy();
  });
});

describe("StatTiles", () => {
  it("renders an em dash for an unknown value, not a zero", () => {
    render(
      <StatTiles
        stats={[
          { label: "Awaiting decision", value: null },
          { label: "Arriving today", value: 0 },
        ]}
      />,
    );
    // Zero is a fact; a dash is the absence of one. Conflating them is how a dashboard misleads.
    const unknown = screen.getByText("Awaiting decision").parentElement;
    expect(unknown?.textContent).toContain("—");
    const zero = screen.getByText("Arriving today").parentElement;
    expect(zero?.textContent).toContain("0");
  });

  it("pairs every label with its value as a description list", () => {
    render(<StatTiles stats={[{ label: "On this page", value: 7, note: "people" }]} />);
    // dt/dd: the label is the term, the number its definition.
    expect(screen.getByText("On this page").tagName).toBe("DT");
    expect(screen.getByText("7")).toBeTruthy();
    expect(screen.getByText("people")).toBeTruthy();
  });

  it("renders nothing at all when given no stats", () => {
    const { container } = render(<StatTiles stats={[]} />);
    expect(container.firstChild).toBeNull();
  });
});

describe("StatusTag", () => {
  it("always states the status in words, so colour is never the only carrier", () => {
    render(<StatusTag label="Rejected" tone="danger" />);
    expect(screen.getByText("Rejected")).toBeTruthy();
  });

  it("maps each request status to a tone", () => {
    expect(requestTone("approved")).toBe("success");
    expect(requestTone("rejected")).toBe("danger");
    expect(requestTone("cancelled")).toBe("muted");
    expect(requestTone("submitted")).toBe("neutral");
  });

  it("names the master-data states rather than relying on a coloured dot", () => {
    const { rerender } = render(<ActiveTag active />);
    expect(screen.getByText("Active")).toBeTruthy();
    rerender(<ActiveTag active={false} />);
    expect(screen.getByText("Inactive")).toBeTruthy();
  });
});

describe("SegmentedControl", () => {
  const OPTIONS = [
    { value: "all", label: "All" },
    { value: "submitted", label: "Awaiting" },
    { value: "approved", label: "Approved" },
  ] as const;

  function Harness({ onChange = () => {} }: { onChange?: (v: string) => void }) {
    const [value, setValue] = useState<string>("all");
    return (
      <SegmentedControl
        label="Status"
        options={OPTIONS}
        value={value}
        onChange={(next) => {
          setValue(next);
          onChange(next);
        }}
      />
    );
  }

  it("is a radio group, so it gets arrow-key movement and one tab stop for free", () => {
    render(<Harness />);
    // radiogroup, not tablist: these filters navigate, they do not swap panels in place.
    expect(screen.getByRole("group", { name: "Status" })).toBeTruthy();
    expect(screen.getAllByRole("radio")).toHaveLength(3);
    expect(screen.getByRole("radio", { name: "All" })).toBeChecked();
  });

  it("reports the chosen value and moves the selection", () => {
    const onChange = vi.fn();
    render(<Harness onChange={onChange} />);

    fireEvent.click(screen.getByRole("radio", { name: "Approved" }));

    expect(onChange).toHaveBeenCalledWith("approved");
    expect(screen.getByRole("radio", { name: "Approved" })).toBeChecked();
    expect(screen.getByRole("radio", { name: "All" })).not.toBeChecked();
  });

  it("disables every option when the group is disabled", () => {
    render(
      <SegmentedControl label="Status" options={OPTIONS} value="all" onChange={() => {}} disabled />,
    );
    for (const radio of screen.getAllByRole("radio")) {
      expect(radio).toBeDisabled();
    }
  });
});
