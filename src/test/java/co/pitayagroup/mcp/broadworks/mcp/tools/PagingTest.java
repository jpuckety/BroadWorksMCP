package co.pitayagroup.mcp.broadworks.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import co.pitayagroup.mcp.broadworks.mcp.AlpacaException;

import org.junit.jupiter.api.Test;

class PagingTest {

    @Test
    void effectivePageLimitAppliesDefaultAndServerCeiling() {
        assertThat(Paging.effectivePageLimit(null, 3)).isEqualTo(Paging.DEFAULT_PAGE_LIMIT);
        assertThat(Paging.effectivePageLimit(0, 3)).isEqualTo(Paging.DEFAULT_PAGE_LIMIT);
        assertThat(Paging.effectivePageLimit(-7, 3)).isEqualTo(Paging.DEFAULT_PAGE_LIMIT);
        assertThat(Paging.effectivePageLimit(5, 3)).isEqualTo(5);
        assertThat(Paging.effectivePageLimit(10_000, 3)).isEqualTo(Paging.MAX_PAGE_LIMIT);
    }

    @Test
    void effectivePageLimitHonoursCellBudget() {
        // With a very wide row the cell budget bites before the row ceiling.
        final int wideColumns = Paging.MAX_CELL_BUDGET;
        assertThat(Paging.effectivePageLimit(Paging.MAX_PAGE_LIMIT, wideColumns)).isEqualTo(1);
    }

    @Test
    void cursorRoundTripsAndBlankMeansStart() {
        assertThat(Paging.decodeCursor(null)).isZero();
        assertThat(Paging.decodeCursor("   ")).isZero();
        final String cursor = Paging.encodeCursor(42);
        assertThat(Paging.decodeCursor(cursor)).isEqualTo(42);
    }

    @Test
    void decodeCursorRejectsInvalidToken() {
        assertThatThrownBy(() -> Paging.decodeCursor("@@not-valid@@"))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("Invalid pagination cursor");
    }

    @Test
    void toPageBuildsColumnarPagesAndPagingMetadata() {
        final List<String> schema = List.of("id", "name");
        final List<List<Object>> all = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            all.add(Arrays.asList("id-" + i, "Name " + i));
        }

        final Page first = Paging.toPage(schema, all, 0, 2, "some_tool", "things");
        assertThat(first.schema()).isEqualTo(schema);
        assertThat(first.rows()).containsExactly(
                Arrays.asList("id-0", "Name 0"),
                Arrays.asList("id-1", "Name 1"));
        assertThat(first.returned()).isEqualTo(2);
        assertThat(first.totalMatching()).isEqualTo(3);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isNotNull();
        assertThat(first.truncationReason()).isNotNull();
        assertThat(first.suggestion()).contains("some_tool").contains(first.nextCursor());

        final Page second = Paging.toPage(schema, all, Paging.decodeCursor(first.nextCursor()), 2,
                "some_tool", "things");
        assertThat(second.rows()).containsExactly(Arrays.asList("id-2", "Name 2"));
        assertThat(second.returned()).isEqualTo(1);
        assertThat(second.hasMore()).isFalse();
        assertThat(second.nextCursor()).isNull();
        assertThat(second.truncationReason()).isNull();
        assertThat(second.suggestion()).contains("things");
    }

    @Test
    void toPageBeyondEndReturnsEmptyPage() {
        final List<String> schema = List.of("id");
        final List<List<Object>> all = List.of(Arrays.asList((Object) "id-0"));

        final Page page = Paging.toPage(schema, all, 99, 10, "some_tool", "things");
        assertThat(page.rows()).isEmpty();
        assertThat(page.returned()).isZero();
        assertThat(page.totalMatching()).isEqualTo(1);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }
}
