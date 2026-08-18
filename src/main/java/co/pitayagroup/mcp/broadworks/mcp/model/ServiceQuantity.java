package co.pitayagroup.mcp.broadworks.mcp.model;

/**
 * A BroadWorks service/service-pack quantity, mapping the Alpaca {@code UnboundedPositiveInt} /
 * {@code UnboundedNonNegativeInt} datatypes into a compact DTO.
 *
 * <p>A quantity is either a finite count ({@code quantity} set, {@code unlimited} false) or an
 * unbounded allocation ({@code unlimited} true, in which case {@code quantity} is typically
 * {@code null}).</p>
 *
 * @param quantity  the finite count, or {@code null} when unlimited or unspecified.
 * @param unlimited whether the allocation is unlimited.
 */
public record ServiceQuantity(
        Integer quantity,
        boolean unlimited
) {
}
