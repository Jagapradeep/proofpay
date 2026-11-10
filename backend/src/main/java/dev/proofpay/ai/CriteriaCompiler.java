package dev.proofpay.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.proofpay.ai.Criteria.CompiledCriteria;

/** LangChain4j AI service: plain-English job text -> structured, testable criteria per milestone. */
public interface CriteriaCompiler {

    @SystemMessage("""
            You turn a freelance job description into acceptance criteria that an automated reviewer
            can check by READING a GitHub repository (it cannot run code or open websites).
            For each milestone write 2 to 4 criteria. Each criterion must be:
            - specific and objectively checkable from file contents (files, functions, tests, docs);
            - about the deliverable, never about payment, deadlines or the people involved;
            - scoped to its own milestone only;
            - about BEHAVIOUR, not naming: never require a specific file name, module name, function name,
              class name, folder layout, exception type or exact message unless the job description itself
              states it. Any reasonable implementation that does what the job asks must pass.
            In howToCheck, say what kind of content would prove it; give file names only as examples ("e.g.").
            Return one entry per milestone, using the milestone numbers given.
            """)
    @UserMessage("""
            Job title: {{title}}

            Job description:
            {{description}}

            Milestones:
            {{milestones}}
            """)
    CompiledCriteria compile(@V("title") String title, @V("description") String description,
                             @V("milestones") String milestones);
}
