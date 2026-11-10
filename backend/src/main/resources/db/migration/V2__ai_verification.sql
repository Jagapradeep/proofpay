-- AI phase: both parties accept the compiled criteria before funding,
-- and each milestone keeps the verifier agent's latest report.

ALTER TABLE jobs ADD COLUMN client_accepted_criteria_at     TIMESTAMPTZ;
ALTER TABLE jobs ADD COLUMN freelancer_accepted_criteria_at TIMESTAMPTZ;

ALTER TABLE milestones ADD COLUMN verification_json JSONB;
