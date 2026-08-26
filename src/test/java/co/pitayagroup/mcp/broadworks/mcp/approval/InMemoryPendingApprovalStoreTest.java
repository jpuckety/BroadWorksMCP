package co.pitayagroup.mcp.broadworks.mcp.approval;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryPendingApprovalStoreTest {

    private InMemoryPendingApprovalStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryPendingApprovalStore();
    }

    @Test
    void createAndGetReturnsPendingRow() {
        final PendingApproval created = pending("elicit-1", "sub-1", Instant.now().plusSeconds(60));
        store.create(created);

        assertThat(store.get("elicit-1")).contains(created);
        assertThat(store.get("missing")).isEmpty();
    }

    @Test
    void expiredRowIsTreatedAsMissing() {
        store.create(pending("elicit-expired", "sub-1", Instant.now().minusSeconds(1)));

        assertThat(store.get("elicit-expired")).isEmpty();
        assertThat(store.decide("elicit-expired", "sub-1", ApprovalDecision.APPROVED)).isEmpty();
    }

    @Test
    void decideApprovesMatchingSubject() {
        store.create(pending("elicit-2", "sub-1", Instant.now().plusSeconds(60)));

        assertThat(store.decide("elicit-2", "sub-1", ApprovalDecision.APPROVED))
                .get()
                .extracting(PendingApproval::decision)
                .isEqualTo(ApprovalDecision.APPROVED);
        assertThat(store.get("elicit-2"))
                .get()
                .extracting(PendingApproval::decision)
                .isEqualTo(ApprovalDecision.APPROVED);
    }

    @Test
    void decideWrongSubjectLooksMissing() {
        store.create(pending("elicit-3", "owner", Instant.now().plusSeconds(60)));

        assertThat(store.decide("elicit-3", "other", ApprovalDecision.APPROVED)).isEmpty();
        assertThat(store.get("elicit-3"))
                .get()
                .extracting(PendingApproval::decision)
                .isEqualTo(ApprovalDecision.PENDING);
    }

    @Test
    void decideSameDecisionIsIdempotent() {
        store.create(pending("elicit-4", "sub-1", Instant.now().plusSeconds(60)));
        store.decide("elicit-4", "sub-1", ApprovalDecision.DECLINED);

        assertThat(store.decide("elicit-4", "sub-1", ApprovalDecision.DECLINED))
                .get()
                .extracting(PendingApproval::decision)
                .isEqualTo(ApprovalDecision.DECLINED);
    }

    @Test
    void decideConflictingDecisionReturnsOriginalRow() {
        store.create(pending("elicit-5", "sub-1", Instant.now().plusSeconds(60)));
        store.decide("elicit-5", "sub-1", ApprovalDecision.APPROVED);

        assertThat(store.decide("elicit-5", "sub-1", ApprovalDecision.DECLINED))
                .get()
                .extracting(PendingApproval::decision)
                .isEqualTo(ApprovalDecision.APPROVED);
    }

    @Test
    void removeDeletesRow() {
        store.create(pending("elicit-6", "sub-1", Instant.now().plusSeconds(60)));

        store.remove("elicit-6");

        assertThat(store.get("elicit-6")).isEmpty();
    }

    private static PendingApproval pending(String id, String subject, Instant expiresAt) {
        return new PendingApproval(id, subject, "session-1", "delete user 'u-1'",
                ApprovalDecision.PENDING, expiresAt);
    }
}
