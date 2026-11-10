package dev.proofpay.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolArgumentsErrorHandler;
import dev.langchain4j.service.tool.ToolExecutionErrorHandler;
import dev.proofpay.ai.Criteria.Criterion;
import dev.proofpay.ai.EvidenceChecker.CheckedCriterion;
import dev.proofpay.ai.EvidenceChecker.CheckedEvidence;
import dev.proofpay.ai.InjectionScanner.Finding;
import dev.proofpay.ai.InjectionScanner.ScanResult;
import dev.proofpay.ai.VerifierTools.Verdict;
import dev.proofpay.audit.AuditService;
import dev.proofpay.domain.JobStatus;
import dev.proofpay.domain.Milestone;
import dev.proofpay.domain.MilestoneStatus;
import dev.proofpay.domain.Repositories.MilestoneRepository;
import dev.proofpay.release.ReleaseProposal;
import dev.proofpay.release.ReleaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

/**
 * One verification run: fetch the repo at a pinned commit -> regex injection scan -> verifier agent
 * (LLM + tools) -> evidence check -> ReleaseService.propose(..., AGENT) -> policy gate.
 * "LLM proposes, code disposes": the agent's verdict is only an input to the gate.
 */
@Service
public class VerificationService {

    private static final Logger log = LoggerFactory.getLogger(VerificationService.class);

    static final double UNPROVEN_CONFIDENCE_CAP = 0.5;

    private final AiProperties ai;
    private final ChatModel model;
    private final RepoFetcher fetcher;
    private final InjectionScanner scanner;
    private final EvidenceChecker checker;
    private final ReleaseService releases;
    private final MilestoneRepository milestones;
    private final AuditService audit;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public VerificationService(AiProperties ai, ChatModel model, RepoFetcher fetcher, InjectionScanner scanner,
                               EvidenceChecker checker, ReleaseService releases, MilestoneRepository milestones,
                               AuditService audit, ObjectMapper json, TransactionTemplate tx) {
        this.ai = ai;
        this.model = model;
        this.fetcher = fetcher;
        this.scanner = scanner;
        this.checker = checker;
        this.releases = releases;
        this.milestones = milestones;
        this.audit = audit;
        this.json = json;
        this.tx = tx;
    }

    public enum Outcome { RELEASED, BLOCKED, NO_VERDICT }

    public record Report(UUID milestoneId, Outcome outcome, String repoUrl, String commitSha, int filesFetched,
                         List<String> filesRead, List<CheckedCriterion> criteria, List<Finding> scannerFindings,
                         boolean agentInjectionSuspected, boolean injectionFlagged, BigDecimal proposedAmount,
                         Double agentConfidence, Double effectiveConfidence, List<String> adjustments,
                         Boolean gateApproved, List<String> gateReasons, UUID releaseId, String agentSummary,
                         String agentReply, String model, long durationMs, Instant verifiedAt) {}

    record Context(UUID jobId, UUID milestoneId, int seq, String title, BigDecimal amount, String currency,
                   String url, List<Criterion> criteria) {}

    /** Filled in by the proposeRelease tool while the agent runs. */
    static final class Decision {
        EvidenceChecker.Result checked;
        boolean injectionFlagged;
        BigDecimal amount;
        double effectiveConfidence;
        List<String> adjustments = List.of();
        ReleaseService.ProposalResult result;
    }

    public Report verify(UUID milestoneId) {
        ai.requireEnabled();
        long started = System.currentTimeMillis();
        Context ctx = tx.execute(s -> load(milestoneId));

        audit.record(ctx.jobId(), milestoneId, null, "VERIFICATION_STARTED", AuditService.AGENT,
                Map.of("repo", ctx.url(), "model", ai.model()));

        RepoSnapshot repo = fetcher.fetch(ctx.url());
        ScanResult scan = scanner.scan(repo.files());
        audit.record(ctx.jobId(), milestoneId, null, "INJECTION_SCAN", AuditService.SYSTEM,
                Map.of("commit", repo.commitSha(), "filesScanned", repo.files().size(),
                        "flagged", scan.flagged(), "findings", scan.findings()));

        Decision decision = new Decision();
        VerifierTools tools = new VerifierTools(repo, v -> decide(ctx, repo, scan, v, decision));
        VerifierAgent agent = AiServices.builder(VerifierAgent.class)
                .chatModel(model)
                .tools(tools)
                .maxSequentialToolsInvocations(30)
                // Bad tool arguments: let the LLM retry. Exceptions inside our code: never shown to the LLM.
                .toolArgumentsErrorHandler(ToolArgumentsErrorHandler.sendExceptionMessageToLlm())
                .toolExecutionErrorHandler(ToolExecutionErrorHandler.failInvocationUnlessVisibleToLlm())
                .build();

        String reply;
        try {
            reply = agent.verify(brief(ctx, repo));
        } catch (RuntimeException e) {
            if (tools.verdict().isEmpty()) {
                audit.record(ctx.jobId(), milestoneId, null, "VERIFICATION_FAILED", AuditService.SYSTEM,
                        Map.of("error", String.valueOf(e.getMessage())));
                throw new AiUnavailableException("Verifier agent failed: " + e.getMessage(), e);
            }
            // The verdict (and any release) already happened; only the closing sentence failed.
            log.warn("Verifier agent errored after its verdict for milestone {}: {}", milestoneId, e.getMessage());
            reply = "";
        }

        Report report = report(ctx, repo, scan, tools, decision, reply, System.currentTimeMillis() - started);
        tx.executeWithoutResult(s -> {
            Milestone m = milestones.findById(milestoneId).orElseThrow();
            m.setVerificationJson(write(report));
            if (report.outcome() == Outcome.NO_VERDICT && m.getStatus() == MilestoneStatus.SUBMITTED) {
                m.setStatus(MilestoneStatus.NEEDS_REVIEW);
            }
        });
        if (report.outcome() == Outcome.NO_VERDICT) {
            audit.record(ctx.jobId(), milestoneId, null, "AGENT_NO_VERDICT", AuditService.AGENT,
                    Map.of("reply", reply == null ? "" : reply));
        }
        return report;
    }

    /** Runs inside the proposeRelease tool call: code checks the verdict, then the gate decides. */
    private String decide(Context ctx, RepoSnapshot repo, ScanResult scan, Verdict v, Decision d) {
        List<String> ids = ctx.criteria().stream().map(Criterion::id).toList();
        EvidenceChecker.Result checked = checker.check(ids, v.results(), repo.files());

        List<String> adjustments = new ArrayList<>();
        double confidence = Double.isFinite(v.confidence()) ? Math.max(0, Math.min(1, v.confidence())) : 0;
        if (!checked.allProven() && confidence > UNPROVEN_CONFIDENCE_CAP) {
            confidence = UNPROVEN_CONFIDENCE_CAP;
            adjustments.add("Confidence capped at " + UNPROVEN_CONFIDENCE_CAP + ": unproven criteria "
                    + checked.unprovenCriteria());
        }
        if (checked.fakeQuotes() > 0) {
            adjustments.add(checked.fakeQuotes() + " evidence quote(s) not found word for word in the repo");
        }
        boolean injection = scan.flagged() || v.injectionSuspected();
        if (scan.flagged() && !v.injectionSuspected()) {
            adjustments.add("Injection scanner flagged the repo although the agent did not");
        }
        BigDecimal amount = Double.isFinite(v.amount())
                ? BigDecimal.valueOf(v.amount()).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("commit", repo.commitSha());
        detail.put("criteria", checked.criteria());
        detail.put("agentConfidence", v.confidence());
        detail.put("agentInjectionSuspected", v.injectionSuspected());
        detail.put("summary", v.summary());
        detail.put("adjustments", adjustments);
        audit.record(ctx.jobId(), ctx.milestoneId(), null, "AGENT_VERDICT", AuditService.AGENT, detail);

        List<String> evidence = checked.criteria().stream()
                .flatMap(c -> c.evidence().stream().filter(CheckedEvidence::verified)
                        .map(e -> c.criterionId() + " " + e.path() + ": " + e.quote()))
                .limit(12).toList();
        ReleaseService.ProposalResult result = releases.propose(
                new ReleaseProposal(ctx.milestoneId(), amount, confidence, injection, evidence), AuditService.AGENT);

        d.checked = checked;
        d.injectionFlagged = injection;
        d.amount = amount;
        d.effectiveConfidence = confidence;
        d.adjustments = List.copyOf(adjustments);
        d.result = result;

        return result.decision().approved()
                ? "APPROVED by the policy gate. Release " + result.releaseId() + " started (capture + payout)."
                : "BLOCKED by the policy gate: " + String.join("; ", result.decision().reasons())
                  + ". The milestone now waits for human review.";
    }

    private Report report(Context ctx, RepoSnapshot repo, ScanResult scan, VerifierTools tools, Decision d,
                          String reply, long ms) {
        Optional<Verdict> v = tools.verdict();
        Outcome outcome = d.result == null ? Outcome.NO_VERDICT
                : d.result.decision().approved() ? Outcome.RELEASED : Outcome.BLOCKED;
        return new Report(ctx.milestoneId(), outcome, repo.url(), repo.commitSha(), repo.files().size(),
                tools.filesRead(), d.checked == null ? List.of() : d.checked.criteria(), scan.findings(),
                v.map(Verdict::injectionSuspected).orElse(false), d.result != null ? d.injectionFlagged : scan.flagged(),
                d.amount, v.map(Verdict::confidence).orElse(null), d.result == null ? null : d.effectiveConfidence,
                d.adjustments, d.result == null ? null : d.result.decision().approved(),
                d.result == null ? List.of() : d.result.decision().reasons(),
                d.result == null ? null : d.result.releaseId(), v.map(Verdict::summary).orElse(null), reply,
                ai.model(), ms, Instant.now());
    }

    private Context load(UUID milestoneId) {
        Milestone m = milestones.findById(milestoneId)
                .orElseThrow(() -> new NoSuchElementException("Milestone not found: " + milestoneId));
        if (m.getJob().getStatus() != JobStatus.FUNDED) {
            throw new IllegalStateException("Job is " + m.getJob().getStatus() + "; it must be FUNDED to verify");
        }
        if (m.getStatus() != MilestoneStatus.SUBMITTED) {
            throw new IllegalStateException("Milestone is " + m.getStatus() + "; submit a deliverable first");
        }
        if (m.getCriteriaJson() == null) {
            throw new IllegalStateException("Milestone has no acceptance criteria");
        }
        List<Criterion> criteria;
        try {
            criteria = json.readValue(m.getCriteriaJson(), new TypeReference<List<Criterion>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored criteria are not valid JSON", e);
        }
        return new Context(m.getJob().getId(), m.getId(), m.getSeq(), m.getTitle(), m.getAmount(),
                m.getJob().getCurrency(), m.getDeliverableUrl(), criteria);
    }

    String brief(Context ctx, RepoSnapshot repo) {
        StringBuilder sb = new StringBuilder();
        sb.append("Milestone ").append(ctx.seq()).append(": ").append(ctx.title()).append('\n');
        sb.append("Milestone amount: ").append(ctx.amount()).append(' ').append(ctx.currency()).append('\n');
        sb.append("Repository: ").append(repo.url()).append(" (commit ").append(repo.commitSha()).append(")\n\n");
        sb.append("Acceptance criteria:\n");
        for (Criterion c : ctx.criteria()) {
            sb.append("- ").append(c.id()).append(": ").append(c.description());
            if (c.howToCheck() != null && !c.howToCheck().isBlank()) sb.append(" (check: ").append(c.howToCheck()).append(')');
            sb.append('\n');
        }
        sb.append("\nFiles in the repository (").append(repo.allPaths().size()).append("):\n");
        repo.allPaths().stream().limit(300).forEach(p -> sb.append(p)
                .append(repo.files().containsKey(p) ? "" : "  [not fetched]").append('\n'));
        sb.append("\nRead the README and the files relevant to each criterion, then call proposeRelease once.");
        return sb.toString();
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
