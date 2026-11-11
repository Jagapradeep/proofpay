package dev.proofpay.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Static check for code that only pretends to work: tests that assert nothing and functions that are
 * empty stubs. We do not run the deliverable (running a stranger's code needs a sandbox), so this is the
 * deterministic backstop behind the agent's reading: any finding caps confidence below the gate's floor
 * and the milestone goes to a human. Plain Java, no LLM, so the repo cannot argue with it.
 */
public class HollowCodeChecker {

    public record Finding(String path, int line, String rule, String snippet) {}

    private static final Pattern TEST_DECL = Pattern.compile(
            "^\\s*(async\\s+)?def\\s+test_\\w*|@Test\\b|\\b(it|test)\\s*\\(\\s*['\"`]|\\bfunc\\s+Test\\w+");
    private static final Pattern ASSERTION = Pattern.compile(
            "\\bassert\\w*\\b|\\bexpect\\s*\\(|\\.should\\b|\\braises\\b|\\bverify\\s*\\(|\\bfail\\s*\\(|\\bt\\.(Error|Fatal)");
    /** Assertions that can never fail. */
    private static final Pattern TRIVIAL = Pattern.compile(
            "^\\s*assert\\s+(True|1|not\\s+False|1\\s*==\\s*1)\\s*(,.*)?$"
                    + "|expect\\(\\s*true\\s*\\)\\s*\\.\\s*(toBe\\(\\s*true\\s*\\)|toBeTruthy\\(\\s*\\))"
                    + "|assertTrue\\(\\s*true\\s*\\)|assertEquals\\(\\s*1\\s*,\\s*1\\s*\\)");
    private static final Pattern PY_DEF = Pattern.compile("^(\\s*)(async\\s+)?def\\s+(\\w+)\\s*\\(.*\\)\\s*(->.*)?:\\s*$");
    private static final Pattern PY_STUB_LINE = Pattern.compile(
            "^(pass|\\.\\.\\.|raise\\s+NotImplementedError.*|return|return\\s+None)$");
    private static final Pattern NOT_IMPLEMENTED = Pattern.compile(
            "throw\\s+new\\s+\\w*(Error|Exception)\\(\\s*['\"`](not\\s+(yet\\s+)?implemented|todo)", Pattern.CASE_INSENSITIVE);

    public List<Finding> check(Map<String, String> files) {
        List<Finding> out = new ArrayList<>();
        for (var e : files.entrySet()) {
            String path = e.getKey();
            String lower = path.toLowerCase(Locale.ROOT);
            String name = lower.substring(lower.lastIndexOf('/') + 1);
            if (!isCode(name) || e.getValue() == null) continue;
            String[] lines = e.getValue().split("\\R", -1);
            if (name.contains("test") || name.contains("spec") || lower.contains("/tests/") || lower.startsWith("tests/")) {
                checkTests(out, path, lines);
            } else {
                if (name.endsWith(".py")) checkPythonStubs(out, path, lines);
                for (int i = 0; i < lines.length; i++) {
                    if (NOT_IMPLEMENTED.matcher(lines[i]).find()) add(out, path, i + 1, "STUB_FUNCTION", lines[i]);
                }
            }
            if (out.size() >= 20) break;
        }
        return List.copyOf(out);
    }

    private static boolean isCode(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && List.of("py", "js", "jsx", "ts", "tsx", "mjs", "java", "kt", "go", "rb", "cs", "php")
                .contains(name.substring(dot + 1));
    }

    /** A test file that declares tests but contains no assertion that could fail. */
    private static void checkTests(List<Finding> out, String path, String[] lines) {
        int firstDecl = -1, real = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.strip().startsWith("#") || line.strip().startsWith("//")) continue;
            if (TEST_DECL.matcher(line).find()) {
                if (firstDecl < 0) firstDecl = i;
                continue; // "def test_x" is a declaration, not an assertion
            }
            if (ASSERTION.matcher(line).find() && !TRIVIAL.matcher(line).find()) real++;
        }
        if (firstDecl >= 0 && real == 0) add(out, path, firstDecl + 1, "TESTS_ASSERT_NOTHING", lines[firstDecl]);
    }

    /** Python functions whose whole body is pass / ... / NotImplementedError (abstract methods excepted). */
    private static void checkPythonStubs(List<Finding> out, String path, String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            var m = PY_DEF.matcher(lines[i]);
            if (!m.matches()) continue;
            String before = i > 0 ? lines[i - 1].strip() : "";
            if (before.startsWith("@abstract") || before.startsWith("@overload") || before.contains("abc.")) continue;
            int indent = m.group(1).length();
            boolean any = false, stub = true, inDoc = false;
            for (int j = i + 1; j < lines.length && stub; j++) {
                String raw = lines[j];
                String s = raw.strip();
                if (s.isEmpty()) continue;
                if (!inDoc && raw.length() - raw.stripLeading().length() <= indent) break; // body ended
                if (inDoc) {
                    if (s.contains("\"\"\"") || s.contains("'''")) inDoc = false;
                    continue;
                }
                if (s.startsWith("#")) continue;
                if (s.startsWith("\"\"\"") || s.startsWith("'''")) {
                    String rest = s.substring(3);
                    if (!rest.contains("\"\"\"") && !rest.contains("'''")) inDoc = true;
                    continue;
                }
                any = true;
                if (!PY_STUB_LINE.matcher(s).matches()) stub = false;
            }
            if (any && stub) add(out, path, i + 1, "STUB_FUNCTION", lines[i]);
        }
    }

    private static void add(List<Finding> out, String path, int line, String rule, String text) {
        String snippet = text.strip();
        out.add(new Finding(path, line, rule, snippet.length() > 120 ? snippet.substring(0, 117) + "..." : snippet));
    }
}
