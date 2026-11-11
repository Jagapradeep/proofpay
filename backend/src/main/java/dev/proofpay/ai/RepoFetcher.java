package dev.proofpay.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads a public GitHub repo: 2 GitHub API calls (HEAD commit, file tree) + raw file downloads. */
@Component
public class RepoFetcher {

    static final Pattern GITHUB_URL =
            Pattern.compile("^https://github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?/?$");

    static final int MAX_FILES = 40;
    static final int MAX_FILE_BYTES = 60_000;
    static final int MAX_TOTAL_BYTES = 250_000;

    private static final Set<String> TEXT_EXT = Set.of(
            "md", "txt", "rst", "py", "java", "kt", "js", "jsx", "ts", "tsx", "mjs", "cjs", "go", "rs", "rb", "php",
            "cs", "c", "h", "cpp", "hpp", "swift", "scala", "sh", "ps1", "html", "htm", "css", "scss", "json", "yml",
            "yaml", "toml", "xml", "ini", "cfg", "sql", "gradle", "properties");
    private static final Set<String> TEXT_NAMES = Set.of("dockerfile", "makefile", "procfile", ".gitignore");
    private static final List<String> SKIP_DIRS = List.of(
            "node_modules/", ".git/", "dist/", "build/", "target/", "vendor/", ".venv/", "venv/", "__pycache__/",
            ".next/", "coverage/");
    private static final Set<String> SKIP_FILES = Set.of("package-lock.json", "yarn.lock", "pnpm-lock.yaml");

    private final RestClient api;
    private final RestClient raw;
    /** After the raw host failed, files go straight to the API for 5 minutes instead of failing one by one. */
    private volatile long rawHostDownUntil;

    public RepoFetcher(RestClient.Builder builder, AiProperties props) {
        RestClient.Builder b = builder.clone()
                .baseUrl("https://api.github.com")
                .defaultHeader(HttpHeaders.USER_AGENT, "ProofPay-Verifier")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28");
        if (props.githubToken() != null && !props.githubToken().isBlank()) {
            b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.githubToken());
        }
        this.api = b.build();
        this.raw = builder.clone().defaultHeader(HttpHeaders.USER_AGENT, "ProofPay-Verifier").build();
    }

    /** One retry on a network hiccup (e.g. a pooled connection GitHub already closed), then a clear error. */
    public RepoSnapshot fetch(String url) {
        try {
            return fetchOnce(url);
        } catch (ResourceAccessException first) {
            try {
                return fetchOnce(url);
            } catch (ResourceAccessException second) {
                throw new IllegalStateException("Could not reach GitHub to read the repository; please try again", second);
            }
        }
    }

    private RepoSnapshot fetchOnce(String url) {
        Matcher m = GITHUB_URL.matcher(url == null ? "" : url.strip());
        if (!m.matches()) {
            throw new IllegalArgumentException("Expected https://github.com/<owner>/<repo>, got: " + url);
        }
        String owner = m.group(1);
        String repo = m.group(2);
        try {
            String sha = api.get().uri("/repos/{o}/{r}/commits/HEAD", owner, repo)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github.sha")
                    .retrieve().body(String.class);
            if (sha == null || !sha.strip().matches("[0-9a-f]{40}")) {
                throw new IllegalStateException("GitHub returned no commit for " + url);
            }
            sha = sha.strip();
            JsonNode tree = api.get().uri("/repos/{o}/{r}/git/trees/{sha}?recursive=1", owner, repo, sha)
                    .retrieve().body(JsonNode.class);

            List<String> all = new ArrayList<>();
            Map<String, Long> sizes = new HashMap<>();
            for (JsonNode n : tree.path("tree")) {
                if (!"blob".equals(n.path("type").asText())) continue;
                String path = n.path("path").asText();
                all.add(path);
                sizes.put(path, n.path("size").asLong());
            }
            all.sort(Comparator.naturalOrder());

            Map<String, String> files = new LinkedHashMap<>();
            List<String> skipped = new ArrayList<>();
            long total = 0;
            for (String path : prioritize(all)) {
                long size = sizes.getOrDefault(path, 0L);
                if (!isText(path) || size > MAX_FILE_BYTES || files.size() >= MAX_FILES
                        || total + size > MAX_TOTAL_BYTES) {
                    skipped.add(path);
                    continue;
                }
                files.put(path, download(owner, repo, sha, path));
                total += size;
            }
            return new RepoSnapshot(url.strip(), owner, repo, sha, List.copyOf(all), files, skipped);
        } catch (RestClientResponseException e) {
            int s = e.getStatusCode().value();
            if (s == 404 || s == 409) throw new IllegalArgumentException("Repository not found, private or empty: " + url);
            if (s == 403 || s == 429) {
                throw new IllegalStateException("GitHub rate limit hit; set GITHUB_TOKEN in .env or wait an hour");
            }
            throw new IllegalStateException("GitHub error " + s + " reading " + url);
        }
    }

    private String download(String owner, String repo, String sha, String path) {
        StringBuilder encoded = new StringBuilder();
        for (String seg : path.split("/")) {
            if (!encoded.isEmpty()) encoded.append('/');
            encoded.append(UriUtils.encodePathSegment(seg, StandardCharsets.UTF_8));
        }
        URI uri = URI.create("https://raw.githubusercontent.com/" + owner + "/" + repo + "/" + sha + "/" + encoded);
        byte[] bytes;
        try {
            if (System.currentTimeMillis() < rawHostDownUntil) throw new ResourceAccessException("raw host skipped");
            bytes = raw.get().uri(uri).retrieve().body(byte[].class);
        } catch (ResourceAccessException e) {
            // Some networks cannot reach raw.githubusercontent.com. Same file, same pinned commit, via the API
            // (slower and counted against the API rate limit, so only as a fallback).
            rawHostDownUntil = System.currentTimeMillis() + 300_000;
            bytes = api.get().uri(URI.create("https://api.github.com/repos/" + owner + "/" + repo + "/contents/"
                            + encoded + "?ref=" + sha))
                    .header(HttpHeaders.ACCEPT, "application/vnd.github.raw+json")
                    .retrieve().body(byte[].class);
        }
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }

    /** README first, then shallow files before deep ones, so the cap keeps what matters most. */
    static List<String> prioritize(List<String> paths) {
        List<String> out = new ArrayList<>(paths);
        out.sort(Comparator
                .comparing((String p) -> !p.toLowerCase(Locale.ROOT).startsWith("readme"))
                .thenComparingInt(p -> p.split("/").length)
                .thenComparing(Comparator.naturalOrder()));
        return out;
    }

    static boolean isText(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (SKIP_DIRS.stream().anyMatch(d -> lower.startsWith(d) || lower.contains("/" + d))) return false;
        String name = lower.substring(lower.lastIndexOf('/') + 1);
        if (SKIP_FILES.contains(name)) return false;
        if (TEXT_NAMES.contains(name) || name.startsWith("readme") || name.startsWith("license")) return true;
        int dot = name.lastIndexOf('.');
        return dot >= 0 && TEXT_EXT.contains(name.substring(dot + 1));
    }
}
