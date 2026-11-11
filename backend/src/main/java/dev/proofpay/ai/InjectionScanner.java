package dev.proofpay.ai;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic prompt-injection pre-pass over the deliverable. Plain regex, no LLM, so text in the
 * repo cannot talk it out of flagging. Its flag goes straight to the policy gate, OR-ed with the agent's.
 *
 * It is a tripwire, not a proof of absence: text is first made comparable (invisible characters removed,
 * Unicode look-alikes folded to ASCII), then the rules run per line, across line breaks, over file
 * names and over decoded base64. Anything it flags goes to a human; a false alarm never moves money.
 */
@Component
public class InjectionScanner {

    public record Finding(String path, int line, String rule, String snippet) {}

    public record ScanResult(List<Finding> findings) {
        public boolean flagged() { return !findings.isEmpty(); }
    }

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final int MAX_FINDINGS = 60;

    /** Rules applied to each line and to every run of 3 lines (after normalisation). */
    private static final Map<String, Pattern> LINE_RULES = new LinkedHashMap<>();
    static {
        LINE_RULES.put("IGNORE_INSTRUCTIONS", Pattern.compile(
                "\\b(ignore|disregard|forget|override|bypass)\\b.{0,30}\\b(previous|prior|above|earlier|all|any|your|the)\\b"
                        + ".{0,20}\\b(instructions?|prompts?|rules|guidelines|criteria|checks?)\\b", FLAGS));
        LINE_RULES.put("ADDRESSES_REVIEWER", Pattern.compile(
                "\\b(ai|llm|gpt|automated|model|bot|agent)[\\s-]*(reviewer|verifier|grader|judge|evaluator|auditor|checker)s?\\b"
                        + "\\s*[:,\\-]", FLAGS));
        LINE_RULES.put("ADDRESSES_AI", Pattern.compile(
                "\\b(note|attention|message|instructions?|reminder|notice|memo)\\s+(to|for)\\s+(the\\s+|any\\s+|all\\s+|an?\\s+)?"
                        + "(ai|llm|gpt|language\\s+model|model|assistant|agent|bot|chatbot|automated|automatic|reviewing"
                        + "|verif\\w+|grad\\w+|evaluat\\w+)\\b"
                        + "|\\bdear\\s+(ai|llm|assistant|model|reviewer|verifier|grader|evaluator|agent)\\b"
                        + "|\\bif\\s+you\\s+are\\s+an?\\s+(ai|llm|language\\s+model|assistant|automated|bot|agent)\\b", FLAGS));
        LINE_RULES.put("REVIEWER_DIRECTIVE", Pattern.compile(
                "\\b(reviewer|verifier|grader|judge|evaluator|auditor|assistant)s?\\b.{0,80}"
                        + "\\b(approve|release|pay|accept|pass|mark|score|grant)\\w*", FLAGS));
        LINE_RULES.put("PAYMENT_DIRECTIVE", Pattern.compile(
                "\\b(approve|release|authori[sz]e|send|transfer|unlock)\\b.{0,25}\\b(payment|funds|escrow|money|payout)\\b"
                        + "|\\b(pay|award|grant|release|approve)\\b.{0,25}\\b(full|entire|whole|total|maximum)\\b.{0,15}"
                        + "\\b(amount|milestone|sum|price|budget)\\b", FLAGS));
        LINE_RULES.put("SCORE_MANIPULATION", Pattern.compile(
                "\\b(set|give|assign|output|return|report|use)\\b.{0,20}\\b(confidence|score|rating)\\b.{0,20}"
                        + "(\\b1(\\.0+)?\\b|100\\s*%?|10\\s*/\\s*10|\\bmax(imum)?\\b|\\bperfect\\b|\\bfull\\b)", FLAGS));
        LINE_RULES.put("MARK_PASSED", Pattern.compile(
                "\\b(mark|treat|consider|report|count)\\b.{0,30}\\b(all|every|each)\\b.{0,20}"
                        + "\\b(criteri(a|on)|requirements?|checks?|tests?|milestones?)\\b.{0,20}"
                        + "\\b(passed|met|satisfied|complete|completed|done|green)\\b", FLAGS));
        // The payee telling the judge its own verdict ("all acceptance criteria are met").
        LINE_RULES.put("VERDICT_CLAIM", Pattern.compile(
                "\\b(all|every|each)\\b.{0,30}\\b(criteri(a|on)|requirements?|milestones?|deliverables?)\\b.{0,30}"
                        + "\\b(met|satisfied|passed|verified|fulfilled|complete|completed|approved)\\b", FLAGS));
        LINE_RULES.put("PRE_APPROVED", Pattern.compile(
                "\\b(already|pre-?)\\s*(verified|approved|reviewed|audited|accepted)\\b.{0,40}"
                        + "\\b(client|human|reviewer|proofpay|qa|auditor|payment|milestone)\\b"
                        + "|\\b(verified|approved|certified|signed\\s+off)\\s+by\\s+(the\\s+)?(client|proofpay|a\\s+human|human|qa\\b|auditor)",
                FLAGS));
        LINE_RULES.put("ROLE_HIJACK", Pattern.compile(
                "(system\\s*prompt|\\byou are now\\b|\\bnew instructions\\b|<\\|im_start\\|>|\\[/?INST\\]|^\\s*#+\\s*system\\s*:?\\s*$)",
                FLAGS));
        // Faking our own file wrapper or chat-role markers to make file text look like it comes from the system.
        LINE_RULES.put("DELIMITER_SPOOF", Pattern.compile(
                "<<<\\s*(END\\s+)?UNTRUSTED|\\bEND\\s+(OF\\s+)?UNTRUSTED\\b"
                        + "|</?\\s*(system|assistant|developer|tool_result|function_call)\\s*>"
                        + "|\\[(system|assistant|developer)\\]\\s*:", FLAGS));
        // Our verifier's own tool and field names have no business inside a deliverable.
        LINE_RULES.put("TOOL_REFERENCE", Pattern.compile("\\bpropose_?release\\b|\\binjection_?suspected\\b", FLAGS));
        // The same two ideas (ignore the rules / approve the payment) in Spanish, French, German and Portuguese.
        LINE_RULES.put("FOREIGN_DIRECTIVE", Pattern.compile(
                "\\b(ignora|ignorez|ignorieren?|ignorar|olvida|oublie[zr]?|vergiss)\\b.{0,40}"
                        + "\\b(instrucciones|instructions|anweisungen|instru[cç][oõ]es|consignes|reglas|regeln)\\b"
                        + "|\\b(aprueb[ae]|aprobar|approuve[zr]?|genehmigen?|aprov[ae]r?|liberar?|lib[eé]re[zr]?)\\b.{0,30}"
                        + "\\b(pago|paiement|zahlung|pagamento|fondos|fonds)\\b", FLAGS));
    }

    /**
     * Characters that render as nothing: zero-width, bidi controls, soft hyphen, fillers, Unicode "tag"
     * characters (invisible ASCII look-alikes), variation selectors. Shared with {@link EvidenceChecker}.
     */
    static final Pattern INVISIBLE = Pattern.compile(
            "[\\u00AD\\u034F\\u061C\\u115F\\u1160\\u17B4\\u17B5\\u180B-\\u180E\\u200B-\\u200F\\u202A-\\u202E"
                    + "\\u2060-\\u206F\\u3164\\uFE00-\\uFE0F\\uFEFF\\uFFA0\\x{E0000}-\\x{E0FFF}]");

    /**
     * The subset worth an alarm. The zero-width joiner and variation selectors are normal inside emoji
     * (and so only count when they touch a letter); a byte-order mark at the start of a file is normal too.
     */
    private static final Pattern HIDDEN = Pattern.compile(
            "[\\u00AD\\u034F\\u061C\\u115F\\u1160\\u17B4\\u17B5\\u180B-\\u180E\\u200B\\u200C\\u200E\\u200F\\u202A-\\u202E"
                    + "\\u2060-\\u206F\\u3164\\uFEFF\\uFFA0\\x{E0000}-\\x{E0FFF}]"
                    + "|(?<=[A-Za-z])[\\u200D\\uFE00-\\uFE0F]|[\\u200D\\uFE00-\\uFE0F](?=[A-Za-z])");

    /** A word that mixes Latin and Cyrillic letters: almost always a look-alike trick ("аpprove"). */
    private static final Pattern MIXED_SCRIPT = Pattern.compile(
            "[A-Za-z][A-Za-z0-9]*[\\u0400-\\u04FF]|[\\u0400-\\u04FF][\\u0400-\\u04FF0-9]*[A-Za-z]");

    private static final Pattern HTML_COMMENT = Pattern.compile("<!--([\\s\\S]*?)-->");
    /** Markdown's other invisible comment form: [//]: # (text) */
    private static final Pattern MD_COMMENT = Pattern.compile("(?m)^\\s*\\[//\\]:\\s*#\\s*[(\"'](.*)$");
    private static final Pattern COMMENT_TRIGGER = Pattern.compile(
            "\\b(approve|payment|pay|reviewer|verifier|ignore|instruction|release|ai|llm|score|confidence)\\b", FLAGS);

    private static final Pattern BASE64 = Pattern.compile("[A-Za-z0-9+/]{32,}={0,2}");
    private static final Pattern PATH_SEPARATORS = Pattern.compile("[/\\\\_.\\-]+");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    /** Cyrillic and Greek letters that look like Latin ones, with the letter they imitate. */
    private static final Map<Integer, Character> LOOKALIKES = new HashMap<>();
    static {
        String from = "аеорсухіјѕԁһԛԝкмтвнАЕОРСУХІЈЅКМТВНοαεινρτκυΑΕΟΙΝΡΤΚΒΗΜΧΥΖ";
        String to   = "aeopcyxijsdhqwkmtbhAEOPCYXIJSKMTBHoaeivptkuAEOINPTKBHMXYZ";
        for (int i = 0; i < from.length(); i++) LOOKALIKES.put((int) from.charAt(i), to.charAt(i));
    }

    public ScanResult scan(Map<String, String> files) {
        return scan(files, files.keySet());
    }

    /** @param allPaths every path in the repo, fetched or not: file names reach the agent too. */
    public ScanResult scan(Map<String, String> files, Collection<String> allPaths) {
        List<Finding> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String path : allPaths) scanPath(out, seen, path);
        for (var e : files.entrySet()) {
            scanFile(out, seen, e.getKey(), e.getValue() == null ? "" : e.getValue());
        }
        return new ScanResult(List.copyOf(out.size() > MAX_FINDINGS ? out.subList(0, MAX_FINDINGS) : out));
    }

    private void scanPath(List<Finding> out, Set<String> seen, String path) {
        if (CONTROL.matcher(path).find() || HIDDEN.matcher(path).find()) {
            add(out, seen, path, 0, "SUSPICIOUS_FILE_NAME", path);
        }
        String words = comparable(PATH_SEPARATORS.matcher(path).replaceAll(" "));
        for (var rule : LINE_RULES.entrySet()) {
            if (rule.getValue().matcher(words).find()) add(out, seen, path, 0, rule.getKey() + " (file name)", path);
        }
    }

    private void scanFile(List<Finding> out, Set<String> seen, String path, String raw) {
        String content = raw.startsWith("﻿") ? raw.substring(1) : raw; // a leading byte-order mark is harmless
        String[] lines = content.split("\\R", -1);
        String[] clean = new String[lines.length];
        boolean[] hit = new boolean[lines.length];

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (HIDDEN.matcher(line).find()) add(out, seen, path, i + 1, "HIDDEN_CHARACTERS", visible(line));
            if (MIXED_SCRIPT.matcher(line).find()) add(out, seen, path, i + 1, "LOOKALIKE_LETTERS", visible(line));
            clean[i] = comparable(line);
            for (var rule : LINE_RULES.entrySet()) {
                if (rule.getValue().matcher(clean[i]).find()) {
                    hit[i] = true;
                    add(out, seen, path, i + 1, rule.getKey(), visible(line));
                }
            }
        }
        // An instruction broken over two or three lines reads the same to an LLM; join and look again.
        for (int i = 0; i + 1 < lines.length; i++) {
            int end = Math.min(lines.length, i + 3);
            if (hit[i] || hit[i + 1] || (end == i + 3 && hit[i + 2])) continue; // already reported
            String joined = String.join(" ", Arrays.copyOfRange(clean, i, end));
            for (var rule : LINE_RULES.entrySet()) {
                if (rule.getKey().equals("ROLE_HIJACK")) continue; // its heading form is line-anchored
                if (rule.getValue().matcher(joined).find()) {
                    add(out, seen, path, i + 1, rule.getKey(),
                            visible(String.join(" ", Arrays.copyOfRange(lines, i, end))));
                    hit[i] = true;
                    break;
                }
            }
        }

        for (Pattern hidden : List.of(HTML_COMMENT, MD_COMMENT)) {
            Matcher c = hidden.matcher(content);
            while (c.find()) {
                String body = comparable(c.group(1));
                if (COMMENT_TRIGGER.matcher(body).find()) {
                    add(out, seen, path, lineOf(content, c.start()), "HIDDEN_COMMENT", visible(c.group(1)));
                }
            }
        }

        Matcher b = BASE64.matcher(content);
        for (int n = 0; n < 50 && b.find(); n++) {
            String decoded = decodeText(b.group());
            if (decoded == null) continue;
            String text = comparable(decoded.replaceAll("\\R", " "));
            for (var rule : LINE_RULES.entrySet()) {
                if (rule.getValue().matcher(text).find()) {
                    add(out, seen, path, lineOf(content, b.start()), "ENCODED_INSTRUCTION", decoded);
                    break;
                }
            }
        }
    }

    /**
     * Makes text comparable for the rules: decodes invisible "tag" characters to the ASCII they smuggle,
     * drops other invisible characters, applies Unicode compatibility folding (full-width letters,
     * ligatures) and maps look-alike Cyrillic/Greek letters to Latin.
     */
    static String comparable(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            if (cp >= 0xE0020 && cp <= 0xE007E) sb.append((char) (cp - 0xE0000));
            else sb.appendCodePoint(cp);
        });
        String t = Normalizer.normalize(INVISIBLE.matcher(sb).replaceAll(""), Normalizer.Form.NFKC);
        StringBuilder folded = new StringBuilder(t.length());
        t.codePoints().forEach(cp -> {
            Character latin = LOOKALIKES.get(cp);
            if (latin != null) folded.append(latin.charValue());
            else folded.appendCodePoint(cp);
        });
        return folded.toString();
    }

    private static String visible(String s) {
        return INVISIBLE.matcher(s).replaceAll("");
    }

    /** Base64 that decodes to readable text, or null. */
    private static String decodeText(String token) {
        try {
            byte[] bytes = Base64.getDecoder().decode(token.length() % 4 == 0 ? token
                    : token.substring(0, token.length() - token.length() % 4));
            if (bytes.length < 16) return null;
            int printable = 0;
            for (byte x : bytes) if (x == '\n' || x == '\t' || (x >= 32 && x < 127)) printable++;
            return printable >= bytes.length * 0.9 ? new String(bytes, StandardCharsets.US_ASCII) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void add(List<Finding> out, Set<String> seen, String path, int line, String rule, String text) {
        if (!seen.add(path + ":" + line + ":" + rule)) return;
        String snippet = CONTROL.matcher(text.strip()).replaceAll(" ").replaceAll("\\s+", " ");
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
