package co.pitayagroup.mcp.broadworks.mcp.approval;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

/**
 * Single-node {@link PendingApprovalStore} keyed by elicitation id.
 *
 * <p>Matches in-memory MCP sessions: state is not shared across JVMs and is lost on restart.</p>
 */
@Component
public class InMemoryPendingApprovalStore implements PendingApprovalStore {

    private final ConcurrentMap<String, PendingApproval> approvals = new ConcurrentHashMap<>();

    @Override
    public void create(PendingApproval approval) {
        approvals.put(approval.elicitationId(), approval);
    }

    @Override
    public Optional<PendingApproval> get(String elicitationId) {
        if (elicitationId == null) {
            return Optional.empty();
        }
        final PendingApproval approval = approvals.get(elicitationId);
        if (approval == null) {
            return Optional.empty();
        }
        if (isExpired(approval)) {
            approvals.remove(elicitationId, approval);
            return Optional.empty();
        }
        return Optional.of(approval);
    }

    @Override
    public Optional<PendingApproval> decide(String elicitationId, String subject, ApprovalDecision decision) {
        if (elicitationId == null || subject == null || decision == null) {
            return Optional.empty();
        }
        final AtomicReference<PendingApproval> result = new AtomicReference<>();
        approvals.compute(elicitationId, (id, existing) -> {
            if (existing == null) {
                return null;
            }
            if (isExpired(existing)) {
                return null;
            }
            if (!subject.equals(existing.subject())) {
                return existing;
            }
            if (existing.decision() != ApprovalDecision.PENDING) {
                result.set(existing);
                return existing;
            }
            final PendingApproval updated = existing.withDecision(decision);
            result.set(updated);
            return updated;
        });
        return Optional.ofNullable(result.get());
    }

    @Override
    public void remove(String elicitationId) {
        if (elicitationId != null) {
            approvals.remove(elicitationId);
        }
    }

    private static boolean isExpired(PendingApproval approval) {
        return approval.expiresAt() != null && Instant.now().isAfter(approval.expiresAt());
    }
}
