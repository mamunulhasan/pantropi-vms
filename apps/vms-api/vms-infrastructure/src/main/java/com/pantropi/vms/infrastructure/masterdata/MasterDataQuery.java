package com.pantropi.vms.infrastructure.masterdata;

import com.pantropi.vms.application.masterdata.port.MasterDataStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the filter, paging and argument list every coded master data listing needs.
 *
 * <p>Extracted at the third adapter. Each store still writes its own SQL — the table, the columns
 * and the ordering are genuinely different and reading them literally is worth more than hiding
 * them — but the parts that are identical every time, and quietly wrong when they are not, live
 * here: the {@code active} filter, the two-column search, the page bounds, and the argument order
 * that has to match the placeholders.
 *
 * <p>The page-size ceiling is the reason this matters most. A listing endpoint with no upper bound
 * is a way to ask the server for the whole table; getting that right in one place beats getting it
 * right in five.
 */
final class MasterDataQuery {

    /** A page is a page. Beyond this the caller is asking for a dump, and gets a page anyway. */
    private static final int MAX_PAGE_SIZE = 200;

    private final String where;
    private final List<Object> filterArgs;
    private final int page;
    private final int size;

    private MasterDataQuery(String where, List<Object> filterArgs, int page, int size) {
        this.where = where;
        this.filterArgs = filterArgs;
        this.page = page;
        this.size = size;
    }

    /**
     * @param parentColumn the column holding the parent id, or null for a top-level entity. Passing
     *                     null makes {@code parentId} inapplicable rather than ignored — a
     *                     top-level entity has no parent to filter by.
     */
    static MasterDataQuery of(MasterDataStore.Query query, String parentColumn) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();

        if (parentColumn != null && query.parentId() != null) {
            where.append(" AND ").append(parentColumn).append(" = ?");
            args.add(query.parentId());
        }
        if (query.active() != null) {
            where.append(" AND is_active = ?");
            args.add(query.active());
        }
        if (query.search() != null && !query.search().isBlank()) {
            // Both fields: someone searching "west" should find it whether it is the code or the
            // name that matches, without having to know which they typed.
            where.append(" AND (code ILIKE ? OR name ILIKE ?)");
            String pattern = "%" + query.search().trim() + "%";
            args.add(pattern);
            args.add(pattern);
        }

        return new MasterDataQuery(where.toString(), args,
                Math.max(0, query.page()),
                Math.max(1, Math.min(query.size(), MAX_PAGE_SIZE)));
    }

    /** The {@code WHERE} fragment. Contains only placeholders — no caller input is interpolated. */
    String where() {
        return where;
    }

    /** Arguments for the count query. */
    Object[] countArgs() {
        return filterArgs.toArray();
    }

    /** The same arguments plus {@code LIMIT} and {@code OFFSET}, in placeholder order. */
    Object[] pageArgs() {
        List<Object> args = new ArrayList<>(filterArgs);
        args.add(size);
        args.add(page * size);
        return args.toArray();
    }

    int page() {
        return page;
    }

    int size() {
        return size;
    }
}
