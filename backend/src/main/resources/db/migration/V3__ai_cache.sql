-- Reuse AI answers when the inputs are identical (same job text, or same commit + criteria + model).
-- Saves cost during testing and judging; the code checks and policy gate still run every time.
CREATE TABLE ai_cache (
    cache_key  VARCHAR(64)  PRIMARY KEY,   -- sha256 hex of everything that shaped the answer
    kind       VARCHAR(32)  NOT NULL,      -- CRITERIA | VERDICT
    model      VARCHAR(64)  NOT NULL,
    value      JSONB        NOT NULL,
    hits       INT          NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
