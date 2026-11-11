package dev.proofpay.ai;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HollowCodeCheckerTest {

    private final HollowCodeChecker checker = new HollowCodeChecker();

    @Test
    void realCodeAndRealTestsAreClean() {
        var r = checker.check(Map.of(
                "tempconv/core.py", """
                        from abc import ABC, abstractmethod

                        def c_to_f(c: float) -> float:
                            \"\"\"Celsius to Fahrenheit.\"\"\"
                            return c * 9 / 5 + 32

                        class Unit(ABC):
                            @abstractmethod
                            def to_kelvin(self, v):
                                ...
                        """,
                "tests/test_core.py", """
                        import pytest
                        from tempconv.core import c_to_f

                        def test_boiling():
                            assert c_to_f(100) == 212

                        def test_below_zero():
                            with pytest.raises(ValueError):
                                c_to_f(-300)
                        """,
                "README.md", "def todo(): pass"));
        assertThat(r).as(r.toString()).isEmpty();
    }

    @Test
    void testsThatAssertNothingAreFlagged() {
        var r = checker.check(Map.of("tests/test_core.py", """
                def test_conversion():
                    assert True

                def test_kelvin():
                    print("ok")
                """));
        assertThat(r).extracting(HollowCodeChecker.Finding::rule).containsExactly("TESTS_ASSERT_NOTHING");
    }

    @Test
    void stubFunctionsAreFlagged() {
        var r = checker.check(Map.of(
                "tempconv/core.py", """
                        def c_to_k(c):
                            \"\"\"Fully implemented and tested.\"\"\"
                            pass

                        def k_to_c(k):
                            raise NotImplementedError
                        """,
                "src/convert.ts", "export function toK(c: number) { throw new Error('not implemented'); }\n"));
        assertThat(r).extracting(HollowCodeChecker.Finding::rule).containsOnly("STUB_FUNCTION").hasSize(3);
    }
}
