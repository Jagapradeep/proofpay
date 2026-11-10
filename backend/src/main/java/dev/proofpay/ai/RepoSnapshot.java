package dev.proofpay.ai;

import java.util.List;
import java.util.Map;

/**
 * The deliverable exactly as verified, pinned to one commit, so the evidence in the audit
 * trail points at code that cannot change underneath it.
 *
 * @param files   path -> text content of the files we fetched (capped)
 * @param skipped paths we listed but did not fetch (binary, too large, over the cap)
 */
public record RepoSnapshot(String url, String owner, String repo, String commitSha,
                           List<String> allPaths, Map<String, String> files, List<String> skipped) {}
