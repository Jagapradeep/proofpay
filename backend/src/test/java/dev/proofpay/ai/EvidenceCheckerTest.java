package dev.proofpay.ai;

import dev.proofpay.ai.VerifierTools.CriterionResult;
import dev.proofpay.ai.VerifierTools.Evidence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceCheckerTest {

    private final EvidenceChecker checker = new EvidenceChecker();

    private final Map<String, String> files = Map.of(
            "tempconv/core.py", "def c_to_f(c):\n    return c * 9 / 5 + 32\n",
            "tests/test_core.py", "def test_boiling():\n    assert c_to_f(100) == 212\n");

    private static CriterionResult passed(String id, String path, String quote) {
        return new CriterionResult(id, true, 0.9, List.of(new Evidence(path, quote)), "ok");
    }

    @Test
    void verbatimQuotesProveCriteria() {
        var r = checker.check(List.of("M1-C1", "M1-C2"), List.of(
                passed("M1-C1", "tempconv/core.py", "return c * 9 / 5 + 32"),
                passed("M1-C2", "./tests/test_core.py", "assert   c_to_f(100) == 212")), files);
        assertThat(r.allProven()).isTrue();
        assertThat(r.fakeQuotes()).isZero();
    }

    @Test
    void madeUpQuoteLeavesCriterionUnproven() {
        var r = checker.check(List.of("M1-C1"),
                List.of(passed("M1-C1", "tempconv/core.py", "def kelvin_to_celsius(k):")), files);
        assertThat(r.allProven()).isFalse();
        assertThat(r.unprovenCriteria()).containsExactly("M1-C1");
        assertThat(r.fakeQuotes()).isEqualTo(1);
    }

    @Test
    void quoteFromTheWrongFileDoesNotCount() {
        var r = checker.check(List.of("M1-C1"),
                List.of(passed("M1-C1", "tests/test_core.py", "return c * 9 / 5 + 32")), files);
        assertThat(r.allProven()).isFalse();
    }

    @Test
    void skippedCriterionCountsAsNotPassed() {
        var r = checker.check(List.of("M1-C1", "M1-C2"),
                List.of(passed("M1-C1", "tempconv/core.py", "return c * 9 / 5 + 32")), files);
        assertThat(r.unprovenCriteria()).containsExactly("M1-C2");
        assertThat(r.criteria().get(1).note()).contains("Not assessed");
    }

    @Test
    void tinyQuotesAreRejected() {
        assertThat(EvidenceChecker.verify("tempconv/core.py", "def", files)).isFalse();
    }

    @Test
    void readmeQuoteProvesOnlyDocumentationCriteria() {
        var files = java.util.Map.of("README.md", "Supports Kelvin with an absolute-zero check.\n",
                "core.py", "def c_to_f(c):\n    return c * 9 / 5 + 32\n");
        var claim = new dev.proofpay.ai.VerifierTools.CriterionResult("M1-C1", true, 1.0,
                java.util.List.of(new dev.proofpay.ai.VerifierTools.Evidence("README.md", "Supports Kelvin with an absolute-zero check.")), "ok");
        var checker = new EvidenceChecker();

        var behaviour = checker.check(java.util.List.of("M1-C1"), java.util.List.of(claim), files, java.util.Set.of());
        assertThat(behaviour.allProven()).as("the payee's own sentence is not proof of behaviour").isFalse();
        assertThat(behaviour.fakeQuotes()).isZero();

        var docs = checker.check(java.util.List.of("M1-C1"), java.util.List.of(claim), files, java.util.Set.of("M1-C1"));
        assertThat(docs.allProven()).isTrue();
    }
}
