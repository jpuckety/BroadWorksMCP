package co.pitayagroup.mcp.broadworks.mcp.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.pitayagroup.mcp.broadworks.mcp.AlpacaException;

import co.ecg.alpaca.toolkit.messaging.response.Response;
import org.junit.jupiter.api.Test;

class AlpacaRequestsTest {

    @Test
    void ensureSuccessDoesNothingForSuccessResponse() {
        final Response response = mock(Response.class);
        when(response.isErrorResponse()).thenReturn(false);

        assertThatCode(() -> AlpacaRequests.ensureSuccess(response, "modify service provider vwave_sp"))
                .doesNotThrowAnyException();
    }

    /**
     * The toolkit parses the numeric error code out of the summary text, so the summary is where the
     * actionable reason lives. Surfacing it (instead of only the bare code) is what lets a caller see,
     * for example, <em>why</em> BroadWorks rejected an address change with error 4015.
     */
    @Test
    void ensureSuccessIncludesSummaryTextWhenPresent() {
        final Response response = mock(Response.class);
        when(response.isErrorResponse()).thenReturn(true);
        when(response.getErrorCode()).thenReturn("4015");
        when(response.getSummaryText()).thenReturn("[Error 4015] State/Province is not valid for the country");

        assertThatThrownBy(() -> AlpacaRequests.ensureSuccess(response, "modify service provider vwave_sp"))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("modify service provider vwave_sp")
                .hasMessageContaining("error code 4015")
                .hasMessageContaining("State/Province is not valid for the country");
    }

    @Test
    void ensureSuccessAppendsDetailWhenDifferentFromSummary() {
        final Response response = mock(Response.class);
        when(response.isErrorResponse()).thenReturn(true);
        when(response.getErrorCode()).thenReturn("4015");
        when(response.getSummaryText()).thenReturn("[Error 4015] Address is invalid");
        when(response.getDetailText()).thenReturn("stateOrProvince 'GA' is not configured for country 'USA'");

        assertThatThrownBy(() -> AlpacaRequests.ensureSuccess(response, "modify service provider vwave_sp"))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("Address is invalid")
                .hasMessageContaining("stateOrProvince 'GA' is not configured for country 'USA'");
    }

    @Test
    void ensureSuccessOmitsDetailWhenItDuplicatesSummary() {
        final Response response = mock(Response.class);
        when(response.isErrorResponse()).thenReturn(true);
        when(response.getErrorCode()).thenReturn("4015");
        when(response.getSummaryText()).thenReturn("[Error 4015] Address is invalid");
        when(response.getDetailText()).thenReturn("[Error 4015] Address is invalid");

        assertThatThrownBy(() -> AlpacaRequests.ensureSuccess(response, "modify service provider vwave_sp"))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("Address is invalid")
                // The summary must appear exactly once (detail not appended when it merely repeats it).
                .satisfies(ex -> assertThat(countOccurrences(ex.getMessage(), "Address is invalid")).isEqualTo(1));
    }

    @Test
    void ensureSuccessFallsBackToCodeWhenNoTextAvailable() {
        final Response response = mock(Response.class);
        when(response.isErrorResponse()).thenReturn(true);
        when(response.getErrorCode()).thenReturn("4015");
        when(response.getSummaryText()).thenReturn("  ");
        when(response.getDetailText()).thenReturn(null);

        assertThatThrownBy(() -> AlpacaRequests.ensureSuccess(response, "modify service provider vwave_sp"))
                .isInstanceOf(AlpacaException.class)
                .hasMessage("BroadWorks failed to modify service provider vwave_sp (error code 4015)");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
