package co.pitayagroup.mcp.broadworks.mcp.tools;

import java.util.List;

/**
 * A single, server-capped page of tabular BroadWorks data.
 *
 * <p>Tabular data is returned in a compact columnar form: {@code schema} names the columns once and
 * every entry in {@code rows} is a positional value list in the same order, avoiding repeating the
 * field keys on every object. The remaining fields are pagination/observability metadata so the
 * caller can decide whether to page again or narrow the request.</p>
 *
 * @param schema           ordered column names describing each entry in {@code rows}.
 * @param rows             the page of records, each a positional list matching {@code schema}.
 * @param returned         the number of rows in this page (equals {@code rows.size()}).
 * @param totalMatching    the total number of matching records across all pages.
 * @param hasMore          whether more rows remain beyond this page.
 * @param nextCursor       opaque cursor to pass back to fetch the next page, or {@code null} when {@code hasMore} is false.
 * @param truncationReason a short explanation of why the page was capped, or {@code null} when nothing was truncated.
 * @param suggestion       guidance for the caller (e.g. call again with the cursor, or refine the query).
 */
public record Page(
        List<String> schema,
        List<List<Object>> rows,
        int returned,
        int totalMatching,
        boolean hasMore,
        String nextCursor,
        String truncationReason,
        String suggestion
) {
}
