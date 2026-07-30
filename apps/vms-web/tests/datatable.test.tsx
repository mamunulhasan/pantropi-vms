/**
 * US-06.2.1 AC-3 — the table is a controlled view over one server page: it renders what it is
 * given, announces sort state, and reports paging/sorting intent upward.
 */
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { DataTable, type Column } from "@/components/ui/DataTable";
import { EmptyState } from "@/components/ui/states";

afterEach(cleanup);

type Row = { id: string; name: string; city: string };

const COLUMNS: readonly Column<Row>[] = [
  { key: "name", header: "Name", sortable: true, render: (r) => r.name },
  { key: "city", header: "City", render: (r) => r.city },
];

const ROWS: Row[] = [
  { id: "1", name: "Westgate Tower", city: "Dhaka" },
  { id: "2", name: "Eastgate Annex", city: "Dhaka" },
];

function renderTable(overrides: Partial<Parameters<typeof DataTable<Row>>[0]> = {}) {
  const onPageChange = vi.fn();
  const onSortChange = vi.fn();
  render(
    <DataTable<Row>
      caption="Buildings"
      columns={COLUMNS}
      rows={ROWS}
      rowKey={(r) => r.id}
      page={0}
      size={2}
      totalElements={5}
      sort={{ key: "name", direction: "asc" }}
      onPageChange={onPageChange}
      onSortChange={onSortChange}
      {...overrides}
    />,
  );
  return { onPageChange, onSortChange };
}

describe("DataTable", () => {
  it("renders exactly the given page and the range text", () => {
    renderTable();
    expect(screen.getAllByRole("row")).toHaveLength(3); // header + 2 data rows
    expect(screen.getByText("Showing 1–2 of 5")).toBeInTheDocument();
  });

  it("announces the sorted column via aria-sort", () => {
    renderTable();
    expect(screen.getByRole("columnheader", { name: /Name/ })).toHaveAttribute(
      "aria-sort",
      "ascending",
    );
    expect(screen.getByRole("columnheader", { name: "City" })).not.toHaveAttribute("aria-sort");
  });

  it("clicking the sorted header toggles direction; a new column starts ascending", () => {
    const { onSortChange } = renderTable();
    fireEvent.click(screen.getByRole("button", { name: /Name/ }));
    expect(onSortChange).toHaveBeenCalledWith({ key: "name", direction: "desc" });
  });

  it("an unsortable column renders no sort control", () => {
    renderTable();
    expect(screen.queryByRole("button", { name: /City/ })).not.toBeInTheDocument();
  });

  it("Next asks for the next zero-based page; Previous is disabled on the first", () => {
    const { onPageChange } = renderTable();
    expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    expect(onPageChange).toHaveBeenCalledWith(1);
  });

  it("Next is disabled on the last page", () => {
    renderTable({ page: 2, totalElements: 5 }); // pages of 2: 0,1,2 — 2 is last
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
    expect(screen.getByText("Showing 5–5 of 5")).toBeInTheDocument();
  });

  it("no rows renders the empty slot instead of a bare table", () => {
    renderTable({
      rows: [],
      totalElements: 0,
      empty: <EmptyState title="No buildings yet" />,
    });
    expect(screen.getByText("No buildings yet")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });
});
