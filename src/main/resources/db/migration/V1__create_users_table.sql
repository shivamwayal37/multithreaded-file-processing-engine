CREATE TABLE csv_jobs (
    id          VARCHAR(36) PRIMARY KEY,
    file_hash   VARCHAR(64) NOT NULL UNIQUE,
    original_filename VARCHAR(255),
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    total_rows  INT NOT NULL,
    processed   INT NOT NULL DEFAULT 0,
    failed      INT NOT NULL DEFAULT 0,
    error_message VARCHAR(500),
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE imported_users (
    id          BIGSERIAL PRIMARY KEY,
    job_id      VARCHAR(36) NOT NULL REFERENCES csv_jobs(id),
    email       VARCHAR(255) NOT NULL,
    name        VARCHAR(255),
    row_fingerprint VARCHAR(64) NOT NULL UNIQUE,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_imported_users_job_id ON imported_users(job_id);
