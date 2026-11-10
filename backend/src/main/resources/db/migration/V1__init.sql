-- ProofPay core schema: jobs, milestones, one PayPal authorization per job,
-- one release (partial capture + payout) per milestone, and an audit trail.

CREATE TABLE jobs (
    id               UUID PRIMARY KEY,
    title            VARCHAR(200)  NOT NULL,
    description      TEXT          NOT NULL,
    client_email     VARCHAR(254)  NOT NULL,
    freelancer_email VARCHAR(254)  NOT NULL,
    currency         VARCHAR(3)      NOT NULL DEFAULT 'USD',
    total_amount     NUMERIC(12,2) NOT NULL CHECK (total_amount > 0),
    status           VARCHAR(32)   NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL
);

CREATE TABLE milestones (
    id               UUID PRIMARY KEY,
    job_id           UUID          NOT NULL REFERENCES jobs(id),
    seq              INT           NOT NULL,
    title            VARCHAR(200)  NOT NULL,
    amount           NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    criteria_json    JSONB,
    deliverable_url  VARCHAR(500),
    status           VARCHAR(32)   NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL,
    UNIQUE (job_id, seq)
);

CREATE TABLE payment_authorizations (
    id                      UUID PRIMARY KEY,
    job_id                  UUID          NOT NULL UNIQUE REFERENCES jobs(id),
    order_request_id        UUID          NOT NULL UNIQUE,
    paypal_order_id         VARCHAR(64)   UNIQUE,
    paypal_authorization_id VARCHAR(64)   UNIQUE,
    amount                  NUMERIC(12,2) NOT NULL,
    captured_amount         NUMERIC(12,2) NOT NULL DEFAULT 0,
    currency                VARCHAR(3)      NOT NULL,
    status                  VARCHAR(32)   NOT NULL,
    expires_at              TIMESTAMPTZ,
    created_at              TIMESTAMPTZ   NOT NULL,
    updated_at              TIMESTAMPTZ   NOT NULL,
    CHECK (captured_amount <= amount)
);

-- capture_request_id / payout_request_id: generated once when the release row is
-- created and reused ONLY for retries of this release (PayPal-Request-Id replay gotcha).
CREATE TABLE releases (
    id                    UUID PRIMARY KEY,
    job_id                UUID          NOT NULL REFERENCES jobs(id),
    milestone_id          UUID          NOT NULL UNIQUE REFERENCES milestones(id),
    authorization_id      UUID          NOT NULL REFERENCES payment_authorizations(id),
    amount                NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    currency              VARCHAR(3)      NOT NULL,
    payee_email           VARCHAR(254)  NOT NULL,
    final_capture         BOOLEAN       NOT NULL,
    status                VARCHAR(32)   NOT NULL,
    capture_request_id    UUID          NOT NULL UNIQUE,
    payout_request_id     UUID          NOT NULL UNIQUE,
    paypal_capture_id     VARCHAR(64)   UNIQUE,
    paypal_payout_batch_id VARCHAR(64)  UNIQUE,
    paypal_payout_item_id VARCHAR(64),
    failure_reason        VARCHAR(500),
    version               BIGINT        NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ   NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL
);

CREATE INDEX idx_releases_status ON releases(status);

CREATE TABLE audit_events (
    id           BIGSERIAL PRIMARY KEY,
    job_id       UUID,
    milestone_id UUID,
    release_id   UUID,
    type         VARCHAR(64)  NOT NULL,
    actor        VARCHAR(32)  NOT NULL,
    detail       JSONB,
    created_at   TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_audit_job ON audit_events(job_id, created_at);

-- Webhook de-duplication: PayPal retries deliveries.
CREATE TABLE webhook_events (
    event_id    VARCHAR(64) PRIMARY KEY,
    event_type  VARCHAR(64) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL
);
