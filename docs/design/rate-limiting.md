# Distributed Rate Limiting — Production Design

| | |
|---|---|
| **Status** | Documented design only. **Not implemented** in the current application. |
| **Spec** | README §3.2 (Must Document), FR-15, §18 |
| **Current behavior** | No endpoint is rate-limited. The API never returns `429 Too Many Requests` today. |
| **Infrastructure** | Would require Redis. Redis is **not** part of the current deployment and must not be added without approval. |

## 1. Problem

Without limits, one client can:

- flood `POST /api/v1/jobs` and fill the shared PostgreSQL queue, delaying every other user's jobs;
- brute-force `POST /api/v1/auth/login` (BCrypt makes each guess slow, but not impossible);
- poll `GET /api/v1/jobs/{id}` aggressively and load the database.

The application runs as **several independent instances** behind a load balancer. A limit must
hold for the system as a whole, whichever instance a request reaches.

## 2. Why per-instance limiting is not correct

An in-memory counter (for example Bucket4j in local mode, or a `ConcurrentHashMap` of counters)
is maintained separately by each JVM:

- With *N* instances and round-robin load balancing, a user effectively gets *N* times the limit.
- Scaling out silently raises every user's limit; an instance restart resets its counters.
- Sticky sessions do not help: the API is stateless and tokens are accepted by every instance.

The counter therefore has to live in **one shared store** that every instance consults atomically.

## 3. Proposed design: Token Bucket in Redis

### 3.1 Algorithm

Each limited key owns a **token bucket**:

- `capacity` — maximum burst size.
- `refill_rate` — tokens added per second, up to `capacity`.
- A request consumes `cost` tokens (normally 1). If not enough tokens are available, it is rejected.

Token bucket allows short bursts while enforcing an average rate, which suits clients that submit
a batch of jobs and then go quiet.

### 3.2 Atomic consumption

Check-and-decrement must be atomic across all instances. It runs as a **single Lua script** inside
Redis (scripts execute atomically), using Redis' own clock so application clocks do not matter:

```lua
-- KEYS[1] = bucket key; ARGV = capacity, refill_per_sec, cost
local capacity = tonumber(ARGV[1])
local rate     = tonumber(ARGV[2])
local cost     = tonumber(ARGV[3])
local t        = redis.call('TIME')
local now      = tonumber(t[1]) + tonumber(t[2]) / 1e6

local state  = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1]) or capacity
local ts     = tonumber(state[2]) or now

tokens = math.min(capacity, tokens + (now - ts) * rate)
local allowed = tokens >= cost
if allowed then tokens = tokens - cost end

redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
-- Idle buckets expire once they would be full again.
redis.call('EXPIRE', KEYS[1], math.ceil(capacity / rate) + 1)

local retry_after = allowed and 0 or math.ceil((cost - tokens) / rate)
return { allowed and 1 or 0, math.floor(tokens), retry_after }
```

One round trip per request; no locks, no read-modify-write race between instances.

### 3.3 Keys and limits

| Endpoint | Key | Example limit | Rationale |
|---|---|---|---|
| `POST /api/v1/jobs` | `rl:jobs:create:{userId}` | capacity 20, refill 1/s | Protects the shared queue per user. |
| `GET /api/v1/jobs/**` | `rl:jobs:read:{userId}` | capacity 100, refill 20/s | Allows UIs to poll, stops scrapers. |
| `DELETE /api/v1/jobs/{id}` | `rl:jobs:cancel:{userId}` | capacity 20, refill 2/s | Cheap, but writes to the database. |
| `POST /api/v1/auth/login` | `rl:login:{clientIp}` and `rl:login:{username}` | capacity 10, refill 1 per 30 s | Unauthenticated: limit by source and by target account. |

- **Authenticated requests are keyed by the user id from the validated JWT**, never by a value the
  client controls, so a user cannot evade the limit with new tokens or other instances.
- Login is limited both per client IP and per username, so neither one attacker spreading across
  many accounts nor many sources attacking one account gets unlimited attempts.
- Limits are configuration (`jobprocessor.rate-limit.*`), validated at startup like the other
  operational settings.

### 3.4 Placement in the request flow

A servlet filter (or `HandlerInterceptor`) running **after authentication** for protected
endpoints, so the user id is known, and before authentication for the login endpoint:

```
TraceIdFilter → RequestSizeLimitFilter → Spring Security (JWT) → RateLimitFilter → controller
```

It runs before any database work, so rejected requests cost one Redis call.

### 3.5 Response

On rejection:

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 3
Content-Type: application/problem+json

{
  "title": "Too Many Requests",
  "status": 429,
  "detail": "Rate limit exceeded, retry later",
  "errorCode": "RATE_LIMITED",
  "traceId": "…"
}
```

The body uses the existing ProblemDetail format. Successful responses may also carry the remaining
budget (`RateLimit-Remaining`, from the IETF RateLimit header fields draft) so well-behaved
clients can slow down before being rejected.

Rate limiting does not interfere with idempotency: a rejected `POST /jobs` creates nothing and
binds no idempotency key, so the client can retry the same request with the same key later.

### 3.6 When Redis is unavailable

Rate limiting protects capacity; it is not a correctness mechanism (PostgreSQL remains the source
of truth for jobs). The recommended policy:

- **Fail open** for job endpoints: if Redis does not answer within a short timeout (~50 ms), let
  the request through, log it and increment a `rate_limit_unavailable_total` metric. A Redis outage
  should not take the API down.
- Optionally fall back to a **local, deliberately conservative per-instance bucket** (the global
  limit divided by the expected instance count) so protection degrades instead of disappearing.
- **Fail closed for login** if brute-force protection is a hard security requirement; this is a
  product decision.
- A circuit breaker avoids paying the timeout on every request during an outage.

## 4. Alternatives considered

| Option | Why not (for this system) |
|---|---|
| Per-instance in-memory buckets | Incorrect with multiple instances (§2). |
| PostgreSQL row per bucket (`UPDATE … RETURNING`) | Correct and needs no new infrastructure, but adds a write and a row lock to the primary database on every request — the component this limit is meant to protect. Acceptable only at low traffic. |
| API gateway / ingress limits (e.g. NGINX, Envoy, a cloud API gateway) | A good complement for coarse per-IP limits, but usually cannot key on the user id inside our JWT without extra configuration, and splits the policy away from the application. |
| Fixed-window counters (`INCR` + `EXPIRE`) | Simpler, but allows twice the limit around window boundaries. |

## 5. Verification plan (when implemented)

- Unit tests of the bucket arithmetic (refill, burst, `Retry-After`).
- Integration tests with Testcontainers Redis: two application contexts sharing one Redis must
  together admit exactly `capacity` requests in a burst.
- Concurrency test: parallel requests for one user never exceed the budget.
- Failure test: stopping Redis makes job endpoints fail open (and login fail closed, if chosen).
- Metrics: `rate_limit_rejected_total{endpoint}`, `rate_limit_unavailable_total`.
