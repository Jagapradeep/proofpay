package dev.proofpay.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.proofpay.audit.AuditService;
import dev.proofpay.domain.Job;
import dev.proofpay.domain.JobStatus;
import dev.proofpay.domain.Milestone;
import dev.proofpay.domain.MilestoneStatus;
import dev.proofpay.domain.Repositories.MilestoneRepository;
import dev.proofpay.release.PolicyGate;
import dev.proofpay.release.ReleaseProposal;
import dev.proofpay.release.ReleaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Same commit + same criteria: the second verification reuses the agent's answer, but the gate still runs. */
class VerificationCacheTest {

    private static final String URL = "https://github.com/demo/tempconv";
    private static final String SHA = "c".repeat(40);
    private static final RepoSnapshot REPO = new RepoSnapshot(URL, "demo", "tempconv", SHA,
            List.of("README.md", "tempconv/core.py"),
            Map.of("README.md", "# tempconv\nConverts C, F and K.\n",
                    "tempconv/core.py", "def c_to_f(c):\n    return c * 9 / 5 + 32\n"),
            List.of());

    private final Map<String, String> store = new HashMap<>();
    private ReleaseService releases;
    private VerificationService service;
    private VerifierToolsTest.ScriptedModel model;
    private Milestone milestone;

    @BeforeEach
    void setUp() {
        Job job = new Job("Temp", "Convert temps", "c@example.com", "f@example.com", "USD");
        milestone = job.addMilestone("Library", new BigDecimal("40.00"));
        job.setStatus(JobStatus.FUNDED);
        milestone.setStatus(MilestoneStatus.SUBMITTED);
        milestone.setDeliverableUrl(URL);
        milestone.setCriteriaJson("[{\"id\":\"M1-C1\",\"description\":\"C to F conversion\",\"howToCheck\":\"core.py\"}]");

        MilestoneRepository milestones = mock(MilestoneRepository.class);
        when(milestones.findById(milestone.getId())).thenReturn(Optional.of(milestone));
        RepoFetcher fetcher = mock(RepoFetcher.class);
        when(fetcher.fetch(URL)).thenReturn(REPO);
        releases = mock(ReleaseService.class);
        when(releases.propose(any(), any())).thenReturn(new ReleaseService.ProposalResult(
                new PolicyGate.Decision(true, List.of()), UUID.randomUUID()));

        AiCache cache = mock(AiCache.class);
        when(cache.get(anyString())).thenAnswer(inv -> Optional.ofNullable(store.get(inv.<String>getArgument(0))));
        doAnswer(inv -> store.put(inv.getArgument(0), inv.getArgument(3))).when(cache)
                .put(anyString(), any(), anyString(), anyString());

        model = new VerifierToolsTest.ScriptedModel(
                AiMessage.from(ToolExecutionRequest.builder().id("1").name("readFile")
                        .arguments("{\"path\":\"tempconv/core.py\"}").build()),
                AiMessage.from(ToolExecutionRequest.builder().id("2").name("proposeRelease").arguments("""
                        {"results":[{"criterionId":"M1-C1","passed":true,"score":0.95,
                          "evidence":[{"path":"tempconv/core.py","quote":"return c * 9 / 5 + 32"}],"note":"ok"}],
                         "amount":40,"confidence":0.9,"injectionSuspected":false,"summary":"All good."}
                        """).build()));

        service = new VerificationService(new AiProperties("key", "gpt-test", "low", 10, ""), model, fetcher,
                new InjectionScanner(), new EvidenceChecker(), releases, milestones, mock(AuditService.class),
                new ObjectMapper().findAndRegisterModules(),
                new TransactionTemplate(mock(PlatformTransactionManager.class)), cache);
    }

    @Test
    void secondRunReusesTheVerdictButStillGoesThroughTheGate() {
        var first = service.verify(milestone.getId());
        int llmCalls = model.turn;
        assertThat(llmCalls).isEqualTo(2);
        assertThat(first.cachedVerdict()).isFalse();
        assertThat(first.outcome()).isEqualTo(VerificationService.Outcome.RELEASED);
        assertThat(store).hasSize(1);

        var second = service.verify(milestone.getId());
        assertThat(model.turn).as("no new LLM calls").isEqualTo(llmCalls);
        assertThat(second.cachedVerdict()).isTrue();
        assertThat(second.filesRead()).containsExactly("README.md", "tempconv/core.py");
        assertThat(second.criteria()).singleElement().satisfies(c -> assertThat(c.proven()).isTrue());

        // The gate saw the same proposal both times: the cache only replaces the LLM call.
        ArgumentCaptor<ReleaseProposal> p = ArgumentCaptor.forClass(ReleaseProposal.class);
        verify(releases, times(2)).propose(p.capture(), eq(AuditService.AGENT));
        assertThat(p.getAllValues()).extracting(ReleaseProposal::amount)
                .containsExactly(new BigDecimal("40.00"), new BigDecimal("40.00"));
    }

    @Test
    void differentCommitMissesTheCache() {
        assertThat(AiCache.key(AiCache.Kind.VERDICT, "m", SHA))
                .isNotEqualTo(AiCache.key(AiCache.Kind.VERDICT, "m", "d".repeat(40)))
                .isNotEqualTo(AiCache.key(AiCache.Kind.CRITERIA, "m", SHA))
                .isEqualTo(AiCache.key(AiCache.Kind.VERDICT, "m", SHA));
        assertThat(AiCache.key(AiCache.Kind.VERDICT, "ab", "c")).isNotEqualTo(AiCache.key(AiCache.Kind.VERDICT, "a", "bc"));
    }
}
