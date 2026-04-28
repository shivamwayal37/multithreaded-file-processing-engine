-- Align legacy databases with the current entity mappings.
-- This migration is idempotent and safe to run on already up-to-date schemas.

ALTER TABLE csv_jobs
    ADD COLUMN IF NOT EXISTS file_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS original_filename VARCHAR(255),
    ADD COLUMN IF NOT EXISTS status VARCHAR(20),
    ADD COLUMN IF NOT EXISTS total_rows INT,
    ADD COLUMN IF NOT EXISTS processed INT,
    ADD COLUMN IF NOT EXISTS failed INT,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;

UPDATE csv_jobs
SET file_hash = md5(
    coalesce(id, '') ||
    coalesce(created_at::text, '') ||
    random()::text ||
    clock_timestamp()::text
)
WHERE file_hash IS NULL;

UPDATE csv_jobs SET status = 'PENDING' WHERE status IS NULL;
UPDATE csv_jobs SET total_rows = 0 WHERE total_rows IS NULL;
UPDATE csv_jobs SET processed = 0 WHERE processed IS NULL;
UPDATE csv_jobs SET failed = 0 WHERE failed IS NULL;
UPDATE csv_jobs SET created_at = NOW() WHERE created_at IS NULL;
UPDATE csv_jobs SET updated_at = NOW() WHERE updated_at IS NULL;

ALTER TABLE csv_jobs
    ALTER COLUMN file_hash SET NOT NULL,
    ALTER COLUMN status SET NOT NULL,
    ALTER COLUMN total_rows SET NOT NULL,
    ALTER COLUMN processed SET NOT NULL,
    ALTER COLUMN failed SET NOT NULL,
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN updated_at SET NOT NULL,
    ALTER COLUMN status SET DEFAULT 'PENDING',
    ALTER COLUMN processed SET DEFAULT 0,
    ALTER COLUMN failed SET DEFAULT 0,
    ALTER COLUMN created_at SET DEFAULT NOW(),
    ALTER COLUMN updated_at SET DEFAULT NOW();

CREATE UNIQUE INDEX IF NOT EXISTS uq_csv_jobs_file_hash ON csv_jobs(file_hash);

ALTER TABLE imported_users
    ADD COLUMN IF NOT EXISTS job_id VARCHAR(36),
    ADD COLUMN IF NOT EXISTS email VARCHAR(255),
    ADD COLUMN IF NOT EXISTS name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS row_fingerprint VARCHAR(64),
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP;

UPDATE imported_users
SET row_fingerprint = md5(
    coalesce(id::text, '') ||
    coalesce(email, '') ||
    random()::text ||
    clock_timestamp()::text
)
WHERE row_fingerprint IS NULL;

UPDATE imported_users SET job_id = '' WHERE job_id IS NULL;
UPDATE imported_users SET email = '' WHERE email IS NULL;
UPDATE imported_users SET created_at = NOW() WHERE created_at IS NULL;

ALTER TABLE imported_users
    ALTER COLUMN job_id SET NOT NULL,
    ALTER COLUMN email SET NOT NULL,
    ALTER COLUMN row_fingerprint SET NOT NULL,
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT NOW();

CREATE UNIQUE INDEX IF NOT EXISTS uq_imported_users_row_fingerprint ON imported_users(row_fingerprint);
CREATE INDEX IF NOT EXISTS idx_imported_users_job_id ON imported_users(job_id);
