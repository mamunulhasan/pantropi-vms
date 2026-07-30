/**
 * UI-4a — the URL is the source of truth for list state: params parse from it, setters write
 * it, and any non-paging change resets to page 0.
 */
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { type ListParams, type Page } from "@/lib/admin-api";
import { useAdminList } from "@/lib/use-admin-list";

const replace = vi.fn();
let currentSearch = new URLSearchParams();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace }),
  usePathname: () => "/admin/buildings",
  useSearchParams: () => currentSearch,
}));

type Row = { id: string };

function makeFetcher(total = 5) {
  return vi.fn(
    async (params: ListParams): Promise<Page<Row>> => ({
      items: [{ id: "r1" }],
      page: params.page ?? 0,
      size: params.size ?? 20,
      total,
    }),
  );
}

function Harness({ fetcher }: { fetcher: (p: ListParams) => Promise<Page<Row>> }) {
  const list = useAdminList<Row>(fetcher, { sort: "code" });
  return (
    <div>
      <output data-testid="page">{String(list.params.page)}</output>
      <output data-testid="search">{list.params.search ?? "(none)"}</output>
      <output data-testid="total">{list.data ? String(list.data.total) : "loading"}</output>
      <button onClick={() => list.update({ search: "north" })}>set-search</button>
      <button onClick={() => list.update({ page: 3 })}>set-page</button>
      <button onClick={() => list.reload()}>reload</button>
    </div>
  );
}

beforeEach(() => {
  replace.mockClear();
  currentSearch = new URLSearchParams();
});

afterEach(cleanup);

describe("useAdminList", () => {
  it("parses params from the URL and fetches with them", async () => {
    currentSearch = new URLSearchParams("page=2&search=west");
    const fetcher = makeFetcher();
    render(<Harness fetcher={fetcher} />);

    expect(screen.getByTestId("page")).toHaveTextContent("2");
    expect(screen.getByTestId("search")).toHaveTextContent("west");
    await waitFor(() => expect(screen.getByTestId("total")).toHaveTextContent("5"));
    expect(fetcher).toHaveBeenCalledWith(
      expect.objectContaining({ page: 2, size: 20, search: "west", sort: "code" }),
    );
  });

  it("a filter change writes the URL and resets to page 0", async () => {
    currentSearch = new URLSearchParams("page=2&search=west");
    render(<Harness fetcher={makeFetcher()} />);

    fireEvent.click(screen.getByRole("button", { name: "set-search" }));

    expect(replace).toHaveBeenCalledTimes(1);
    const url = String(replace.mock.calls[0][0]);
    const q = new URLSearchParams(url.split("?")[1] ?? "");
    expect(q.get("search")).toBe("north");
    // The old page number was an address into a result set that no longer exists.
    expect(q.get("page")).toBeNull();
  });

  it("paging keeps the filters", () => {
    currentSearch = new URLSearchParams("search=west");
    render(<Harness fetcher={makeFetcher()} />);

    fireEvent.click(screen.getByRole("button", { name: "set-page" }));

    const q = new URLSearchParams(String(replace.mock.calls[0][0]).split("?")[1] ?? "");
    expect(q.get("page")).toBe("3");
    expect(q.get("search")).toBe("west");
  });

  it("the default sort stays out of the URL", () => {
    currentSearch = new URLSearchParams();
    render(<Harness fetcher={makeFetcher()} />);

    fireEvent.click(screen.getByRole("button", { name: "set-page" }));

    const q = new URLSearchParams(String(replace.mock.calls[0][0]).split("?")[1] ?? "");
    expect(q.get("sort")).toBeNull();
  });

  it("reload re-runs the identical fetch", async () => {
    const fetcher = makeFetcher();
    render(<Harness fetcher={fetcher} />);
    await waitFor(() => expect(screen.getByTestId("total")).toHaveTextContent("5"));

    fireEvent.click(screen.getByRole("button", { name: "reload" }));

    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    expect(replace).not.toHaveBeenCalled(); // a reload is not a navigation
  });
});
