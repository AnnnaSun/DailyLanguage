# M0-S4C2d Restricted-Container Verification

This verification-only harness runs the packaged backend with disposable PostgreSQL and Redis services.
The backend is restricted to `1 CPU / 512 MiB`, uses JVM `Xmx=256m`, and keeps the production
`PASSWORD_HASH_MAX_CONCURRENT=1` and default Rate Limit policies.

## Verification scenarios

Shared Container lifecycle, profile capture, CSRF bootstrap and evidence helpers live in
`verification-support.sh`; it is sourced by both scenario runners and is not executed directly.

### C2d-1 saturation / recovery

`verify-saturation.sh`:

1. build the executable Spring Boot jar;
2. start isolated PostgreSQL, Redis, and a restricted Java container;
3. generate an anonymous SPA CSRF cookie;
4. issue concurrent unknown-account login requests, which execute the real dummy Argon2 verification;
5. require both completed `401` verifications and saturated fail-fast `503` responses;
6. send a later unknown-account login and require `401` recovery;
7. record Container/JVM/image/profile, latency, Redis-backed HTTP behavior, GC log, OOM and restart evidence.

### C2d-2 mixed authentication workload

`verify-mixed-workload.sh`:

1. register two real local accounts in the disposable PostgreSQL database;
2. prove baseline correct login, wrong-password login, unknown-account login and authenticated `/me`;
3. concurrently issue 3 known-correct, 3 known-wrong and 6 unknown-account logins;
4. concurrently read `/me` four times with the previously authenticated Redis Session;
5. require Argon2 completion plus saturation `503`, then verify login and Session recovery;
6. record status and latency by workload category with the same restricted profile evidence.

The harness never changes production code, Argon2 parameters, configured concurrency, default Rate Limit
policy, or production dependencies. Its disposable Compose project is named
`daily-language-m0-s4c2d`; cleanup removes only that project's containers and ephemeral volumes.

## Run

Docker Desktop must be running. The Java runtime image is pulled on the first run if it is not present.

```bash
cd server/verification/m0-s4c2d
./verify-saturation.sh
./verify-mixed-workload.sh
```

Useful test-only overrides:

```bash
VERIFY_CONCURRENCY=16 ./verify-saturation.sh
VERIFY_BACKEND_CPUS=0.5 VERIFY_BACKEND_MEMORY=384m ./verify-saturation.sh
VERIFY_KEEP_CONTAINERS=true ./verify-saturation.sh
```

The mixed scenario intentionally fixes its hash workload at 12 requests. Including three baseline login
checks and one recovery probe, it consumes 16 of the default 20 attempts for the isolated client address.
Each known email is used four times, below the default five-attempt email limit.

Concurrency is intentionally limited to `2..19`: the default login IP policy allows 20 attempts per
five-minute window, and the recovery request consumes one additional attempt. A `429` therefore means
the supposedly isolated environment was not fresh or the policy/configuration drifted, and fails this slice.

## Evidence and pass criteria

Each run writes ignored evidence under `evidence/<UTC timestamp>/`:

- `summary.md`: provisional result and core status counts;
- `profile.properties`, resolved Compose and Docker inspect files;
- `requests/`: response headers, bodies, curl metrics and transport errors;
- `latency-summary.txt` and `container-stats.tsv`;
- `gc.log`, backend log and JVM/image metadata.

CSRF and Session Cookie Jars are deleted on both success and failure and are never retained as evidence.

The slice passes only when:

- at least one unknown-account request completes dummy verification and returns `401`;
- at least one concurrent request is rejected by exhausted Argon2 capacity with `503`;
- no saturation request returns another status, including `429`;
- the recovery request returns `401` after load stops;
- no curl transport failure, backend OOM kill, or container restart occurs.

The mixed scenario additionally requires:

- registration and all three baseline authentication paths return their expected statuses;
- concurrent known-correct logins return only `204/503`;
- concurrent known-wrong and unknown-account logins return only `401/503`;
- at least one hash operation completes and at least one request fails fast with `503`;
- all authenticated `/me` requests return `200` during the burst;
- recovery login returns `401` and the original Session still returns `/me` as `200` after the burst;
- every completed correct login supplies a `SESSION` response cookie.

Latency, CPU, memory and GC are captured as evidence rather than used as a production capacity threshold.
The result is always **PROVISIONAL** and cannot confirm Hosted capacity; target-hardware mixed load, soak,
and final capacity confirmation remain M6 work.

## Explicit non-goals

- Hosted capacity confirmation;
- soak or sustained-throughput testing;
- changing security parameters, Rate Limit policy, or production configuration;
- adding production observability or dependencies;
- updating planning/status documents before Review and Ownership.
