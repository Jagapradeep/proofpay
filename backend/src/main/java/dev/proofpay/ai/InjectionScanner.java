package dev.proofpay.ai;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic prompt-injection pre-pass over the deliverable. Plain regex, no LLM, so text in the
 * repo cannot talk it out of flagging. Its flag goes straight to the policy gate, OR-ed with the agent's.
 */
@Component
public class InjectionScanner {

    public record Finding(String path, int line, String rule, String snippet) {}

    public record ScanResult(List<Finding> findings) {
        public boolean flagged() { return !findings.isEmpty(); }
    }

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    /** Rules applied to each line (after removing invisible characters). */
    private static final Map<String, Pattern> LINE_RULES = new LinkedHashMap<>();
    static {
        LINE_RULES.put("IGNORE_INSTRUCTIONS", Pattern.compile(
                "\\b(ignore|disregard|forget|override|bypass)\\b.{0,30}\\b(previous|prior|above|earlier|all|any|your|the)\\b"
                        + ".{0,20}\\b(instructions?|prompts?|rules|guidelines|criteria|checks?)\\b", FLAGS));
        LINE_RULES.put("ADDRESSES_REVIEWER", Pattern.compile(
                "\\b(ai|llm|gpt|automated|model|bot|agent)[\\s-]*(reviewer|verifier|grader|judge|evaluator|auditor|checker)s?\\b"
                        + "\\s*[:,\\-]", FLAGS));
        LINE_RULES.put("REVIEWER_DIRECTIVE", Pattern.compile(
                "\\b(reviewer|verifier|grader|judge|evaluator|auditor|assistant)s?\\b.{0,80}"
                        + "\\b(approve|release|pay|accept|pass|mark|score|grant)\\w*", FLAGS));
        LINE_RULES.put("PAYMENT_DIRECTIVE", Pattern.compile(
                "\\b(approve|release|authori[sz]e|send|transfer|unlock)\\b.{0,25}\\b(payment|funds|escrow|money|payout)\\b", FLAGS));
        LINE_RULES.put("SCORE_MANIPULATION", Pattern.compile(
                "\\b(set|give|assign|output|return|report|use)\\b.{0,20}\\b(confidence|score|rating)\\b.{0,20}"
                        + "(\\b1(\\.0+)?\\b|100\\s*%?|10\\s*/\\s*10|\\bmax(imum)?\\b|\\bperfect\\b|\\bfull\\b)", FLAGS));
        LINE_RULES.put("MARK_PASSED", Pattern.compile(
                "\\b(mark|treat|consider|report|count)\\b.{0,30}\\b(all|every|each)\\b.{0,20}"
                        + "\\b(criteri(a|on)|requirements?|checks?|tests?|milestones?)\\b.{0,20}"
                        + "\\b(passed|met|satisfied|complete|completed|done|green)\\b", FLAGS));
        LINE_RULES.put("ROLE_HIJACK", Pattern.compile(
                "(system\\s*prompt|\\byou are now\\b|\\bnew instructions\\b|<\\|im_start\\|>|\\[/?INST\\]|^\\s*#+\\s*(system|instructions?)\\s*:?\\s*$)",
                FLAGS));
    }

    /** Zero-width and bidi-control characters: classic ways to hide text from human reviewers. */
    private static final Pattern INVISIBLE = Pattern.compile("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\uFEFF]");

    private static final Pattern HTML_COMMENT = Pattern.compile("<!--([\\s\\S]*?)-->");
    private static final Pattern COMMENT_TRIGGER = Pattern.compile(
            "\\b(approve|payment|pay|reviewer|verifier|ignore|instruction|release|ai|llm|score|confidence)\\b", FLAGS);

    public ScanResult scan(Map<String, String> files) {
        List<Finding> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (var e : files.entrySet()) {
            String path = e.getKey();
            String content = e.getValue() == null ? "" : e.getValue();
            String[] lines = content.split("\\R", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (INVISIBLE.matcher(line).find()) {
                    add(out, seen, path, i + 1, "HIDDEN_CHARACTERS", line);
                }
                String clean = INVISIBLE.matcher(line).replaceAll("");
                for (var rule : LINE_RULES.entrySet()) {
                    if (rule.getValue().matcher(clean).find()) {
                        add(out, seen, path, i + 1, rule.getKey(), clean);
                    }
                }
            }
            Matcher c = HTML_COMMENT.matcher(content);
            while (c.find()) {
                String body = INVISIBLE.matcher(c.group(1)).replaceAll("");
                if (COMMENT_TRIGGER.matcher(body).find()) {
                    int line = lineOf(content, c.start());
                    add(out, seen, path, line, "HIDDEN_COMMENT", body.strip());
                }
            }
        }
        return new ScanResult(List.copyOf(out));
    }

    private static void add(List<Finding> out, Set<String> seen, String path, int line, String rule, String text) {
        if (!seen.add(path + ":" + line + ":" + rule)) return;
        String snippet = text.strip().replaceAll("\\s+", " ");
        if (snippet.length() > 160) snippet = snippet.substring(0, 157) + "...";
        out.add(new Finding(path, line, rule, snippet));
    }

    private static int lineOf(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') line++;
        }
        return line;
    }
}
