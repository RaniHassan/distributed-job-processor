# Distributed Asynchronous Job Processing System

## Project Specification and Engineering Guide

**Document type:** Project source of truth  
**Target:** Java Backend Take-Home Assignment  
**Primary language:** Java 21  
**Framework:** Spring Boot 3.x  
**Database:** PostgreSQL  
**Architecture:** Modular Monolith with Distributed Deployment  
**Build tool:** Maven

---

# 1. Purpose of This Document

This document is the authoritative technical specification for the project.

It defines:
- The problem we are solving.
- Functional and non-functional requirements.
- Architecture and design decisions.
- Component responsibilities.
- Technology choices.
- Database schema.
- API contracts.
- Job lifecycle and state transitions.
- Distributed processing and concurrency mechanisms.
- Failure handling and recovery.
- Security requirements.
- Testing strategy.
- Implementation phases.
- Definition of Done.

**All implementation decisions must be consistent with this document.**

Claude Code must treat this document as the primary source of truth throughout the entire development process.

If a requirement is unclear, contradictory, or technically incomplete:
1. Identify the issue.
2. Explain the implications.
3. Propose a solution.
4. Ask for approval before changing an architectural decision.

Do not silently introduce new technologies, modify architectural decisions, remove requirements, or replace specified mechanisms with simpler alternatives.

This document distinguishes between:

- **MUST IMPLEMENT:** Functionality that must exist in the delivered application.
- **MUST DOCUMENT:** Functionality that does not necessarily need to be implemented, but must have a clear engineering design explaining how it would be handled in a production system.
- **FUTURE ENHANCEMENT:** Optional functionality that is outside the initial implementation scope.

The goal is to deliver a reliable, well-structured, tested application while demonstrating sound distributed-systems engineering.

# 2. System Overview

Build a backend system for asynchronous and distributed job processing.

Clients interact with the system through a REST API. They can:

- Create a job.
- Retrieve a job's status and details.
- Request cancellation of a job.

Jobs are persisted in PostgreSQL and processed asynchronously by background Workers.

The system must support:
- Multiple concurrent jobs.
- Bounded concurrency.
- Multiple independent application instances.
- Atomic job claiming.
- Idempotent job creation.
- Retry after transient failures.
- Recovery after application or worker crashes.
- Cooperative job cancellation.
- Authentication and authorization.
- Persistent job and attempt history.

The actual business operation is not important for this assignment. Job execution will be simulated and should take between one and five minutes.

The main engineering challenge is reliable asynchronous processing across multiple application instances.

# 3. Scope and Implementation Priorities

## 3.1 Must Implement

The following capabilities must be implemented and tested:

1. Spring Boot application structure.
2. PostgreSQL persistence.
3. Flyway database migrations.
4. REST API for creating, retrieving and cancelling jobs.
5. Asynchronous job execution.
6. Bounded Worker Pool.
7. Multiple application instances sharing the same database.
8. Atomic job claiming using PostgreSQL row locking.
9. Idempotency for job creation.
10. Job lifecycle and valid state transitions.
11. Lease and heartbeat mechanisms.
12. Fencing tokens.
13. Retry policy with transient and permanent failure classification.
14. Recovery of abandoned jobs.
15. JWT authentication.
16. Authorization on every protected API request.
17. Resource ownership validation.
18. Unit and integration tests.
19. Concurrency and failure-scenario tests.
20. Docker-based local environment.
21. Technical documentation.

## 3.2 Must Document

The following topics must have a clear design and documented production approach:

- Distributed rate limiting using a shared Token Bucket mechanism, such as Redis.
- Scaling the system beyond the initial deployment.
- Future use of a dedicated message broker.
- Cache strategy and cache consistency.
- External side-effect idempotency.
- Production deployment and operational considerations.

Do not introduce Redis, Kafka or RabbitMQ into the initial implementation unless explicitly approved.

## 3.3 Future Enhancements

Possible future improvements include:
- Redis-based distributed caching.
- Redis-based distributed rate limiting.
- Kafka or RabbitMQ integration.
- Separate API and Worker deployments.
- Advanced metrics and dashboards.
- Distributed tracing.
- Dead-letter handling for permanently failed jobs.
- Administrative APIs.

These are not reasons to complicate the initial architecture.

# 4. Functional Requirements

| ID | Requirement | Description |
|---|---|---|
| FR-01 | Create Job | Create a new job through REST API |
| FR-02 | Get Job | Retrieve job details and status |
| FR-03 | Cancel Job | Request cancellation of a job |
| FR-04 | Persistence | Persist jobs across application restarts |
| FR-05 | Async Processing | Execute jobs asynchronously |
| FR-06 | Concurrent Processing | Execute multiple jobs concurrently |
| FR-07 | Bounded Concurrency | Limit active workers per instance |
| FR-08 | Multiple Instances | Support multiple application instances |
| FR-09 | Atomic Claiming | Prevent concurrent valid ownership of the same job |
| FR-10 | Idempotency | Prevent duplicate jobs caused by retried requests |
| FR-11 | Retry | Retry eligible failed jobs |
| FR-12 | Crash Recovery | Recover jobs abandoned after instance failure |
| FR-13 | Authentication | Authenticate API users |
| FR-14 | Authorization | Enforce permissions on every protected request |
| FR-15 | Rate Limiting | Define and document a distributed rate-limiting approach |
| FR-16 | Logging | Log important operations and failures |
| FR-17 | Monitoring | Expose health and operational metrics |

# 5. Non-Functional Requirements

- **Reliability:** Recover from application and worker failures.
- **Durability:** Persist accepted jobs in PostgreSQL.
- **Scalability:** Allow additional application instances.
- **Concurrency Safety:** Prevent conflicting job ownership and stale updates.
- **Maintainability:** Use clear separation of responsibilities.
- **Security:** Protect user data and enforce access control.
- **Observability:** Provide logs, metrics and health checks.
- **Performance:** Return API responses without waiting for job execution.
- **Consistency:** Perform critical state transitions atomically.

Do not invent unsupported SLAs or latency guarantees. Performance targets should be measured and documented rather than assumed.

# 6. Architecture Decisions

## 6.1 Modular Monolith with Distributed Deployment

Use a Modular Monolith architecture.

The application will have:
- One codebase.
- One Spring Boot application.
- One deployable application artifact.
- One Docker image.
- Clearly separated internal modules.

The same application can run as multiple independent JVM processes or containers.

Each instance has its own:
- Memory.
- Dispatcher.
- Worker Pool.
- Local execution state.
- Scheduled tasks.

All instances connect to the same PostgreSQL database.

The system must not rely on local memory for distributed coordination.

## 6.2 Why Not Microservices?

Microservices are unnecessary for the initial scope.

They would introduce:
- Additional network communication.
- Multiple deployment units.
- More operational complexity.
- Distributed failure scenarios.
- Additional consistency concerns.

A Modular Monolith provides sufficient separation while keeping the project manageable.

## 6.3 PostgreSQL as the Persistent Job Queue

PostgreSQL is both:
- The system's primary database.
- The persistent queue used by Dispatchers to find available jobs.

Use PostgreSQL transactions and row-level locking.

The core claiming mechanism must use:

`SELECT ... FOR UPDATE SKIP LOCKED`

Do not introduce Kafka or RabbitMQ in the initial implementation.

## 6.4 PostgreSQL Is the Source of Truth

All critical job state and ownership information must be stored in PostgreSQL.

Do not rely on:
- Local JVM memory.
- Local maps.
- Local in-memory queues as the source of truth.
- Cached job status for critical decisions.

## 6.5 No Redis or Distributed Cache Initially

Do not introduce Redis for caching in the initial implementation.

There is no demonstrated performance requirement that justifies the additional infrastructure.

If read traffic becomes a bottleneck, Redis can be considered for selected read operations, with an explicit cache invalidation and consistency strategy.

## 6.6 Delivery Semantics

Do not claim exactly-once execution.

A worker may finish processing and crash before persisting its final result.

The system should therefore be designed around at-least-once execution semantics.

Use:
- Idempotency.
- Atomic state transitions.
- Lease ownership.
- Fencing tokens.
- Retry.
- Recovery.

If future jobs perform external side effects, the downstream system must also support idempotency or fencing where necessary.

# 7. Technology Stack

| Area | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.x |
| Build | Maven |
| Database | PostgreSQL |
| ORM | Spring Data JPA / Hibernate |
| Direct SQL | Spring JdbcTemplate / Native SQL |
| Database Migrations | Flyway |
| REST API | Spring Web |
| Validation | Jakarta Bean Validation |
| Authentication | Spring Security + JWT |
| Password Hashing | BCrypt |
| Concurrency | ThreadPoolExecutor |
| Scheduling | Spring Scheduling / ScheduledExecutorService |
| Health Checks | Spring Boot Actuator |
| Metrics | Micrometer |
| API Documentation | OpenAPI / Swagger |
| Unit Testing | JUnit 5 + Mockito |
| Integration Testing | Testcontainers |
| Containerization | Docker |
| Local Environment | Docker Compose |
| Logging | SLF4J + Logback |

Use stable, compatible versions available at implementation time.

Do not introduce additional frameworks or infrastructure without a documented reason and approval.

# 8. Component Architecture and Responsibilities

## 8.1 API Layer

Responsibilities:
- Receive HTTP requests.
- Validate request DTOs.
- Delegate operations to application services.
- Return appropriate HTTP responses.
- Map errors to consistent API responses.

Controllers must not contain business logic or execute SQL directly.

## 8.2 Security Module

Responsibilities:
- Authenticate users.
- Validate JWTs.
- Enforce roles and permissions.
- Validate resource ownership.
- Protect every relevant endpoint.

There are two authorization levels:

1. **Operation permission:** Is the user allowed to perform this operation?
2. **Resource ownership:** Is the requested job accessible to this user?

For example, having `JOB_CANCEL` permission does not allow a user to cancel another user's job.

Use Spring Security. Service-level authorization and ownership checks must protect business operations even if they are invoked outside an HTTP Controller.

## 8.3 Job Management Service

This is the central owner of the Job Lifecycle.

Responsibilities:
- Create jobs.
- Retrieve jobs.
- Request cancellation.
- Validate state transitions.
- Manage idempotency.
- Coordinate job and attempt updates.
- Manage retry state.
- Enforce business rules and resource ownership.

**All job lifecycle changes must go through clearly defined Job Management operations.**

Other components must not perform arbitrary job status updates.

The service may expose internal operations for Dispatcher, Worker and Recovery Manager workflows. These operations must validate ownership, fencing tokens and legal state transitions.

Separate business logic from database access.

## 8.4 Persistence Layer

Responsibilities:
- Store and retrieve jobs.
- Store job attempts.
- Store idempotency keys.
- Execute queries.
- Implement atomic database operations.

Use Spring Data JPA for regular CRUD operations.

Use JdbcTemplate or Native SQL for operations requiring precise control over:
- Row locking.
- Atomic claiming.
- Heartbeat updates.
- Recovery.
- Conditional state transitions.

## 8.5 Dispatcher

Responsibilities:
- Find eligible pending jobs.
- Check local Worker Pool capacity.
- Atomically claim jobs.
- Submit claimed jobs to the Worker Pool.

Each instance runs its own Dispatcher.

The Dispatcher must not execute job business logic.

It must only claim jobs when a local Worker slot is available.

Do not use an unbounded in-memory queue. PostgreSQL remains the persistent queue.

## 8.6 Worker Pool

Responsibilities:
- Execute jobs.
- Simulate processing for one to five minutes.
- Check cancellation requests.
- Report success or failure.
- Handle thread interruption.
- Report results through Job Management operations.

Use a bounded ThreadPoolExecutor.

Initial configuration:
- 5 worker threads per instance.
- A bounded or effectively unused local queue.
- An explicit rejection policy.
- Graceful shutdown support.

The Dispatcher must not claim jobs without available Worker capacity.

Do not create a new thread for every job.

The simulated processing operation must be interruptible and periodically check for cancellation. For example, use short execution intervals of approximately 500ms rather than one long sleep.

## 8.7 Heartbeat Scheduler

Responsibilities:
- Renew leases for active jobs.
- Send periodic heartbeat updates.
- Prevent healthy workers from being treated as abandoned.

Heartbeat execution must not depend on the same Worker thread that is processing a long-running job.

Each update must be conditional on:
- Job ID.
- Current worker ID.
- Current fencing token.
- Valid job state.

If ownership validation fails, the worker must treat its ownership as lost and must not attempt to commit a result.

## 8.8 Recovery Manager

Responsibilities:
- Detect jobs with expired leases.
- Identify jobs abandoned after instance failure.
- Mark previous attempts as ABANDONED.
- Requeue jobs if retries remain.
- Mark jobs FAILED when retry limits are exhausted.
- Handle cancellation requests appropriately.

Recovery must use atomic database operations because multiple instances may run Recovery Managers concurrently.

Two Recovery Managers must not independently recover the same job.

## 8.9 Monitoring Module

Responsibilities:
- Health checks.
- Metrics.
- Operational logging.
- Failure visibility.
- Job execution measurements.
- Queue and Worker Pool measurements.

# 9. Job Lifecycle

The supported job states are:

| State | Meaning |
|---|---|
| PENDING | Waiting for processing |
| RUNNING | Currently being processed |
| CANCEL_REQUESTED | Cancellation was requested |
| COMPLETED | Successfully completed |
| FAILED | Failed after exhausting retries |
| CANCELLED | Successfully cancelled |

Valid transitions:

- PENDING → RUNNING
- PENDING → CANCELLED
- RUNNING → COMPLETED
- RUNNING → FAILED
- RUNNING → CANCEL_REQUESTED
- CANCEL_REQUESTED → CANCELLED
- RUNNING → PENDING (retry or recovery)

Implement explicit state transition rules.

Do not allow arbitrary transitions.

## 9.1 Cancellation Workflow

When a cancellation request is received:

1. The API receives DELETE.
2. Job Management validates authentication, authorization and ownership.
3. The current job state is checked.
4. If PENDING, transition directly to CANCELLED.
5. If RUNNING, transition to CANCEL_REQUESTED.
6. The worker holding the job detects the request.
7. The worker stops cooperatively.
8. Job Management records CANCELLED.

The cancellation request may arrive at Instance B while the job is executing on Instance A.

The system must not rely on local memory or local Futures in another instance.

PostgreSQL is the source of truth.

The worker should periodically check the job state using an ownership-aware query.

A future notification mechanism may reduce cancellation latency, but it is not required initially.

## 9.2 Cancellation and Completion Race

A cancellation request may arrive at the same time that processing completes.

Resolve this using atomic conditional updates and transactions.

- If completion commits first, cancellation must observe that the job is already completed.
- If cancellation commits first, the worker must not subsequently commit COMPLETED without validating the current state and ownership.

Add integration tests for this race.

# 10. Atomic Job Claiming

This is one of the most important parts of the system.

Multiple instances run Dispatchers concurrently.

A regular SELECT can allow two Dispatchers to select the same pending job.

Use PostgreSQL row locking with:

`FOR UPDATE SKIP LOCKED`

The claim workflow:

1. Begin a short database transaction.
2. Select an eligible pending job.
3. Lock the selected row.
4. Skip rows locked by other transactions.
5. Change status to RUNNING.
6. Assign worker_id.
7. Set lease_until.
8. Increment fencing_token.
9. Create a Job Attempt.
10. Commit.
11. Start execution only after commit.

Example SQL outline:

```sql
BEGIN;

SELECT id
FROM jobs
WHERE status = 'PENDING'
  AND next_retry_at <= NOW()
ORDER BY next_retry_at, created_at
FOR UPDATE SKIP LOCKED
LIMIT 1;

-- Update ownership, status, lease and fencing token.
-- Insert the corresponding job_attempts record.

COMMIT;
```

The complete claim operation must be implemented as one atomic transaction.

Never keep the transaction open during the actual job execution.

Handle the case where no eligible job exists.

# 11. Lease, Heartbeat and Fencing Token

## 11.1 Lease

A lease represents time-limited ownership of a job.

Initial configuration:
- Lease duration: 30 seconds.
- Heartbeat interval: 10 seconds.

These values must be configurable.

## 11.2 Heartbeat

An active worker periodically extends its lease.

The update must only succeed when:
- The job is still RUNNING.
- The worker ID matches.
- The fencing token matches.

If the update fails, the worker must assume that ownership is no longer valid.

## 11.3 Fencing Token

A fencing token is a monotonically increasing ownership generation for each job.

Example:
- Worker A claims a job with token 1.
- Its lease expires.
- Worker B reclaims the job with token 2.

Worker A must not be able to update the job using token 1.

Every critical worker update must include ownership validation:

`WHERE id = :jobId AND worker_id = :workerId AND fencing_token = :token`

Fencing tokens prevent stale workers from overwriting newer ownership state in PostgreSQL.

They do not automatically prevent external side effects. External systems must also support appropriate idempotency or fencing if required.

# 12. Retry Strategy

Implement retry handling with transient and permanent failure classification.

Use explicit exception categories, such as:
- `RetryableException`
- `NonRetryableException`

Do not retry every exception indiscriminately.

Examples:

| Failure | Retry |
|---|---|
| Temporary timeout | Yes |
| Temporary network failure | Yes |
| Permanent invalid input | No |
| Unsupported operation | No |
| Unexpected internal failure | According to explicit policy |

Initial policy:
- Maximum retries: 3.
- Total attempts: initial attempt + up to 3 retries.
- Backoff: 5, 10 and 20 seconds.
- Add jitter if appropriate.

After a retryable failure:

1. Mark the current attempt FAILED.
2. Increment retry_count.
3. Calculate next_retry_at.
4. Return the job to PENDING.
5. Allow the Dispatcher to claim it later.

After exhausting retries, mark the job FAILED.

A cancellation must not be treated as an ordinary processing failure.

For the assignment simulation, provide a controlled way to simulate transient and permanent failures so the retry policy can be demonstrated and tested.

# 13. Crash Recovery

A worker or instance may fail while processing a job.

The Recovery Manager must detect RUNNING jobs whose lease has expired.

Recovery workflow:

1. Find expired leases.
2. Atomically acquire the right to recover a job.
3. Verify the job is still eligible for recovery.
4. Mark the old attempt ABANDONED.
5. If cancellation was requested, transition to CANCELLED according to the defined state rules.
6. If retries remain, increment the retry count and schedule another attempt.
7. Otherwise, mark the job FAILED.
8. Ensure stale workers cannot update the job after ownership changes.

Recovery operations must be safe across multiple instances.

Do not assume that an expired lease proves with certainty that the old worker has permanently stopped. It may have experienced a long pause or a temporary database/network partition.

Fencing tokens are required to protect database state from stale owners.

# 14. Graceful Shutdown

When an instance shuts down:

1. Stop new Dispatcher claims.
2. Stop scheduling new jobs.
3. Allow active workers to finish within a configurable timeout.
4. Do not mark unfinished jobs as completed.
5. Allow expired leases to be handled by Recovery if the process exits before work finishes.

Initial graceful shutdown timeout: 30 seconds.

Use Spring lifecycle management and shutdown hooks correctly.

# 15. Database Design

Use PostgreSQL as the primary database.

Use UUID primary keys.

Store timestamps in UTC using TIMESTAMPTZ.

## 15.1 users

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| username | VARCHAR | Unique username |
| password_hash | VARCHAR | BCrypt password hash |
| role | VARCHAR | User role |
| enabled | BOOLEAN | Account enabled |
| created_at | TIMESTAMPTZ | Creation timestamp |

Constraints:
- Primary key.
- Unique username.
- Never store plaintext passwords.

Create test users through seed data or a controlled initialization process.

## 15.2 jobs

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| owner_id | UUID | FK to users |
| status | VARCHAR | Job status |
| payload | JSONB | Job input |
| created_at | TIMESTAMPTZ | Creation time |
| updated_at | TIMESTAMPTZ | Last update |
| started_at | TIMESTAMPTZ | Processing start |
| completed_at | TIMESTAMPTZ | Processing completion |
| retry_count | INTEGER | Retries already consumed |
| max_retries | INTEGER | Maximum retries |
| next_retry_at | TIMESTAMPTZ | Next eligible execution time |
| worker_id | VARCHAR | Current owner |
| lease_until | TIMESTAMPTZ | Lease expiration |
| heartbeat_at | TIMESTAMPTZ | Last heartbeat |
| fencing_token | BIGINT | Ownership generation |
| cancel_requested_at | TIMESTAMPTZ | Cancellation request time |
| error_message | TEXT | Last error summary |

Requirements:
- Foreign key to users.
- Constraints for retry counts.
- Constraints for supported statuses.
- Appropriate indexes.
- Consistent ownership and lease fields.

For a newly created job, next_retry_at must be initialized so that the job is immediately eligible for processing.

## 15.3 job_attempts

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| job_id | UUID | FK to jobs |
| attempt_number | INTEGER | Attempt sequence |
| worker_id | VARCHAR | Worker that executed it |
| fencing_token | BIGINT | Ownership token |
| status | VARCHAR | Attempt status |
| started_at | TIMESTAMPTZ | Start time |
| finished_at | TIMESTAMPTZ | End time |
| error_message | TEXT | Failure details |

Attempt statuses:
- RUNNING
- SUCCEEDED
- FAILED
- CANCELLED
- ABANDONED

Constraints:
- Foreign key to jobs.
- Unique(job_id, attempt_number).

## 15.4 idempotency_keys

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| owner_id | UUID | FK to users |
| idempotency_key | VARCHAR | Client-provided key |
| request_hash | VARCHAR | Canonical request hash |
| job_id | UUID | FK to jobs |
| created_at | TIMESTAMPTZ | Creation time |
| expires_at | TIMESTAMPTZ | Expiration time |

Constraints:
- Unique(owner_id, idempotency_key).
- Foreign keys.

Behavior:
- Same user + same key + same request hash: return the existing job.
- Same user + same key + different request hash: return HTTP 409 Conflict.
- New key: create a new job.

Job creation and idempotency key persistence must happen in the same transaction.

Initial retention period: 24 hours.

Implement a cleanup policy for expired keys. Do not remove keys before their retention period expires.

## 15.5 Database Indexes

Initial index design:

```sql
CREATE INDEX idx_jobs_pending
ON jobs (next_retry_at, created_at)
WHERE status = 'PENDING';

CREATE INDEX idx_jobs_expired_lease
ON jobs (lease_until)
WHERE status = 'RUNNING';

CREATE INDEX idx_jobs_owner_created
ON jobs (owner_id, created_at DESC);

CREATE INDEX idx_attempts_job
ON job_attempts (job_id, attempt_number);

CREATE UNIQUE INDEX uq_idempotency_owner_key
ON idempotency_keys (owner_id, idempotency_key);
```

Validate index usefulness against actual queries using EXPLAIN ANALYZE.

Do not create unnecessary indexes.

# 16. REST API Design

All application endpoints must be versioned under:

`/api/v1`

All protected endpoints require authentication and authorization.

## 16.1 Create Job

`POST /api/v1/jobs`

Headers:
- Authorization: Bearer JWT.
- Idempotency-Key.
- Content-Type: application/json.

Request:

```json
{
  "payload": {
    "someParameter": "someValue"
  }
}
```

Response:

`202 Accepted`

```json
{
  "id": "uuid",
  "status": "PENDING",
  "createdAt": "2026-11-05T10:00:00Z"
}
```

Include a Location header pointing to the created resource.

A repeated request with the same idempotency key and request body must not create another job.

For an idempotency replay, return the existing job and identify the replay, for example with `Idempotency-Replayed: true`.

## 16.2 Get Job

`GET /api/v1/jobs/{id}`

Response:

```json
{
  "id": "uuid",
  "status": "RUNNING",
  "createdAt": "2026-11-05T10:00:00Z",
  "startedAt": "2026-11-05T10:00:05Z",
  "completedAt": null,
  "retryCount": 0,
  "maxRetries": 3,
  "error": null
}
```

Only return jobs owned by the authenticated user.

Return 404 if the job does not exist or is not accessible to the user.

## 16.3 Cancel Job

`DELETE /api/v1/jobs/{id}`

This endpoint requests cancellation. It does not physically delete the job.

- PENDING: transition to CANCELLED and return 200.
- RUNNING: transition to CANCEL_REQUESTED and return 202.
- Terminal state: return 409 according to the defined policy.

Repeated cancellation requests must be handled consistently and safely.

## 16.4 List Jobs

Optional useful extension:

`GET /api/v1/jobs?page=0&size=20&sort=createdAt,desc`

Support pagination.

Always filter by authenticated user.

Initial maximum page size: 100.

## 16.5 Get Job Attempts

Optional useful extension:

`GET /api/v1/jobs/{id}/attempts`

Return attempt history only if the parent job belongs to the authenticated user.

## 16.6 Error Handling

Use a global exception handler with `@RestControllerAdvice`.

Use Spring ProblemDetail or an equivalent consistent error response.

Example:

```json
{
  "title": "Conflict",
  "status": 409,
  "detail": "Idempotency key was already used with a different request",
  "errorCode": "IDEMPOTENCY_KEY_CONFLICT",
  "traceId": "abc-123"
}
```

Never expose stack traces, credentials or internal infrastructure details to clients.

Expected HTTP statuses:

| Code | Meaning |
|---|---|
| 200 | OK |
| 202 | Accepted |
| 400 | Bad Request |
| 401 | Unauthorized |
| 403 | Forbidden |
| 404 | Not Found |
| 409 | Conflict |
| 429 | Too Many Requests |
| 500 | Internal Server Error |
| 503 | Service Unavailable |

## 16.7 DTOs

Never expose JPA entities directly through the API.

Use request and response DTOs.

Validate requests with Jakarta Bean Validation.

Set reasonable request-size limits and validate payload structure.

# 17. Authentication and Authorization

Implement JWT authentication.

The initial implementation must include:
- Users stored in PostgreSQL.
- BCrypt password hashing.
- A login endpoint.
- JWT generation.
- JWT validation on protected endpoints.
- Role or permission checks.
- Resource ownership checks.

A full self-service registration workflow is not required.

Test users may be initialized through seed data or a controlled setup process.

Permissions should be explicit and extensible, for example:
- JOB_CREATE
- JOB_READ
- JOB_UPDATE
- JOB_CANCEL

Every protected request must be authenticated and authorized.

Do not assume that authentication alone grants access to every job.

Do not implement a generic job update endpoint unless it is explicitly required by the assignment. Job lifecycle changes must remain controlled by the service layer.

# 18. Rate Limiting — Documented Production Design

The initial implementation does not need Redis solely for rate limiting.

However, document a distributed rate-limiting solution.

Recommended production approach:
- Redis as a shared store.
- Token Bucket algorithm.
- Rate limit keyed by authenticated user ID.
- Atomic token consumption.
- HTTP 429 when the bucket is empty.
- Retry-After response header where appropriate.

An in-memory counter per application instance is not a correct distributed solution because each instance would maintain a different limit.

Do not silently add Redis to the initial deployment. Any implementation beyond the agreed scope requires approval.

# 19. Caching Strategy

Do not introduce caching in the initial implementation.

PostgreSQL remains the source of truth.

Critical operations such as claiming, heartbeat, cancellation and recovery must use current database state.

If future read traffic becomes significant, consider Cache-Aside with Redis for selected read-only data.

Any future cache implementation must define:
- TTL.
- Invalidation strategy.
- Behavior after state changes.
- Staleness tolerance.
- Failure behavior when Redis is unavailable.

Do not cache critical job ownership or lifecycle state for decision-making.

# 20. Configuration

Do not hardcode operational values.

Use application configuration and environment variables.

Initial values:

| Parameter | Initial Value |
|---|---|
| Worker threads per instance | 5 |
| Dispatcher poll interval | 500ms–1s |
| Lease duration | 30 seconds |
| Heartbeat interval | 10 seconds |
| Maximum retries | 3 |
| Retry backoff | 5s, 10s, 20s |
| Job duration | 1–5 minutes |
| Graceful shutdown | 30 seconds |
| Idempotency retention | 24 hours |
| Cancellation check interval | 500ms |

All values must be configurable.

Validate configuration at startup.

Ensure that lease and heartbeat intervals are compatible.

# 21. Package Structure

Use a feature-oriented structure with clear responsibilities.

Suggested structure:

```text
src/main/java/com/example/jobprocessor/
│
├── JobProcessorApplication.java
│
├── common/
│   ├── config/
│   ├── exception/
│   ├── response/
│   └── util/
│
├── security/
│   ├── config/
│   ├── jwt/
│   ├── service/
│   └── authorization/
│
├── user/
│   ├── domain/
│   ├── repository/
│   ├── service/
│   └── api/
│
├── job/
│   ├── domain/
│   ├── dto/
│   ├── api/
│   ├── service/
│   ├── repository/
│   ├── lifecycle/
│   └── idempotency/
│
├── worker/
│   ├── dispatcher/
│   ├── pool/
│   ├── execution/
│   └── heartbeat/
│
├── recovery/
│   ├── service/
│   └── scheduler/
│
└── monitoring/
    ├── metrics/
    └── health/
```

Adjust the structure only when there is a clear technical reason.

Avoid unnecessary abstractions and empty interfaces.

# 22. Logging and Monitoring

Use SLF4J and Logback.

Log important events:
- Job created.
- Job claimed.
- Job started.
- Job completed.
- Job failed.
- Retry scheduled.
- Cancellation requested.
- Job cancelled.
- Lease expired.
- Recovery executed.
- Authentication failures.
- Authorization failures.

Use correlation IDs or trace IDs to connect related operations.

Recommended metrics:
- jobs_created_total.
- jobs_completed_total.
- jobs_failed_total.
- jobs_cancelled_total.
- jobs_retried_total.
- jobs_running.
- jobs_pending.
- job_processing_duration.
- job_queue_wait_duration.
- worker_pool_active_threads.
- worker_pool_queue_size.
- lease_expiration_total.

Expose health checks through Spring Boot Actuator.

Protect sensitive management endpoints.

# 23. Testing Strategy

Testing is a required part of the implementation.

## 23.1 Unit Tests

Test:
- Job Management Service.
- State transition rules.
- Retry policy.
- Idempotency logic.
- Authorization logic.
- Cancellation behavior.
- Validation.

Use Mockito to isolate dependencies.

## 23.2 Integration Tests

Use Testcontainers with a real PostgreSQL instance.

Test:
- Job creation and persistence.
- Job retrieval.
- Job cancellation.
- Idempotency.
- Transaction rollback.
- Atomic claiming.
- Retry.
- Recovery.
- Fencing tokens.
- Database constraints.

## 23.3 Concurrency Tests

Test multiple Dispatchers competing for jobs.

Verify:
- The same job is not concurrently assigned to two valid owners.
- Different jobs can be claimed concurrently.
- Stale workers cannot update job state.
- Multiple Recovery Managers do not recover the same job concurrently.
- Concurrent requests with the same idempotency key do not create duplicate jobs.

## 23.4 Failure Scenario Tests

Test:
1. Application instance crashes during processing.
2. Worker fails during execution.
3. Database becomes unavailable.
4. Heartbeat update fails.
5. Lease expires.
6. Job exhausts retries.
7. Cancellation arrives during execution.
8. Cancellation races with completion.
9. Concurrent requests use the same idempotency key.
10. One user tries to access another user's job.
11. A stale worker attempts to update a reclaimed job.

## 23.5 Test Quality

Tests must verify actual database state, ownership, attempts, tokens and transitions.

Do not rely only on mocked method return values for distributed-systems behavior.

# 24. Docker and Local Environment

Provide:
- Dockerfile for the application.
- Docker Compose for local development.
- PostgreSQL container.
- Spring Boot application container.
- Ability to run at least two application instances against the same database.

Use environment variables for configuration and credentials.

Configure health checks and startup dependencies.

Use Flyway for database migrations.

Verify that two instances can process jobs concurrently without duplicate valid ownership.

# 25. Documentation Deliverables

Provide a high-quality README containing:
1. Project overview.
2. Architecture.
3. Component responsibilities.
4. Job lifecycle.
5. Atomic claiming.
6. Lease and heartbeat.
7. Fencing tokens.
8. Retry and recovery.
9. API documentation.
10. Database schema.
11. Authentication and authorization.
12. Rate-limiting design.
13. Caching decision.
14. Local setup.
15. Test execution.
16. Running multiple instances.
17. Tradeoffs and limitations.
18. Future improvements.

If useful, create separate architecture diagrams and Architecture Decision Records (ADRs).

# 26. Implementation Plan

Implement the project incrementally.

## Phase 1 — Project Foundation
- Create Spring Boot project.
- Configure Maven.
- Configure PostgreSQL.
- Configure Docker Compose.
- Configure Flyway.
- Establish package structure.
- Configure environment-specific properties.

## Phase 2 — Domain and Database
- Implement entities.
- Implement repositories.
- Create migrations.
- Add constraints and indexes.
- Implement DTOs.
- Implement job lifecycle rules.

## Phase 3 — API and Security
- Implement Create Job.
- Implement Get Job.
- Implement Cancel Job.
- Implement login and JWT.
- Implement authorization.
- Implement ownership validation.
- Implement request validation.
- Implement global error handling.
- Implement idempotency.

## Phase 4 — Job Processing
- Implement bounded ThreadPoolExecutor.
- Implement Dispatcher.
- Implement atomic claiming.
- Implement Worker execution.
- Implement completion handling.
- Implement failure classification.

## Phase 5 — Distributed Processing
- Implement leases.
- Implement heartbeat.
- Implement fencing tokens.
- Implement Recovery Manager.
- Verify multiple instances.
- Implement graceful shutdown.

## Phase 6 — Reliability
- Implement retry policy.
- Implement cancellation handling.
- Handle race conditions.
- Verify recovery scenarios.
- Document distributed rate limiting.

## Phase 7 — Observability
- Implement structured logging.
- Implement metrics.
- Configure Actuator.
- Implement health checks.

## Phase 8 — Testing
- Unit tests.
- Integration tests.
- Concurrency tests.
- Failure-scenario tests.

## Phase 9 — Documentation and Delivery
- Complete README.
- Add architecture documentation.
- Add API documentation.
- Verify Docker setup.
- Run full test suite.
- Perform final code review.

Do not skip phases or claim completion without verification.

# 27. Definition of Done

The project is complete only when:

- Jobs can be created through REST API.
- Jobs are persisted in PostgreSQL.
- Jobs execute asynchronously.
- Job status can be retrieved.
- Jobs can be cancelled.
- Multiple jobs execute concurrently.
- Worker concurrency is bounded.
- Multiple instances work against the same database.
- Atomic claiming prevents concurrent valid ownership of the same job.
- Idempotency works under concurrent requests.
- Retry works according to the defined policy.
- Recovery detects abandoned jobs.
- Fencing tokens prevent stale database updates.
- Authentication and authorization are enforced.
- Users cannot access other users' jobs.
- Data survives application restarts.
- Automated tests pass.
- Docker Compose starts the local environment.
- Two application instances can be tested.
- Documentation accurately reflects the implementation.

# 28. Mandatory Development Rules for Claude Code

This project must be developed carefully and incrementally.

1. Read this entire document before making implementation decisions.
2. Treat it as the authoritative project specification.
3. Do not start coding until the initial architecture and implementation plan have been reviewed and approved.
4. Do not silently change architectural decisions.
5. Do not introduce unapproved technologies.
6. Do not remove requirements to simplify implementation.
7. Do not confuse documented production designs with required initial implementation.
8. Implement one phase at a time.
9. Before each phase, explain the planned changes.
10. After each phase, run the relevant tests.
11. Report exactly which tests passed and failed.
12. Do not proceed while important tests are failing.
13. Keep code clean, maintainable and well structured.
14. Follow SOLID principles where appropriate.
15. Avoid unnecessary abstractions.
16. Do not leave critical functionality as TODOs or placeholders.
17. Do not claim a feature works unless it has been implemented and tested.
18. Update documentation when implementation decisions or behavior change.
19. Explain important implementation decisions and tradeoffs.
20. Ask for approval when a decision affects architecture, scope, security or data integrity.

## Git Rules

- Use Git for version control.
- Create meaningful, focused commits.
- Keep changes organized by implementation phase.
- Never use force push.
- Never perform destructive Git operations without approval.
- Do not merge or push to a remote repository without approval.
- Never commit secrets, credentials, private keys or access tokens.
- Review the working tree before committing.

## Initial Task — Planning Only

**Do not write code yet.**

First:

1. Read and analyze this specification.
2. Summarize your understanding of the system.
3. Present the proposed architecture and component interactions.
4. Present the proposed package structure.
5. Present the database schema and important constraints.
6. Explain how atomic claiming, leases, heartbeats, fencing tokens and recovery will work together.
7. Present a detailed implementation plan divided into the phases defined above.
8. Identify technical risks, ambiguities and missing decisions.
9. Identify any contradictions or issues in the specification.
10. Separate mandatory implementation from documented production approaches.

Wait for explicit approval before starting Phase 1.

**Do not generate the application, modify files, or perform Git operations during this initial planning task.**