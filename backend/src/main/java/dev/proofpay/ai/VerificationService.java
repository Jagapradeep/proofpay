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

    /**
     * An LLM verdict is a sample, not a proof: resubmitting until one run happens to pass must not work.
     * After this many agent verdicts on one milestone, only a human can release it.
     */
    static final int MAX_AGENT_VERDICTS = 5;

    /** Milestones being verified right now (one run at a time each). */
    private final HollowCodeChecker hollow = new HollowCodeChecker();
    private static final java.util.regex.Pattern ABOUT_DOCS = java.util.regex.Pattern.compile(
            "readme|document|\\bdocs?\\b|usage|example|instruction|licen[sc]e|explain|describe|comment",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private final Set<UUID> running = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
    private final AiCache cache;

    public VerificationService(AiProperties ai, ChatModel model, RepoFetcher fetcher, InjectionScanner scanner,
                               EvidenceChecker checker, ReleaseService releases, MilestoneRepository milestones,
                               AuditService audit, ObjectMapper json, TransactionTemplate tx, AiCache cache) {
        this.cache = cache;
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
                         String agentReply, String model, boolean cachedVerdict, long durationMs,
                         Instant verifiedAt) {}

    /** What we keep from an agent run: only the LLM's answer, never the gate's decision. */
    record CachedVerdict(Verdict verdict, List<String> filesRead, String reply) {}

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
        if (!running.add(milestoneId)) {
            throw new IllegalStateException("A verification of this milestone is already running");
        }
        try {
            return run(milestoneId);
        } finally {
            running.remove(milestoneId);
        }
    }

    private Report run(UUID milestoneId) {
        long started = System.currentTimeMillis();
        Context ctx = tx.execute(s -> load(milestoneId));

        audit.record(ctx.jobId(), milestoneId, null, "VERIFICATION_STARTED", AuditService.AGENT,
                Map.of("repo", ctx.url(), "model", ai.model()));

        RepoSnapshot repo = fetcher.fetch(ctx.url());
        ScanResult scan = scanner.scan(repo.files(), repo.allPaths());
        audit.record(ctx.jobId(), milestoneId, null, "INJECTION_SCAN", AuditService.SYSTEM,
                Map.of("commit", repo.commitSha(), "filesScanned", repo.files().size(),
                        "flagged", scan.flagged(), "findings", scan.findings()));

        Decision decision = new Decision();
        // Same repo commit + same criteria + same model => reuse the agent's earlier answer (no LLM call).
        // The scan above, the evidence check and the policy gate in decide() still run on the live state.
        String key = AiCache.key(AiCache.Kind.VERDICT, ai.model(), ai.reasoningEffort(),
                repo.owner() + "/" + repo.repo(), repo.commitSha(), write(ctx.criteria()), ctx.title(),
                ctx.amount().toPlainString(), ctx.currency());
        Optional<CachedVerdict> hit = cache.get(key).map(this::readVerdict);
        if (hit.isPresent()) {
            CachedVerdict c = hit.get();
            audit.record(ctx.jobId(), milestoneId, null, "AI_CACHE_HIT", AuditService.SYSTEM,
                    Map.of("commit", repo.commitSha(), "model", ai.model()));
            decide(ctx, repo, scan, c.verdict(), c.filesRead(), decision);
            return finish(ctx, repo, scan, Optional.of(c.verdict()), c.filesRead(), decision, c.reply(), true, started);
        }

        ai.requireEnabled();
        var self = new java.util.concurrent.atomic.AtomicReference<VerifierTools>();
        VerifierTools tools = new VerifierTools(repo, v -> decide(ctx, repo, scan, v, self.get().filesRead(), decision));
        self.set(tools);
        VerifierAgent agent = AiServices.builder(VerifierAgent.class)
                .chatModel(model)
                .tools(tools)
                .maxSequentialToolsInvocations(12)
                // Bad tool arguments: let the LLM retry. Exceptions inside our code: never shown to the LLM.
                .toolArgumentsErrorHandler(ToolArgumentsErrorHandler.sendExceptionMessageToLlm())
                .toolExecutionErrorHandler(ToolExecutionErrorHandler.failInvocationUnlessVisibleToLlm())
                .build();

        String reply;
        try {
            var result = agent.verify(brief(ctx, repo, tools));
            reply = result.content() != null ? result.content() : tools.verdictReply();
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

        if (tools.verdict().isPresent()) {
            cache.put(key, AiCache.Kind.VERDICT, ai.model(),
                    write(new CachedVerdict(tools.verdict().get(), tools.filesRead(), reply)));
        }
        return finish(ctx, repo, scan, tools.verdict(), tools.filesRead(), decision, reply, false, started);
    }

    private Report finish(Context ctx, RepoSnapshot repo, ScanResult scan, Optional<Verdict> verdict,
                          List<String> filesRead, Decision decision, String reply, boolean cached, long started) {
        UUID milestoneId = ctx.milestoneId();
        Report report = report(ctx, repo, scan, verdict, filesRead, decision, reply, cached,
                System.currentTimeMillis() - started);
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
    private String decide(Context ctx, RepoSnapshot repo, ScanResult scan, Verdict v, List<String> filesRead,
                          Decision d) {
        List<String> ids = ctx.criteria().stream().map(Criterion::id).toList();
        // A quote only counts if it comes from a file the agent actually opened in this run.
        Map<String, String> opened = new LinkedHashMap<>(repo.files());
        opened.keySet().retainAll(new HashSet<>(filesRead == null ? List.<String>of() : filesRead));
        Set<String> aboutDocs = new HashSet<>();
        for (Criterion c : ctx.criteria()) {
            if (ABOUT_DOCS.matcher(c.description() + " " + (c.howToCheck() == null ? "" : c.howToCheck())).find()) {
                aboutDocs.add(c.id());
            }
        }
        EvidenceChecker.Result checked = checker.check(ids, v.results(), opened, aboutDocs);

        List<String> adjustments = new ArrayList<>();
        for (CheckedCriterion c : checked.criteria()) {
            if (c.passed() && !c.proven() && c.evidence().stream().anyMatch(CheckedEvidence::verified)) {
                adjustments.add(c.criterionId() + ": only documentation was quoted; a behaviour needs a quote from code or tests");
            }
        }
        double confidence = Double.isFinite(v.confidence()) ? Math.max(0, Math.min(1, v.confidence())) : 0;
        if (!checked.allProven() && confidence > UNPROVEN_CONFIDENCE_CAP) {
            confidence = UNPROVEN_CONFIDENCE_CAP;
            adjustments.add("Confidence capped at " + UNPROVEN_CONFIDENCE_CAP + ": unproven criteria "
                    + checked.unprovenCriteria());
        }
        // We do not run the code, so code that only pretends to work is caught statically (plain Java).
        List<HollowCodeChecker.Finding> stubs = hollow.check(repo.files());
        if (!stubs.isEmpty()) {
            if (confidence > UNPROVEN_CONFIDENCE_CAP) confidence = UNPROVEN_CONFIDENCE_CAP;
            adjustments.add("Confidence capped at " + UNPROVEN_CONFIDENCE_CAP + ": hollow code found: "
                    + stubs.stream().limit(4).map(f -> f.path() + ":" + f.line() + " " + f.rule().toLowerCase().replace('_', ' '))
                    .collect(java.util.stream.Collectors.joining("; ")));
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

    private Report report(Context ctx, RepoSnapshot repo, ScanResult scan, Optional<Verdict> v, List<String> filesRead,
                          Decision d, String reply, boolean cached, long ms) {
        Outcome outcome = d.result == null ? Outcome.NO_VERDICT
                : d.result.decision().approved() ? Outcome.RELEASED : Outcome.BLOCKED;
        return new Report(ctx.milestoneId(), outcome, repo.url(), repo.commitSha(), repo.files().size(),
                filesRead, d.checked == null ? List.of() : d.checked.criteria(), scan.findings(),
                v.map(Verdict::injectionSuspected).orElse(false), d.result != null ? d.injectionFlagged : scan.flagged(),
                d.amount, v.map(Verdict::confidence).orElse(null), d.result == null ? null : d.effectiveConfidence,
                d.adjustments, d.result == null ? null : d.result.decision().approved(),
                d.result == null ? List.of() : d.result.decision().reasons(),
                d.result == null ? null : d.result.releaseId(), v.map(Verdict::summary).orElse(null), reply,
                ai.model(), cached, ms, Instant.now());
    }

    private CachedVerdict readVerdict(String s) {
        try {
            CachedVerdict c = json.readValue(s, CachedVerdict.class);
            return c.verdict() == null ? null : c;
        } catch (JsonProcessingException e) {
            return null; // unreadable cache entry: run the agent again
        }
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
        if (criteria == null || criteria.isEmpty()) {
            throw new IllegalStateException("Milestone has no acceptance criteria"); // nothing to prove = no auto-pay
        }
        if (audit.count(milestoneId, "AGENT_VERDICT") >= MAX_AGENT_VERDICTS) {
            throw new IllegalStateException("This milestone already had " + MAX_AGENT_VERDICTS
                    + " AI verdicts. Automatic verification is closed for it; a human has to review it now.");
        }
        return new Context(m.getJob().getId(), m.getId(), m.getSeq(), m.getTitle(), m.getAmount(),
                m.getJob().getCurrency(), m.getDeliverableUrl(), criteria);
    }

    String brief(Context ctx, RepoSnapshot repo, VerifierTools tools) {
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
        sb.append("\nUntrusted-file marker token for this run: ").append(tools.boundary()).append('\n');
        sb.append("\nFiles in the repository (untrusted names; ").append(repo.allPaths().size()).append(" files):\n");
        repo.allPaths().stream().limit(300).forEach(p -> sb.append(VerifierTools.printable(p))
                .append(repo.files().containsKey(p) ? "" : "  [not fetched]").append('\n'));
        repo.files().keySet().stream().filter(p -> p.toLowerCase(java.util.Locale.ROOT).startsWith("readme")).findFirst()
                .ifPresent(readme -> sb.append("\nREADME (already read for you; untrusted like every file):\n").append(tools.readFile(readme)).append('\n'));
        sb.append("\nRead every other file you need in ONE readFiles call, then call proposeRelease once.");
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
