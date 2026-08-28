# M0-S4C2d Provisional Restricted-Container Results

> Verification date: 2026-08-28  
> Classification: **PASS / PROVISIONAL**  
> Production capacity conclusion: **NOT CONFIRMED — M6 target-hardware verification required**

## Verified artifact and profile

- Application Git baseline: `7250139c341c7207726abbd910142e0bd39713e2`;
- verification harness changes present during the run were later committed in `1101b77`;
- packaged jar SHA-256: `af3d45c1c5dd47d4e8d255d5843c2f433fe79bc63f0455b42424724c05a8eae8`;
- Java image: `eclipse-temurin:25-jre`;
- JVM: Eclipse Temurin `25.0.4+7-LTS`, `Xms=128m`, `Xmx=256m`;
- Container limit: `1.0 CPU`, `512 MiB` memory;
- Argon2 provider artifact: `bcprov-jdk18on-1.85.2.jar`;
- encoding version: `argon2id-v1`;
- Argon2 parameters: memory `19456 KiB`, iterations `2`, parallelism `1`;
- configured password-hash concurrency: `1`.

Raw request, Container inspect, resource sample, GC and backend evidence remains intentionally ignored under
`evidence/<UTC timestamp>/`; it can contain large machine-specific output and is not the durable project record.

## Saturation and recovery

Evidence run: `20260828T140543Z`.

- 12 concurrent unknown-account login requests exercised the real dummy Argon2 verification;
- status results: `401=1`, fail-fast `503=11`, other statuses `0`;
- transport failures: `0`;
- recovery request after load stopped: `401` after 2 seconds;
- backend `OOMKilled=false`, restart count `0`;
- completed `401` latency: p50/p95/p99 `0.252853s`;
- fail-fast `503` latency: p50 `0.133939s`, p95/p99 `0.154015s`.

Observed resource samples were approximately `328.2 MiB / 512 MiB` and `0.26–0.38%` CPU. Sampling was
sparse and occurred after the shortest burst work, so these values are observations, not reliable peak CPU or
capacity thresholds. GC completed without OOM; the longest observed full GC pause was `41.855ms`.

## Mixed authentication workload and recovery

Evidence run: `20260828T140823Z`.

The baseline first proved correct-password login `204`, wrong-password login `401`, unknown-account login
`401`, and authenticated `/me` `200`. The concurrent workload then issued 3 known-correct, 3 known-wrong,
6 unknown-account logins and 4 authenticated `/me` reads.

- known correct: `204=0`, `503=3`;
- known wrong: `401=1`, `503=2`;
- unknown account: `401=0`, `503=6`;
- authenticated `/me`: `200=4/4` during the burst;
- unexpected statuses: `0`; transport failures: `0`;
- recovery login after load stopped: `401`;
- original authenticated Session after the burst: `/me=200`;
- backend `OOMKilled=false`, restart count `0`.

Representative fail-fast `503` p95 values were `0.037272s` for known-correct, `0.037574s` for known-wrong,
and `0.059684s` for unknown-account requests. The completed known-wrong `401` took `0.077976s`.

Observed resource samples were approximately `371.6–371.9 MiB / 512 MiB` and `10.66–13.12%` CPU. As
above, the short sampling window does not establish peak utilization. GC completed without OOM; the longest
observed full GC pause was `112.357ms`.

## Pass conclusion

The restricted local profile demonstrated:

- configured Argon2 concurrency remained a hard capacity boundary;
- saturation failed fast with `503` instead of creating an unbounded application queue;
- completed operations released capacity, allowing post-load recovery; committed focused gate tests separately
  cover permit release when an operation returns `false` or throws;
- Rate Limit did not mask the intended Argon2 saturation evidence;
- authenticated Redis Session reads remained available during and after the mixed burst;
- no transport failure, OOM kill or backend restart occurred.

This evidence does **not** determine Hosted production concurrency, throughput, p95/p99 targets or safe
resource sizing. M6 must repeat benchmark, open-model login load, mixed workload, soak and recovery testing on
the target or equivalent Hosted hardware before capacity can be marked `CONFIRMED`.
