package dev.proofpay.ai;

import dev.proofpay.ai.VerifierTools.CriterionResult;
import dev.proofpay.ai.VerifierTools.Evidence;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Checks that every evidence quote the agent gives really appears, word for word, in the file it
 * names. A "passed" criterion with no real quote counts as unproven. Stops made-up evidence.
 */
@Component
public class EvidenceChecker {

    static final int MIN_QUOTE_CHARS = 8;
    private static final Pattern INVISIBLE = InjectionScanner.INVISIBLE;
    private static final Pattern SPACE = Pattern.compile("\\s+");

    public record CheckedEvidence(String path, String quote, boolean verified) {}

    public record CheckedCriterion(String criterionId, boolean passed, double score, String note,
                                   List<CheckedEvidence> evidence, boolean proven) {}

    public record Result(List<CheckedCriterion> criteria, List<String> unprovenCriteria, int fakeQuotes) {
        public boolean allProven() { return unprovenCriteria.isEmpty(); }
    }

    /**
     * @param criterionIds every criterion of the milestone; any the agent skipped counts as not passed
     */
    public Result check(List<String> criterionIds, List<CriterionResult> results, Map<String, String> files) {
        return check(criterionIds, results, files, null);
    }

    /**
     * @param docEvidenceAllowed criteria that are themselves about documentation (README, usage, licence).
     *        For every other criterion a quote from a README or other prose file exists but proves nothing:
     *        the payee wrote that sentence. null = no restriction.
     */
    public Result check(List<String> criterionIds, List<CriterionResult> results, Map<String, String> files,
                        java.util.Set<String> docEvidenceAllowed) {
        List<CheckedCriterion> out = new ArrayList<>();
        List<String> unproven = new ArrayList<>();
        int fake = 0;
        for (String id : criterionIds) {
            CriterionResult r = results == null ? null : results.stream()
                    .filter(x -> x != null && id.equalsIgnoreCase(strip(x.criterionId()))).findFirst().orElse(null);
            if (r == null) {
                out.add(new CheckedCriterion(id, false, 0, "Not assessed by the agent", List.of(), false));
                unproven.add(id);
                continue;
            }
            List<CheckedEvidence> ev = new ArrayList<>();
            boolean anyVerified = false;
            for (Evidence e : r.evidence() == null ? List.<Evidence>of() : r.evidence()) {
                if (e == null) continue;
                boolean ok = verify(e.path(), e.quote(), files);
                if (!ok) fake++;
                else if (docEvidenceAllowed == null || docEvidenceAllowed.contains(id) || !isDocumentation(e.path())) {
                    anyVerified = true;
                }
                ev.add(new CheckedEvidence(e.path(), e.quote(), ok));
            }
            boolean proven = r.passed() && anyVerified;
            if (!proven) unproven.add(id);
            out.add(new CheckedCriterion(id, r.passed(), r.score(), r.note(), ev, proven));
        }
        return new Result(out, unproven, fake);
    }

    static boolean verify(String path, String quote, Map<String, String> files) {
        String q = normalize(quote);
        if (q.length() < MIN_QUOTE_CHARS) return false;
        String content = files.get(strip(path));
        return content != null && normalize(content).contains(q);
    }

    static boolean isDocumentation(String path) {
        String name = strip(path).toLowerCase(java.util.Locale.ROOT);
        name = name.substring(name.lastIndexOf('/') + 1);
        return name.matches(".*\\.(md|markdown|rst|txt|adoc)") || name.matches("(readme|license|licence|changelog|notes).*");
    }

    static String normalize(String s) {
        if (s == null) return "";
        return SPACE.matcher(INVISIBLE.matcher(s).replaceAll("")).replaceAll(" ").strip();
    }

    private static String strip(String s) {
        if (s == null) return "";
        String t = s.strip();
        return t.startsWith("./") ? t.substring(2) : t.startsWith("/") ? t.substring(1) : t;
    }
}
