# Distributed Job Processor

A backend for **asynchronous, distributed job processing**. Clients submit jobs through a REST
API; any number of identical application instances process them in parallel and coordinate only
through PostgreSQL. It provides atomic job claiming (`SELECT … FOR UPDATE SKIP LOCKED`), leases
with heartbeats, fencing tokens, bounded worker pools, retries with backoff, crash recovery,
cooperative cancellation, idempotent job creation, JWT authentication with per-user ownership,
and metrics.

Java 21 · Spring Boot 3.5 · PostgreSQL 17 · Flyway · Spring Security (JWT) · Micrometer ·
JUnit 5 · Testcontainers · Docker Compose

| Deliverable | Where |
|---|---|
| Source code, build and run instructions | this README, [`src/`](src) |
| **Architecture diagram** (download and open in a browser; Mermaid overview renders on GitHub in `docs/ARCHITECTURE.md`) | **[`docs/architecture.html`](docs/architecture.html)** |
| Key decisions and trade-offs | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) |
| Automated tests | [Automated tests](#7-automated-tests), [`src/test/`](src/test) |
| Original specification (reference only) | [`docs/SPECIFICATION.md`](docs/SPECIFICATION.md) |

---

## 1. Architecture in brief

Every instance is the same Spring Boot JAR and runs both the REST API and the background
processing: a **dispatcher** that claims jobs only when a **bounded worker pool** has a free slot,
a **lease heartbeat**, a **cancellation watcher** and a **recovery manager**. Instances never call
each other; PostgreSQL is the single source of truth and the job queue. A job that loses its
worker (crash, pause, network partition) is recovered when its lease expires, and fencing tokens
stop a stale worker from overwriting newer state. Execution is **at-least-once**.

**Diagram:** a Mermaid overview renders directly on GitHub in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md#2-architecture-diagram). For the detailed interactive
diagram, download or clone the repository and open [`docs/architecture.html`](docs/architecture.html)
in any browser (double-click the file; no server or installation needed). GitHub shows that file
as source code, not as a diagram. Design decisions, trade-offs and limitations:
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## 2. Prerequisites

**Recommended: Docker only.** Java, Maven and PostgreSQL are **not** needed on your machine — the
application is compiled inside the Docker image.

| Tool | Notes |
|---|---|
| Git | to clone the repository |
| Docker Desktop (Windows, macOS) or Docker Engine (Linux) with **Compose v2** | `docker compose version` must work. On Windows, Docker Desktop must use Linux containers (the default). |
| Internet access on first build | downloads base images, Maven and dependencies (a few minutes once, cached afterwards) |
| macOS/Linux: `openssl` | only to generate secrets (preinstalled on macOS and most Linux distributions) |

**Only for running the tests or running without Docker:** JDK 21. Maven is still not needed —
use the Maven Wrapper (`./mvnw` or `mvnw.cmd`), which downloads it.

Free host ports by default: `5432` (PostgreSQL), `8081` and `8082` (the two instances). They can
be changed in `.env` (see [§4](#4-configuration-env)).

## 3. Quick start with Docker (recommended)

Run every block from the repository root. Pick **one** of the two columns: Windows PowerShell, or
macOS/Linux Bash (also works in Git Bash or WSL on Windows).

### 3.1 Clone and create `.env` with your own secrets

`.env` holds local secrets, is git-ignored and is read automatically by Docker Compose. The commands
copy [`.env.example`](.env.example) and fill the four required values with new random strings.

**Windows PowerShell**
```powershell
git clone https://github.com/RaniHassan/distributed-job-processor.git distributed-job-processor
cd distributed-job-processor
Copy-Item .env.example .env
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
function New-Secret { $b = New-Object byte[] 24; $rng.GetBytes($b); -join ($b | ForEach-Object { $_.ToString('x2') }) }
$lines = Get-Content .env | ForEach-Object {
  if ($_ -match '^(POSTGRES_PASSWORD|JWT_SECRET|SEED_USER_PASSWORD|SEED_ADMIN_PASSWORD)=$') { "$($Matches[1])=$(New-Secret)" } else { $_ }
}
[System.IO.File]::WriteAllText((Join-Path $PWD '.env'), ($lines -join "`n") + "`n")   # UTF-8, no BOM, LF
```

**macOS / Linux (Bash)**
```bash
git clone https://github.com/RaniHassan/distributed-job-processor.git distributed-job-processor
cd distributed-job-processor
cp .env.example .env
for var in POSTGRES_PASSWORD JWT_SECRET SEED_USER_PASSWORD SEED_ADMIN_PASSWORD; do
  sed -i.bak "s|^$var=\$|$var=$(openssl rand -hex 24)|" .env   # works with GNU and BSD sed
done
rm -f .env.bak
```

You can also open `.env` in any editor and type your own values (letters and digits, the JWT secret
at least 32 characters). Check the result without printing secrets: `docker compose config --quiet`
prints nothing when the configuration is valid.

### 3.2 Build and start

Same command on every operating system:
```
docker compose up --build -d
```
This builds the image (first time: a few minutes), starts PostgreSQL, waits until it is healthy,
then starts `app-1` and `app-2`. Flyway creates the schema automatically and the `dev` profile
creates two users from `.env`: `alice` (role `USER`) and `admin` (role `ADMIN`).

### 3.3 Verify that both instances are healthy

```
docker compose ps
```
All three services (`postgres`, `app-1`, `app-2`) should show `(healthy)` after about 30–60 seconds.

**Windows PowerShell**
```powershell
Invoke-RestMethod http://localhost:8081/actuator/health
Invoke-RestMethod http://localhost:8082/actuator/health
```
**macOS / Linux**
```bash
curl -s http://localhost:8081/actuator/health; echo
curl -s http://localhost:8082/actuator/health; echo
```
Both return `status: UP`. The readiness probe (`/actuator/health/readiness`) also checks the
database.

### 3.4 Log in, submit a job and retrieve it

The simulated job runs for one minute (`durationMs: 60000`; the allowed range is 1–5 minutes).

**Windows PowerShell**
```powershell
$cfg = @{}; Get-Content .env | Where-Object { $_ -match '^([A-Z_0-9]+)=(.*)$' } | ForEach-Object { $cfg[$Matches[1]] = $Matches[2] }
$base = 'http://localhost:8081/api/v1'
$login = Invoke-RestMethod -Method Post -Uri "$base/auth/login" -ContentType 'application/json' `
  -Body (@{ username = $cfg.SEED_USER_USERNAME; password = $cfg.SEED_USER_PASSWORD } | ConvertTo-Json)
$headers = @{ Authorization = "Bearer $($login.accessToken)" }

$job = Invoke-RestMethod -Method Post -Uri "$base/jobs" -ContentType 'application/json' `
  -Headers ($headers + @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() }) `
  -Body '{"payload":{"report":"daily","simulation":{"durationMs":60000}}}'
$job                                                          # id, status PENDING, createdAt

Invoke-RestMethod "$base/jobs/$($job.id)" -Headers $headers           # PENDING, RUNNING within ~1 s
Invoke-RestMethod "$base/jobs/$($job.id)/attempts" -Headers $headers  # one entry per attempt (empty until claimed)

do { Start-Sleep 5; $j = Invoke-RestMethod "$base/jobs/$($job.id)" -Headers $headers; $j.status } until ($j.status -in 'COMPLETED','FAILED','CANCELLED')
```

**macOS / Linux**
```bash
set -a; . ./.env; set +a
BASE=http://localhost:8081/api/v1
TOKEN=$(curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"$SEED_USER_USERNAME\",\"password\":\"$SEED_USER_PASSWORD\"}" \
  | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')

JOB=$(curl -s -X POST $BASE/jobs -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: demo-$(date +%s)" -H 'Content-Type: application/json' \
  -d '{"payload":{"report":"daily","simulation":{"durationMs":60000}}}' \
  | sed -E 's/.*"id":"([^"]+)".*/\1/')
echo "job $JOB"

curl -s $BASE/jobs/$JOB -H "Authorization: Bearer $TOKEN"; echo            # PENDING, RUNNING within ~1 s
curl -s $BASE/jobs/$JOB/attempts -H "Authorization: Bearer $TOKEN"; echo   # one entry per attempt (empty until claimed)

until curl -s $BASE/jobs/$JOB -H "Authorization: Bearer $TOKEN" | grep -qE '"status":"(COMPLETED|FAILED|CANCELLED)"'; do sleep 5; done
curl -s $BASE/jobs/$JOB -H "Authorization: Bearer $TOKEN"; echo             # status COMPLETED
```

Any instance serves any request: the same job is visible through `http://localhost:8082`. More
examples (idempotent replay, listing, cancellation, failure simulation) are in [§6](#6-api).

### 3.5 Inspect logs and metrics

```
docker compose logs -f app-1 app-2
```
Each instance logs which jobs it claims and runs, e.g. `Job started: jobId=… workerId=app-2-3f9c…`.
Submit several jobs at once and both `app-1` and `app-2` workers appear. Filter with
`docker compose logs app-1 app-2 | Select-String "Job started"` (PowerShell) or
`docker compose logs app-1 app-2 | grep "Job started"` (Bash).

Metrics require the `ADMIN` user (continues the shell session from §3.4):

**Windows PowerShell**
```powershell
$admin = Invoke-RestMethod -Method Post -Uri "$base/auth/login" -ContentType 'application/json' `
  -Body (@{ username = $cfg.SEED_ADMIN_USERNAME; password = $cfg.SEED_ADMIN_PASSWORD } | ConvertTo-Json)
(Invoke-WebRequest -UseBasicParsing http://localhost:8081/actuator/prometheus `
  -Headers @{ Authorization = "Bearer $($admin.accessToken)" }).Content -split "`n" | Select-String '^jobs_'
```
**macOS / Linux**
```bash
ADMIN_TOKEN=$(curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"$SEED_ADMIN_USERNAME\",\"password\":\"$SEED_ADMIN_PASSWORD\"}" \
  | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')
curl -s http://localhost:8081/actuator/prometheus -H "Authorization: Bearer $ADMIN_TOKEN" | grep '^jobs_'
```
Shows e.g. `jobs_submitted_total`, `jobs_completed_total`, `jobs_pending`, `jobs_running`, labelled
with `application` and `instance`. A `USER` token gets `403`.

### 3.6 Stop and clean up

```
docker compose stop          # stop containers; keep them and the database
docker compose down          # remove containers and network; keep the database volume
docker compose down -v       # also delete the database volume (all jobs and users)
```
The image `distributed-job-processor:local` can be removed with
`docker image rm distributed-job-processor:local`.

### 3.7 Running more instances and failure demos

- **Crash an instance mid-job:** `docker compose kill app-2`. Its jobs' leases expire (30 s) and
  `app-1` recovers them (attempt `ABANDONED`, job retried) and completes them. Restart with
  `docker compose start app-2`.
- **Graceful stop:** `docker compose stop app-1` stops claiming, lets running jobs finish for up to
  30 s, then leaves the rest to recovery.
- **More instances:** copy the `app-2` service in [`docker-compose.yml`](docker-compose.yml) with a
  new name, a unique `INSTANCE_ID` and a new host port.

## 4. Configuration (`.env`)

Variables read by Docker Compose from `.env`:

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `POSTGRES_PASSWORD` | **yes** | — | Password of the PostgreSQL user, used by the database and both instances. |
| `JWT_SECRET` | **yes** | — | HS256 key for signing access tokens, ≥ 32 characters, identical for all instances. |
| `SEED_USER_PASSWORD` | **yes** | — | Password of the seeded `USER` account (dev profile). |
| `SEED_ADMIN_PASSWORD` | **yes** | — | Password of the seeded `ADMIN` account (dev profile). |
| `SEED_USER_USERNAME` | no | `alice` | Username of the seeded `USER` account. |
| `SEED_ADMIN_USERNAME` | no | `admin` | Username of the seeded `ADMIN` account. |
| `POSTGRES_DB` | no | `jobprocessor` | Database name. |
| `POSTGRES_USER` | no | `jobprocessor` | Database user. |
| `POSTGRES_HOST_PORT` | no | `5432` | Host port of PostgreSQL (only needed to run the app without Docker). |
| `APP1_PORT` / `APP2_PORT` | no | `8081` / `8082` | Host ports of the two instances. |
| `SPRING_PROFILES_ACTIVE` | no | `dev` | `dev` seeds the users and allows failure injection for demos. |

Docker Compose fails with a clear message (e.g. `Set JWT_SECRET in .env`) if a required value is
missing. `INSTANCE_ID` is set per service in `docker-compose.yml` (`app-1`, `app-2`).

Application settings (environment variables or `application.yml`; defaults are used by Compose):

| Setting (env var) | Default | Purpose |
|---|---|---|
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | set by Compose | Database connection when not using the `dev` profile defaults. |
| `INSTANCE_ID` | `$HOSTNAME`, else `local` | Instance name; a random suffix per start forms the worker id. |
| `SERVER_PORT` | `8080` | HTTP port inside the container / of a local JAR. |
| `DB_SOCKET_TIMEOUT_SECONDS` / `DB_CONNECT_TIMEOUT_SECONDS` | `15` / `5` | JDBC timeouts for an unresponsive database. |
| `DB_POOL_SIZE` | `15` | Maximum database connections per instance. |
| `jobprocessor.worker.threads` | `5` | Worker threads per instance. |
| `jobprocessor.lease.duration` / `heartbeat-interval` | `30s` / `10s` | Job lease and its renewal. |
| `jobprocessor.retry.max-retries` / `backoff` | `3` / `5s,10s,20s` | Retry budget and delays (±20 % jitter). |
| `jobprocessor.simulation.min-duration` / `max-duration` | `1m` / `5m` | Simulated job duration range. |
| `jobprocessor.dispatcher.enabled` / `recovery.enabled` | `true` | Per-instance switches (e.g. API-only instances). |

All settings are in [`src/main/resources/application.yml`](src/main/resources/application.yml) and
are validated at startup; an invalid combination stops the application with an explanation.

## 5. Running without Docker for the application (optional)

Requires **JDK 21** and a PostgreSQL 17 database; the simplest database is the Compose one
(`docker compose up -d postgres`, after creating `.env` as in §3.1). Stop the Docker app
instances first if they are running (`docker compose stop app-1 app-2`).

**Windows PowerShell**
```powershell
.\mvnw.cmd clean package -DskipTests
docker compose up -d postgres
Get-Content .env | Where-Object { $_ -match '^([A-Z_0-9]+)=(.*)$' } | ForEach-Object { Set-Item "env:$($Matches[1])" $Matches[2] }
java -jar target\distributed-job-processor-0.1.0-SNAPSHOT.jar      # dev profile from .env; http://localhost:8080
```
**macOS / Linux**
```bash
./mvnw clean package -DskipTests
docker compose up -d postgres
set -a; . ./.env; set +a
java -jar target/distributed-job-processor-0.1.0-SNAPSHOT.jar      # dev profile from .env; http://localhost:8080
```
The `dev` profile connects to `localhost:${POSTGRES_HOST_PORT}`. To use another database, set
`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`. Stop the
application with `Ctrl+C`.

## 6. API

Base path `/api/v1`. Every endpoint except login and health requires `Authorization: Bearer <token>`.
Errors use RFC 9457 ProblemDetail with an `errorCode` and a `traceId` (also in the `X-Request-Id`
response header).

| Method | Path | Result |
|---|---|---|
| `POST` | `/auth/login` | `200` access token (15 min) · `401 INVALID_CREDENTIALS` |
| `POST` | `/jobs` (header `Idempotency-Key` required) | `202` + `Location` · replay: `202` + `Idempotency-Replayed: true` · `409 IDEMPOTENCY_KEY_CONFLICT` |
| `GET` | `/jobs/{id}` | `200` · `404 JOB_NOT_FOUND` (also for other users' jobs) |
| `GET` | `/jobs?page=0&size=20&sort=createdAt,desc` | `200` paged list of the caller's jobs (max 100 per page) |
| `GET` | `/jobs/{id}/attempts` | `200` attempt history |
| `DELETE` | `/jobs/{id}` | `200` cancelled (was `PENDING`) · `202` cancellation requested (was `RUNNING`) · `409 JOB_NOT_CANCELLABLE` |
| `GET` | `/actuator/health`, `/actuator/health/{liveness,readiness}` | public, no details |
| `GET` | `/actuator/metrics`, `/actuator/prometheus` | `ADMIN` only (`403` for `USER`) |

More examples (Bash; same variables as §3.4):
```bash
# Idempotent retry: same key + same body -> same job, header Idempotency-Replayed: true
curl -s -i -X POST $BASE/jobs -H "Authorization: Bearer $TOKEN" -H 'Idempotency-Key: report-1' \
  -H 'Content-Type: application/json' -d '{"payload":{"report":"daily"}}'
# Same key + different body -> 409 IDEMPOTENCY_KEY_CONFLICT
curl -s -X POST $BASE/jobs -H "Authorization: Bearer $TOKEN" -H 'Idempotency-Key: report-1' \
  -H 'Content-Type: application/json' -d '{"payload":{"report":"weekly"}}'

curl -s "$BASE/jobs?size=5" -H "Authorization: Bearer $TOKEN"               # newest first
curl -s -X DELETE $BASE/jobs/$JOB -H "Authorization: Bearer $TOKEN"         # 202 while RUNNING, then CANCELLED

# Failure simulation (dev/test profiles only): fails once, retried after ~5 s, then succeeds
curl -s -X POST $BASE/jobs -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: retry-$(date +%s)" \
  -H 'Content-Type: application/json' \
  -d '{"payload":{"simulation":{"durationMs":60000,"failureMode":"TRANSIENT_ONCE","failAfterMs":1000}}}'
```
`failureMode` can be `TRANSIENT_ONCE`, `TRANSIENT_ALWAYS`, `PERMANENT` or `UNEXPECTED_ALWAYS`
(optional `failAfterMs`). Outside the `dev`/`test` profiles failure injection is rejected with
`400 FAILURE_INJECTION_DISABLED`.

## 7. Automated tests

Requires **JDK 21** and a running **Docker** (integration tests start PostgreSQL 17 with
Testcontainers). Maven is downloaded by the wrapper.

| | Windows PowerShell | macOS / Linux |
|---|---|---|
| Unit tests only (no Docker) | `.\mvnw.cmd test` | `./mvnw test` |
| All tests (unit + integration) | `.\mvnw.cmd verify` | `./mvnw verify` |
| One integration class | `.\mvnw.cmd verify "-Dit.test=DatabaseOutageIT" "-Dtest=NoSuchTest" "-Dsurefire.failIfNoSpecifiedTests=false"` | `./mvnw verify -Dit.test=DatabaseOutageIT -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false` |

Unit tests (`*Test`) run with Surefire; integration tests (`*IT`) run with Failsafe against a real
PostgreSQL — no mocked database for concurrency or failure behaviour. Tests assert actual database
state (status, owner, fencing token, attempts), not only return values.

### Failure and concurrency scenarios

| Scenario | Test | What it proves |
|---|---|---|
| **Atomic claiming** | `JobClaimIT` | 10 concurrent claimers over 150 jobs: each job claimed exactly once; locked rows are skipped, not waited for; the claim is all-or-nothing (a failed attempt insert rolls back the job update). |
| **No claim without a free worker** | `DispatcherWorkerPoolStressTest` | Real dispatcher + pool, 20 000 instant jobs, concurrent `dispatch()` calls: never more claimed jobs than threads, no rejections, every permit returned. |
| **Stale workers / fencing** | `JobClaimIT`, `RecoveryIT`, `DistributedProcessingIT` | An old fencing token or another worker id cannot complete, fail, renew or release a job; a worker that loses ownership mid-run stops within one heartbeat and writes nothing. |
| **Crash recovery** | `DistributedProcessingIT`, `InterruptedShutdownScenarioIT` | A dead instance's jobs are recovered after lease expiry and completed by another instance (attempts `ABANDONED` → `SUCCEEDED`); a real interrupted shutdown is recovered end to end. |
| **Competing recovery managers** | `RecoveryIT` | 6 concurrent recovery managers over 120 expired jobs: each recovered exactly once; a lease renewed under a lock wins over recovery. |
| **Database outage and recovery** | `DatabaseOutageIT` | The PostgreSQL container is paused mid-job: API returns `503`, readiness DOWN / liveness UP, the worker self-fences at its lease deadline without writing; after the database returns, the job is recovered and completes — no restart. |
| **Cancellation races** | `CancellationRaceIT` | 40 concurrent cancellation-vs-completion races always end consistently (never `COMPLETED` after an accepted cancellation, never `CANCELLED` after a `409`); cancellation during a claim waits for the lock; a claim during a cancellation skips the job. |
| **Cooperative cancellation** | `DistributedProcessingIT` | Cancelling a running job via the API stops the worker, which records `CANCELLED` in under 1 s. |
| **Retries** | `JobProcessingIT`, `JobClaimIT` | Transient failure retried and then succeeds; transient/unexpected failures stop after 4 attempts with the backoff observed; permanent failures are never retried. |
| **Idempotency** | `IdempotencyIT` | 10 concurrent identical requests create exactly one job; conflicting bodies get `409`; field order does not matter; expired keys are reusable; keys are per user. |
| **Heartbeats and leases** | `DistributedProcessingIT`, `JobLeaseIT` | Jobs longer than the lease complete without false recovery; `CANCEL_REQUESTED` leases are not renewed. |
| **Security and ownership** | `AuthApiIT`, `JobApiIT`, `JobManagementServiceAuthorizationIT` | Expired/forged/foreign-issuer tokens → `401`; missing permission → `403`; other users' jobs → `404` (admins included); authorization holds outside HTTP. |
| **Schema integrity** | `SchemaConstraintsIT`, `SchemaIndexesIT` | Every constraint rejects inconsistent states; `EXPLAIN` shows the claim and recovery queries use their partial indexes. |

Unit tests cover the state machine (all 36 transitions), retry policy, request hashing,
validation, JWT handling, the worker pool, heartbeat and runner logic, and metrics.

### Verified results

| | |
|---|---|
| Command | `./mvnw clean verify` (Windows: `.\mvnw.cmd clean verify`) |
| Result | **445 tests, 0 failures, 0 errors, 0 skipped** — 249 unit + 196 integration, about 3–5 minutes |
| Environment | Windows 11, JDK 21.0.11, Docker 29.6.2 (Docker Desktop), PostgreSQL 17 via Testcontainers |
| Stability | Full suite green in consecutive runs; timing-sensitive classes (`DatabaseOutageIT`, stress, distributed and race tests) repeated 3–5 times without failure |

## 8. Troubleshooting

| Symptom | Fix |
|---|---|
| `Set … in .env` when starting | A required variable in `.env` is empty — see §3.1 / §4. |
| `port is already allocated` | Change `APP1_PORT`, `APP2_PORT` or `POSTGRES_HOST_PORT` in `.env`. |
| A service stays `(health: starting)` or restarts | `docker compose logs app-1` shows the reason (e.g. invalid configuration). |
| `password authentication failed` after changing `POSTGRES_PASSWORD` | The database volume keeps the old password: `docker compose down -v` (deletes data), then start again. |
| PowerShell: `curl` behaves differently | In Windows PowerShell 5.1 `curl` is an alias; use the `Invoke-RestMethod` commands above or `curl.exe`. |
| Integration tests cannot find Docker | Start Docker Desktop / the Docker daemon before `mvnw verify`. |

## 9. Project structure

```
src/main/java/com/example/jobprocessor/
├── common/        configuration, error handling (ProblemDetail), trace id, request size limit
├── security/      JWT issuing/validation, permissions, current user
├── user/          users, login endpoint, dev/test seeding
├── job/           API, JobManagementService, JobLifecycleService, idempotency, state machine, retry policy
├── worker/        JobDispatcher, WorkerPool, JobRunner/executor, ActiveExecutions, LeaseHeartbeat, CancellationWatcher
├── recovery/      RecoveryManager and RecoveryScheduler
└── monitoring/    metrics
src/main/resources/db/migration/   Flyway schema (V1)
docs/              architecture.html (diagram), ARCHITECTURE.md, SPECIFICATION.md, design/rate-limiting.md
```

## 10. Known limitations and key decisions

- **PostgreSQL as the queue** (no broker): one transactional store for state and work; polling adds
  up to 1 s start latency. **At-least-once** execution: a job's work can run twice after a crash, so
  real jobs with external side effects must be idempotent downstream.
- A crashed instance's jobs are recovered after the 30 s lease expires.
- Rate limiting is a documented design only ([`docs/design/rate-limiting.md`](docs/design/rate-limiting.md));
  the API never returns `429`. No caching, no message broker, no OpenAPI/Swagger UI.
- Access tokens cannot be revoked before they expire (15 minutes).
- Details, trade-offs and the approved deviations from the specification:
  [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
