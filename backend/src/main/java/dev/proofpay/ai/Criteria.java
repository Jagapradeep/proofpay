package dev.proofpay.ai;

import dev.langchain4j.model.output.structured.Description;

import java.util.List;

/** Testable acceptance criteria: the contract both sides accept before funding. */
public final class Criteria {
    private Criteria() {}

    public record Criterion(
            @Description("Short id; will be renumbered by the server") String id,
            @Description("One objectively checkable requirement") String description,
            @Description("Which files or content in the repository would prove it") String howToCheck) {}

    public record MilestoneCriteria(
            @Description("The milestone number this list belongs to (1-based)") int milestoneSeq,
            List<Criterion> criteria) {}

    public record CompiledCriteria(List<MilestoneCriteria> milestones) {}
}
