-- Core schema: users, jobs, job attempts and idempotency keys (README §15).
-- All timestamps are TIMESTAMPTZ and default to the database clock, which is the single
-- time source for leases, retries and expiry across application instances.

CREATE TABLE users (
    id            UUID         PRIMARY KEY,
    username      VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'))
);

CREATE TABLE jobs (
    id                  UUID         PRIMARY KEY,
    owner_id            UUID         NOT NULL REFERENCES users (id),
    status              VARCHAR(20)  NOT NULL,
    payload             JSONB        NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    retry_count         INTEGER      NOT NULL DEFAULT 0,
    max_retries         INTEGER      NOT NULL,
    next_retry_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    worker_id           VARCHAR(128),
    lease_until         TIMESTAMPTZ,
    heartbeat_at        TIMESTAMPTZ,
    fencing_token       BIGINT       NOT NULL DEFAULT 0,
    cancel_requested_at TIMESTAMPTZ,
    error_message       TEXT,

    CONSTRAINT ck_jobs_status
        CHECK (status IN ('PENDING', 'RUNNING', 'CANCEL_REQUESTED', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_jobs_payload_object CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_jobs_max_retries CHECK (max_retries >= 0),
    CONSTRAINT ck_jobs_retry_count CHECK (retry_count >= 0 AND retry_count <= max_retries),
    CONSTRAINT ck_jobs_fencing_token CHECK (fencing_token >= 0),

    -- Only jobs owned by a worker carry ownership fields; every other state has none,
    -- so a released or finished job can never look owned.
    CONSTRAINT ck_jobs_ownership CHECK (
        (status IN ('RUNNING', 'CANCEL_REQUESTED')
            AND worker_id IS NOT NULL AND lease_until IS NOT NULL)
        OR
        (status NOT IN ('RUNNING', 'CANCEL_REQUESTED')
            AND worker_id IS NULL AND lease_until IS NULL AND heartbeat_at IS NULL)
    ),
    CONSTRAINT ck_jobs_cancel_requested_at
        CHECK (status <> 'CANCEL_REQUESTED' OR cancel_requested_at IS NOT NULL),
    -- completed_at marks successful completion only; finished_at marks any terminal state.
    CONSTRAINT ck_jobs_completed_at CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)),
    CONSTRAINT ck_jobs_finished_at
        CHECK ((status IN ('COMPLETED', 'FAILED', 'CANCELLED')) = (finished_at IS NOT NULL)),
    -- Every state except PENDING and CANCELLED (which may be cancelled before any claim) implies a claim.
    CONSTRAINT ck_jobs_started_at
        CHECK (status NOT IN ('RUNNING', 'CANCEL_REQUESTED', 'COMPLETED', 'FAILED') OR started_at IS NOT NULL)
);

CREATE TABLE job_attempts (
    id             UUID         PRIMARY KEY,
    job_id         UUID         NOT NULL REFERENCES jobs (id),
    attempt_number INTEGER      NOT NULL,
    worker_id      VARCHAR(128) NOT NULL,
    fencing_token  BIGINT       NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    started_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at    TIMESTAMPTZ,
    error_message  TEXT,

    CONSTRAINT uq_job_attempts_job_attempt UNIQUE (job_id, attempt_number),
    CONSTRAINT ck_job_attempts_attempt_number CHECK (attempt_number >= 1),
    CONSTRAINT ck_job_attempts_fencing_token CHECK (fencing_token >= 1),
    CONSTRAINT ck_job_attempts_status
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'ABANDONED')),
    CONSTRAINT ck_job_attempts_finished_at CHECK ((status = 'RUNNING') = (finished_at IS NULL))
);

CREATE TABLE idempotency_keys (
    id              UUID         PRIMARY KEY,
    owner_id        UUID         NOT NULL REFERENCES users (id),
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    job_id          UUID         NOT NULL REFERENCES jobs (id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ  NOT NULL,

    CONSTRAINT uq_idempotency_owner_key UNIQUE (owner_id, idempotency_key),
    CONSTRAINT ck_idempotency_key_not_blank CHECK (length(btrim(idempotency_key)) > 0),
    CONSTRAINT ck_idempotency_expiry CHECK (expires_at > created_at)
);

-- Dispatcher claim query: WHERE status = 'PENDING' AND next_retry_at <= now()
-- ORDER BY next_retry_at, created_at ... FOR UPDATE SKIP LOCKED.
CREATE INDEX idx_jobs_pending
    ON jobs (next_retry_at, created_at)
    WHERE status = 'PENDING';

-- Recovery query: owned jobs whose lease has expired, including cancelling ones.
CREATE INDEX idx_jobs_expired_lease
    ON jobs (lease_until)
    WHERE status IN ('RUNNING', 'CANCEL_REQUESTED');

-- Per-user listing (GET /jobs, newest first); also serves the owner_id foreign key.
CREATE INDEX idx_jobs_owner_created
    ON jobs (owner_id, created_at DESC);

-- At most one active attempt per job, enforced by the database.
-- Attempt lookups by job use uq_job_attempts_job_attempt, so no separate job_id index.
CREATE UNIQUE INDEX uq_job_attempts_one_running
    ON job_attempts (job_id)
    WHERE status = 'RUNNING';

-- Periodic cleanup: DELETE ... WHERE expires_at < now().
CREATE INDEX idx_idempotency_expires
    ON idempotency_keys (expires_at);

-- Keep updated_at correct for every UPDATE, including the conditional JDBC state transitions.
CREATE FUNCTION set_updated_at() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_jobs_updated_at
    BEFORE UPDATE ON jobs
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();
