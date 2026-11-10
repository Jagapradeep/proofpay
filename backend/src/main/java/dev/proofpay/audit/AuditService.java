package dev.proofpay.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proofpay.domain.AuditEvent;
import dev.proofpay.domain.Repositories.AuditEventRepository;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/** Append-only trail of everything that happened to a job: who did what, and why. */
@Service
public class AuditService {

    public static final String SYSTEM = "SYSTEM";
    public static final String CLIENT = "CLIENT";
    public static final String FREELANCER = "FREELANCER";
    public static final String AGENT = "AGENT";
    public static final String GATE = "POLICY_GATE";
    public static final String PAYPAL = "PAYPAL";

    private final AuditEventRepository repo;
    private final ObjectMapper json;

    public AuditService(AuditEventRepository repo, ObjectMapper json) {
        this.repo = repo;
        this.json = json;
    }

    public void record(UUID jobId, UUID milestoneId, UUID releaseId, String type, String actor, Map<String, ?> detail) {
        try {
            repo.save(new AuditEvent(jobId, milestoneId, releaseId, type, actor,
                    detail == null ? null : json.writeValueAsString(detail)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
