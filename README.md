# Multithreaded File Processing Engine

Spring Boot service for large CSV imports with asynchronous job submission, bounded worker pools, idempotent persistence, and live progress over Server-Sent Events.

## What It Does

- Accepts a CSV upload and returns a `jobId` immediately after staging the file to disk and hashing it.
- Processes rows in parallel using a bounded `ThreadPoolExecutor`.
- Tracks progress with `AtomicInteger` counters and persists job state to PostgreSQL.
- Streams progress updates to clients with SSE.
- Prevents duplicate imports by hashing the whole file and by enforcing a unique row fingerprint.
- Skips invalid rows without failing the whole job.

## Architecture

1. `POST /api/jobs/upload` stages the upload to a temp file and computes a SHA-256 file hash.
2. If the same file was already submitted, the existing `jobId` is returned instead of creating a new job.
3. A coordinator thread counts rows, marks the job as `PROCESSING`, and dispatches CSV chunks to the worker pool.
4. Worker threads validate and persist rows independently.
5. Progress is emitted over SSE and stored in `csv_jobs`.

## Why These Choices

- `ThreadPoolExecutor`:
  Uses `corePoolSize = availableProcessors`, `maxPoolSize = core * 2`, a bounded queue, and `CallerRunsPolicy`.
- `CallerRunsPolicy` over `AbortPolicy`:
  Backpressure is preferable to dropping work during burst uploads.
- `AtomicInteger` and `volatile`:
  Multiple worker threads update progress concurrently, so plain `int` fields would race.
- `CompletableFuture`:
  Lets chunk work fan out and then join when the full job is complete.
- SSE over polling:
  Progress updates are one-way server pushes, which is lighter than polling and simpler than websockets for this use case.
- Idempotency:
  `csv_jobs.file_hash` stops duplicate uploads from creating new jobs and `imported_users.row_fingerprint` prevents duplicate row inserts.

## Endpoints

- `POST /api/jobs/upload`
  Multipart field: `file`
- `GET /api/jobs/{jobId}`
- `GET /api/jobs/{jobId}/events`

## Schema

- `csv_jobs`
  Stores durable job metadata, counts, status, and upload hash.
- `imported_users`
  Stores imported rows with a unique `row_fingerprint`.

## Running Locally

```bash
./mvnw spring-boot:run
```

PostgreSQL defaults in `application.yaml`:

- database: `csvdb`
- username: `postgres`
- password: `postgres`

## Running With Docker

```bash
./mvnw package -DskipTests
docker compose up --build
```

## Testing

```bash
./mvnw test
```

Current automated coverage focuses on:

- controller contract for upload, status, and SSE endpoints
- `JobState` progress behavior
- duplicate file submission and persisted-state fallback

## Current Status

- Completed:
  async upload workflow, bounded worker pool, SSE progress streaming, Flyway schema, durable job state, idempotent duplicate protection, graceful bad-row handling, and basic tests
- Still to add for a fully polished portfolio version:
  benchmark/load-test evidence, retry strategy for transient DB failures, cleanup for old job history, and a richer importer domain model