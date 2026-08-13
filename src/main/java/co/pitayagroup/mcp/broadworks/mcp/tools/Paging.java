package co.pitayagroup.mcp.broadworks.mcp.tools;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import co.pitayagroup.mcp.broadworks.mcp.AlpacaException;

/**
 * Shared, server-side pagination helpers for the compact columnar {@link Page} responses used by the
 * BroadWorks list tools.
 *
 * <p>The helpers enforce a hard server-side ceiling on page size (so a model can never request an
 * unbounded page), build the opaque cursor that clients echo back to fetch the next page, and attach
 * the pagination/observability metadata (total, has-more, truncation reason, suggestion).</p>
 */
final class Paging {

    /** Default page size when the caller does not request one. */
    static final int DEFAULT_PAGE_LIMIT = 25;

    /** Hard server-side ceiling on the page size, regardless of what the caller requests. */
    static final int MAX_PAGE_LIMIT = 50;

    /** Hard server-side ceiling on total emitted cells (rows x columns) per page. */
    static final int MAX_CELL_BUDGET = 400;

    private Paging() {
    }

    /**
     * Applies the hard server-side ceilings to a caller-requested page size. Non-positive or missing
     * values fall back to {@link #DEFAULT_PAGE_LIMIT}; the result never exceeds {@link #MAX_PAGE_LIMIT}
     * nor the {@link #MAX_CELL_BUDGET} cell budget for the given column count.
     *
     * @param requested    the caller-supplied limit, or {@code null} when omitted.
     * @param columnCount  the number of columns in each row (used for the cell budget).
     */
    static int effectivePageLimit(Integer requested, int columnCount) {
        final int desired = (requested == null || requested <= 0) ? DEFAULT_PAGE_LIMIT : requested;
        final int cellCap = Math.max(1, MAX_CELL_BUDGET / Math.max(1, columnCount));
        return Math.min(Math.min(desired, MAX_PAGE_LIMIT), cellCap);
    }

    /**
     * Slices the already-materialized columnar {@code allRows} into a single {@link Page} starting at
     * {@code offset}, attaching the pagination/observability metadata (total, has-more, next cursor,
     * truncation reason, suggestion).
     *
     * @param schema        ordered column names for the page.
     * @param allRows       every matching row (positional, matching {@code schema}) across all pages.
     * @param offset        the zero-based row offset to start this page at.
     * @param pageLimit     the (already clamped) maximum rows to include in this page.
     * @param toolName      the MCP tool name to reference in the next-page suggestion.
     * @param entityPlural  the plural entity noun to use in the "all returned" suggestion.
     */
    static Page toPage(List<String> schema,
                       List<List<Object>> allRows,
                       int offset,
                       int pageLimit,
                       String toolName,
                       String entityPlural) {
        final int total = allRows.size();
        final int start = Math.min(offset, total);
        final int end = Math.min(start + pageLimit, total);
        final List<List<Object>> rows = new ArrayList<>(allRows.subList(start, end));
        final boolean hasMore = end < total;
        final String nextCursor = hasMore ? encodeCursor(end) : null;
        final String truncationReason = hasMore
                ? "Page capped at " + pageLimit + " row(s) (server maximum " + MAX_PAGE_LIMIT
                        + "); " + (total - end) + " more row(s) remain"
                : null;
        final String suggestion = hasMore
                ? "More results are available; call " + toolName + " again with cursor=\""
                        + nextCursor + "\" to fetch the next page"
                : "All matching " + entityPlural + " were returned";
        return new Page(schema, rows, rows.size(), total, hasMore, nextCursor, truncationReason, suggestion);
    }

    /** Encodes a zero-based row offset into an opaque, URL-safe pagination cursor. */
    static String encodeCursor(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Integer.toString(offset).getBytes(StandardCharsets.UTF_8));
    }

    /** Decodes an opaque pagination cursor back into a row offset; a blank cursor means "start". */
    static int decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            final String decoded = new String(Base64.getUrlDecoder().decode(cursor.trim()), StandardCharsets.UTF_8);
            final int offset = Integer.parseInt(decoded.trim());
            if (offset < 0) {
                throw new NumberFormatException("negative offset");
            }
            return offset;
        } catch (IllegalArgumentException ex) {
            throw new AlpacaException("Invalid pagination cursor");
        }
    }
}
