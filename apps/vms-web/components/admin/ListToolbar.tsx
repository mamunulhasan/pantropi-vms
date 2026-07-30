"use client";

/**
 * The list-screen toolbar: search + active filter, writing through the URL-reflected
 * list state (UI-4a). Search submits on Enter or the button — not per keystroke, because
 * every change is a server round trip and a URL write.
 */
import { useEffect, useState, type ReactNode } from "react";
import { Button } from "@/components/ui/Button";
import { type ListState } from "@/lib/use-admin-list";

export function ListToolbar<T>({
  list,
  searchLabel,
  children,
}: {
  list: ListState<T>;
  searchLabel: string;
  /** Right-hand slot — usually the create button. */
  children?: ReactNode;
}) {
  const [draft, setDraft] = useState(list.params.search ?? "");

  // Back/forward navigation changes the URL under us; the box must follow it.
  useEffect(() => {
    setDraft(list.params.search ?? "");
  }, [list.params.search]);

  return (
    <div className="mb-4 flex flex-wrap items-end justify-between gap-3">
      <form
        role="search"
        className="flex flex-wrap items-end gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          list.update({ search: draft.trim() || undefined });
        }}
      >
        <div>
          <label htmlFor="list-search" className="block text-sm font-medium text-text">
            {searchLabel}
          </label>
          <input
            id="list-search"
            type="search"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            className="mt-1 w-64 rounded-md border border-border bg-surface px-3 py-2 text-sm"
          />
        </div>
        <Button type="submit" variant="secondary">
          Search
        </Button>
        <div>
          <label htmlFor="list-active" className="block text-sm font-medium text-text">
            Status
          </label>
          <select
            id="list-active"
            value={list.params.active === undefined ? "all" : String(list.params.active)}
            onChange={(e) => {
              const v = e.target.value;
              list.update({ active: v === "all" ? undefined : v === "true" });
            }}
            className="mt-1 rounded-md border border-border bg-surface px-3 py-2 text-sm"
          >
            <option value="all">All</option>
            <option value="true">Active</option>
            <option value="false">Inactive</option>
          </select>
        </div>
      </form>
      {children && <div>{children}</div>}
    </div>
  );
}
