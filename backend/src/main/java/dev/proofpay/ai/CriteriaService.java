package dev.proofpay.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.proofpay.ai.Criteria.*;
import dev.proofpay.audit.AuditService;
import dev.proofpay.domain.Job;
import dev.proofpay.domain.JobStatus;
import dev.proofpay.domain.Milestone;
import dev.proofpay.domain.Repositories.JobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Compiles criteria with the LLM, then both parties accept them before the job can be funded. */
@Service
public class CriteriaService {

    public enum Party { CLIENT, FREELANCER }

    /** Criteria lock as soon as a funding order exists. */
    private static final Set<JobStatus> EDITABLE = Set.of(JobStatus.DRAFT);

    private final CriteriaCompiler compiler;
    private final AiProperties ai;
    private final JobRepository jobs;
    private final AuditService audit;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public CriteriaService(CriteriaCompiler compiler, AiProperties ai, JobRepository jobs, AuditService audit,
                           ObjectMapper json, TransactionTemplate tx) {
        this.compiler = compiler;
        this.ai = ai;
        this.jobs = jobs;
        this.audit = audit;
        this.json = json;
        this.tx = tx;
    }

    private record JobText(String title, String description, String milestones, int count) {}

    public void compile(UUID jobId) {
        ai.requireEnabled();
        JobText text = tx.execute(s -> {
            Job job = load(jobId);
            requireEditable(job);
            String ms = job.getMilestones().stream()
                    .map(m -> m.getSeq() + ". " + m.getTitle() + " (" + m.getAmount() + " " + job.getCurrency() + ")")
                    .collect(Collectors.joining("\n"));
            return new JobText(job.getTitle(), job.getDescription(), ms, job.getMilestones().size());
        });

        // The LLM call runs outside any transaction (it can take a while).
        CompiledCriteria compiled;
        try {
            compiled = compiler.compile(text.title(), text.description(), text.milestones());
        } catch (RuntimeException e) {
            throw new AiUnavailableException("Criteria compiler failed: " + e.getMessage(), e);
        }
        Map<Integer, List<Criterion>> bySeq = normalize(compiled, text.count());

        tx.executeWithoutResult(s -> {
            Job job = load(jobId);
            requireEditable(job);
            for (Milestone m : job.getMilestones()) {
                m.setCriteriaJson(write(bySeq.get(m.getSeq())));
            }
            // New criteria = new contract: earlier acceptances no longer count.
            job.setClientAcceptedCriteriaAt(null);
            job.setFreelancerAcceptedCriteriaAt(null);
            audit.record(jobId, null, null, "CRITERIA_COMPILED", AuditService.AGENT,
                    Map.of("model", ai.model(), "criteria", bySeq));
        });
    }

    public void accept(UUID jobId, Party party) {
        tx.executeWithoutResult(s -> {
            Job job = load(jobId);
            requireEditable(job);
            if (job.getMilestones().stream().anyMatch(m -> m.getCriteriaJson() == null)) {
                throw new IllegalStateException("Compile criteria before accepting them");
            }
            Instant now = Instant.now();
            if (party == Party.CLIENT) job.setClientAcceptedCriteriaAt(now);
            else job.setFreelancerAcceptedCriteriaAt(now);
            audit.record(jobId, null, null, "CRITERIA_ACCEPTED", party.name(), null);
        });
    }

    /** Server owns the ids (M1-C1, M1-C2, ...) and checks every milestone got criteria. */
    static Map<Integer, List<Criterion>> normalize(CompiledCriteria compiled, int milestoneCount) {
        if (compiled == null || compiled.milestones() == null) {
            throw new AiUnavailableException("Criteria compiler returned nothing");
        }
        Map<Integer, List<Criterion>> out = new TreeMap<>();
        for (MilestoneCriteria mc : compiled.milestones()) {
            if (mc == null || mc.milestoneSeq() < 1 || mc.milestoneSeq() > milestoneCount || mc.criteria() == null) continue;
            List<Criterion> list = out.computeIfAbsent(mc.milestoneSeq(), k -> new ArrayList<>());
            for (Criterion c : mc.criteria()) {
                if (c == null || c.description() == null || c.description().isBlank() || list.size() >= 6) continue;
                list.add(new Criterion("M" + mc.milestoneSeq() + "-C" + (list.size() + 1),
                        c.description().strip(), c.howToCheck() == null ? "" : c.howToCheck().strip()));
            }
        }
        for (int seq = 1; seq <= milestoneCount; seq++) {
            if (out.getOrDefault(seq, List.of()).isEmpty()) {
                throw new AiUnavailableException("Criteria compiler returned no criteria for milestone " + seq + "; try again");
            }
        }
        return out;
    }

    private Job load(UUID id) {
        return jobs.findById(id).orElseThrow(() -> new NoSuchElementException("Job not found: " + id));
    }

    private static void requireEditable(Job job) {
        if (!EDITABLE.contains(job.getStatus())) {
            throw new IllegalStateException("Job is " + job.getStatus() + "; criteria are locked once funding starts");
        }
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
