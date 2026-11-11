package dev.proofpay.api;

import dev.proofpay.ai.CriteriaService;
import dev.proofpay.ai.VerificationService;
import dev.proofpay.api.ApiDtos.*;
import dev.proofpay.audit.AuditService;
import dev.proofpay.domain.Job;
import dev.proofpay.domain.Repositories.*;
import dev.proofpay.job.FundingService;
import dev.proofpay.job.JobService;
import dev.proofpay.release.ReleaseProposal;
import dev.proofpay.release.ReleaseService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class JobController {

    private final JobService jobs;
    private final FundingService funding;
    private final ReleaseService releases;
    private final JobRepository jobRepo;
    private final PaymentAuthorizationRepository authRepo;
    private final ReleaseRepository releaseRepo;
    private final AuditEventRepository auditRepo;
    private final CriteriaService criteria;
    private final VerificationService verification;

    public JobController(JobService jobs, FundingService funding, ReleaseService releases, JobRepository jobRepo,
                         PaymentAuthorizationRepository authRepo, ReleaseRepository releaseRepo,
                         AuditEventRepository auditRepo, CriteriaService criteria, VerificationService verification) {
        this.criteria = criteria;
        this.verification = verification;
        this.jobs = jobs;
        this.funding = funding;
        this.releases = releases;
        this.jobRepo = jobRepo;
        this.authRepo = authRepo;
        this.releaseRepo = releaseRepo;
        this.auditRepo = auditRepo;
    }

    // ---- jobs ----

    @PostMapping("/jobs")
    @ResponseStatus(HttpStatus.CREATED)
    public JobView create(@Valid @RequestBody CreateJobRequest req) {
        Job job = jobs.create(req.title(), req.description(), req.clientEmail(), req.freelancerEmail(), req.currency(),
                req.milestones().stream().map(m -> new JobService.NewMilestone(m.title(), m.amount())).toList());
        return view(job.getId());
    }

    @GetMapping("/jobs")
    @Transactional(readOnly = true)
    public List<JobView> list() {
        return jobRepo.findAll().stream()
                .map(j -> JobView.of(j, authRepo.findByJobId(j.getId()).orElse(null))).toList();
    }

    @GetMapping("/jobs/{id}")
    public JobView get(@PathVariable UUID id) {
        return view(id);
    }

    @GetMapping("/jobs/{id}/audit")
    public List<AuditView> audit(@PathVariable UUID id) {
        return auditRepo.findByJobIdOrderByCreatedAtAsc(id).stream().map(AuditView::of).toList();
    }

    // ---- criteria (AI compiler; both sides accept before funding) ----

    @PostMapping("/jobs/{id}/criteria")
    public JobView compileCriteria(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean fresh) {
        criteria.compile(id, fresh);
        return view(id);
    }

    @PostMapping("/jobs/{id}/criteria/accept")
    public JobView acceptCriteria(@PathVariable UUID id, @Valid @RequestBody AcceptCriteriaRequest req) {
        criteria.accept(id, req.party());
        return view(id);
    }

    // ---- funding ----

    @PostMapping("/jobs/{id}/order")
    public FundingService.OrderInfo createOrder(@PathVariable UUID id) {
        return funding.createOrder(id);
    }

    @PostMapping("/jobs/{id}/authorize")
    public JobView authorize(@PathVariable UUID id) {
        funding.authorize(id);
        return view(id);
    }

    @PostMapping("/jobs/{id}/void")
    public JobView voidFunding(@PathVariable UUID id) {
        funding.voidFunding(id);
        return view(id);
    }

    // ---- milestones & releases ----

    @PostMapping("/milestones/{id}/submit")
    public MilestoneView submit(@PathVariable UUID id, @Valid @RequestBody SubmitRequest req) {
        return MilestoneView.of(jobs.submit(id, req.deliverableUrl()));
    }

    /**
     * Manual stand-in for the verifier agent's proposeRelease tool (and later the human-review approve
     * button). It goes through the same policy gate, so it can't bypass any rule.
     */
    @PostMapping("/milestones/{id}/propose-release")
    public ProposeReleaseResponse propose(@PathVariable UUID id, @Valid @RequestBody ProposeReleaseRequest req) {
        var result = releases.propose(new ReleaseProposal(id, req.amount(), req.confidence(),
                req.injectionFlagged(), req.evidence() == null ? List.of() : req.evidence()), AuditService.CLIENT);
        return new ProposeReleaseResponse(result.decision().approved(), result.decision().reasons(), result.releaseId());
    }

    /** Runs the verifier agent on the submitted repo; its proposal goes through the policy gate. */
    @PostMapping("/milestones/{id}/verify")
    public VerificationService.Report verify(@PathVariable UUID id) {
        return verification.verify(id);
    }

    @PostMapping("/releases/{id}/retry-payout")
    public void retryPayout(@PathVariable UUID id) {
        releases.retryPayout(id);
    }

    @PostMapping("/releases/{id}/refresh")
    public void refresh(@PathVariable UUID id) {
        releases.advance(id);
    }

    @GetMapping("/ledger")
    @Transactional(readOnly = true)
    public List<LedgerRow> ledger() {
        return releaseRepo.findAllByOrderByCreatedAtDesc().stream().map(LedgerRow::of).toList();
    }

    @GetMapping("/audit")
    public List<AuditView> allAudit() {
        return auditRepo.findAllByOrderByCreatedAtDesc().stream().map(AuditView::of).toList();
    }

    private JobView view(UUID id) {
        return JobView.of(jobs.get(id), authRepo.findByJobId(id).orElse(null));
    }
}
