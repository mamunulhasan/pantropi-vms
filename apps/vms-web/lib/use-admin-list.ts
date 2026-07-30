"use client";

/**
 * List-screen state with the URL as the source of truth (UI-4a).
 *
 * `page`, `size`, `sort`, `search` and `active` live in the query string, not in component
 * state: a filtered page-3 view survives refresh, lands in history, and can be pasted to a
 * colleague. Setters write the URL (via `router.replace` — paging through a list should not
 * bury the back button); the fetch effect keys off the URL, so navigation and setter calls
 * take the identical path.
 *
 * Changing search/filter/sort resets to page 0 — the old page number was an address into a
 * result set that no longer exists.
 */
import { useCallback, useEffect, useMemo, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { type ListParams, type Page } from "./admin-api";

export type ListState<T> = {
  data: Page<T> | null;
  loading: boolean;
  error: unknown;
  params: Required<Pick<ListParams, "page" | "size">> & ListParams;
  /** Merge new params into the URL. Non-paging changes reset `page` to 0. */
  update: (next: Partial<ListParams>) => void;
  /** Re-run the current fetch (after a mutation, or from an ErrorState retry). */
  reload: () => void;
};

export function useAdminList<T>(
  fetcher: (params: ListParams) => Promise<Page<T>>,
  defaults?: { sort?: string },
): ListState<T> {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();

  const params = useMemo<ListState<T>["params"]>(() => {
    const pageRaw = Number(searchParams.get("page"));
    const sizeRaw = Number(searchParams.get("size"));
    const active = searchParams.get("active");
    return {
      search: searchParams.get("search") ?? undefined,
      active: active === null ? undefined : active === "true",
      page: Number.isInteger(pageRaw) && pageRaw > 0 ? pageRaw : 0,
      size: Number.isInteger(sizeRaw) && sizeRaw > 0 ? sizeRaw : 20,
      sort: searchParams.get("sort") ?? defaults?.sort,
    };
  }, [searchParams, defaults?.sort]);

  const [data, setData] = useState<Page<T> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    fetcher(params)
      .then((page) => {
        if (!cancelled) {
          setData(page);
        }
      })
      .catch((e: unknown) => {
        if (!cancelled) {
          setError(e);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [fetcher, params, tick]);

  const update = useCallback(
    (next: Partial<ListParams>) => {
      const q = new URLSearchParams(searchParams);
      const merged = { ...params, ...next };
      const changedBeyondPaging = Object.keys(next).some((k) => k !== "page" && k !== "size");
      const page = changedBeyondPaging ? 0 : (merged.page ?? 0);

      const write = (key: string, value: string | undefined) => {
        if (value === undefined || value === "") {
          q.delete(key);
        } else {
          q.set(key, value);
        }
      };
      write("search", merged.search || undefined);
      write("active", merged.active === undefined ? undefined : String(merged.active));
      write("page", page > 0 ? String(page) : undefined);
      write("size", merged.size !== 20 ? String(merged.size) : undefined);
      write("sort", merged.sort === defaults?.sort ? undefined : merged.sort);

      const query = q.toString();
      router.replace(query ? `${pathname}?${query}` : pathname);
    },
    [router, pathname, searchParams, params, defaults?.sort],
  );

  const reload = useCallback(() => setTick((t) => t + 1), []);

  return { data, loading, error, params, update, reload };
}
