package dev.proofpay.ai;

import dev.proofpay.ai.Criteria.CompiledCriteria;
import dev.proofpay.ai.Criteria.Criterion;
import dev.proofpay.ai.Criteria.MilestoneCriteria;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CriteriaAndRepoTest {

    @Test
    void serverRenumbersCriteriaAndDropsJunk() {
        var compiled = new CompiledCriteria(List.of(
                new MilestoneCriteria(1, List.of(new Criterion("x", "Has a CLI entry point", "tempconv/__main__.py"),
                        new Criterion("y", " ", "blank is dropped"))),
                new MilestoneCriteria(2, List.of(new Criterion("z", "Has pytest tests", null))),
                new MilestoneCriteria(9, List.of(new Criterion("w", "Unknown milestone", "")))));

        var out = CriteriaService.normalize(compiled, 2);

        assertThat(out).containsOnlyKeys(1, 2);
        assertThat(out.get(1)).extracting(Criterion::id).containsExactly("M1-C1");
        assertThat(out.get(2)).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo("M2-C1");
            assertThat(c.howToCheck()).isEmpty();
        });
    }

    @Test
    void missingMilestoneCriteriaIsAnError() {
        var compiled = new CompiledCriteria(List.of(new MilestoneCriteria(1, List.of(new Criterion("a", "x", "y")))));
        assertThatThrownBy(() -> CriteriaService.normalize(compiled, 2))
                .isInstanceOf(AiUnavailableException.class).hasMessageContaining("milestone 2");
    }

    @Test
    void acceptsOnlyPlainRepoUrls() {
        assertThat(RepoFetcher.GITHUB_URL.matcher("https://github.com/demo/tempconv").matches()).isTrue();
        assertThat(RepoFetcher.GITHUB_URL.matcher("https://github.com/demo/tempconv.git").matches()).isTrue();
        assertThat(RepoFetcher.GITHUB_URL.matcher("https://github.com/demo/tempconv/").matches()).isTrue();
        assertThat(RepoFetcher.GITHUB_URL.matcher("https://github.com/demo").matches()).isFalse();
        assertThat(RepoFetcher.GITHUB_URL.matcher("https://evil.com/github.com/demo/x").matches()).isFalse();
    }

    @Test
    void picksTextFilesAndReadmeFirst() {
        assertThat(RepoFetcher.isText("src/app.py")).isTrue();
        assertThat(RepoFetcher.isText("README")).isTrue();
        assertThat(RepoFetcher.isText("logo.png")).isFalse();
        assertThat(RepoFetcher.isText("node_modules/x/index.js")).isFalse();
        assertThat(RepoFetcher.isText("web/node_modules/x/index.js")).isFalse();
        assertThat(RepoFetcher.isText("package-lock.json")).isFalse();
        assertThat(RepoFetcher.prioritize(List.of("a/b/c.py", "setup.py", "README.md")))
                .containsExactly("README.md", "setup.py", "a/b/c.py");
    }
}
