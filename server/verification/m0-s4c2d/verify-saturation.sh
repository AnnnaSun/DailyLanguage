#!/usr/bin/env bash

set -Eeuo pipefail

# -----------------------------------------------------------------------------
# 1. 路径与可覆盖的验证参数
# -----------------------------------------------------------------------------
# 默认 profile 是本 slice 的受限环境：1 CPU、512 MiB Container、12 个并发请求。
# VERIFY_* 只影响 verification Compose，不会修改 application 的生产默认配置。
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
REPOSITORY_DIR="$(cd -- "$SERVER_DIR/.." && pwd)"

VERIFY_BACKEND_PORT="${VERIFY_BACKEND_PORT:-18080}"
VERIFY_BACKEND_CPUS="${VERIFY_BACKEND_CPUS:-1.0}"
VERIFY_BACKEND_MEMORY="${VERIFY_BACKEND_MEMORY:-512m}"
VERIFY_JAVA_IMAGE="${VERIFY_JAVA_IMAGE:-eclipse-temurin:25-jre}"
VERIFY_CONCURRENCY="${VERIFY_CONCURRENCY:-12}"
VERIFY_RECOVERY_DELAY_SECONDS="${VERIFY_RECOVERY_DELAY_SECONDS:-2}"
VERIFY_KEEP_CONTAINERS="${VERIFY_KEEP_CONTAINERS:-false}"

# 每个未知邮箱消耗一次 IP Rate Limit；recovery 还需要一次机会。因此并发数必须低于
# 默认的 20 次/5 分钟 IP 限额，避免 429 掩盖 Argon2 concurrency gate 的 503。
if ! [[ "$VERIFY_CONCURRENCY" =~ ^([2-9]|1[0-9])$ ]]
then
  printf 'VERIFY_CONCURRENCY must be between 2 and 19; current value=%s\n' "$VERIFY_CONCURRENCY" >&2
  exit 1
fi

RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
VERIFY_EVIDENCE_DIR="$SCRIPT_DIR/evidence/$RUN_ID"
VERIFY_JAR_PATH="$SERVER_DIR/target/daily-language-server-0.0.1-SNAPSHOT.jar"
BASE_URL="http://localhost:$VERIFY_BACKEND_PORT"
COOKIE_JAR="$VERIFY_EVIDENCE_DIR/cookies.txt"
REQUEST_DIR="$VERIFY_EVIDENCE_DIR/requests"
COMPOSE_PROJECT_NAME="daily-language-m0-s4c2d"

mkdir -p "$REQUEST_DIR"

export VERIFY_BACKEND_PORT VERIFY_BACKEND_CPUS VERIFY_BACKEND_MEMORY
export VERIFY_JAVA_IMAGE VERIFY_EVIDENCE_DIR VERIFY_JAR_PATH

COMPOSE=(
  docker compose
  --project-name "$COMPOSE_PROJECT_NAME"
  --file "$SCRIPT_DIR/compose.yaml"
)

# -----------------------------------------------------------------------------
# 2. 生命周期与环境准备
# -----------------------------------------------------------------------------
# 无论成功还是失败，只清理这个固定 Compose project 的 Container 和临时 volume。
# 显式设置 VERIFY_KEEP_CONTAINERS=true 时保留现场，方便人工排查。
cleanup() {
  if [[ -n "${RESOURCE_SAMPLER_PID:-}" ]]
  then
    touch "$VERIFY_EVIDENCE_DIR/stop-resource-sampler"
    wait "$RESOURCE_SAMPLER_PID" 2>/dev/null || true
    RESOURCE_SAMPLER_PID=""
  fi
  if [[ "$VERIFY_KEEP_CONTAINERS" == "true" ]]
  then
    printf 'Verification containers retained for inspection.\n'
    return
  fi
  "${COMPOSE[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

# 最长等待 90 秒；启动失败时保存 Backend log，而不是继续发送无意义的请求。
wait_for_backend() {
  local attempt
  local status
  for attempt in $(seq 1 90)
  do
    status="$(curl -sS -o /dev/null -w '%{http_code}' "$BASE_URL/actuator/health" 2>/dev/null || true)"
    if [[ "$status" == "200" ]]
    then
      return
    fi
    sleep 1
  done

  "${COMPOSE[@]}" logs backend >"$VERIFY_EVIDENCE_DIR/backend-startup-failure.log" 2>&1 || true
  printf 'Backend did not become healthy; inspect %s\n' \
    "$VERIFY_EVIDENCE_DIR/backend-startup-failure.log" >&2
  exit 1
}

# 保存“测试发生在哪里、用什么运行”的证据。Docker inspect 记录实际 CPU/memory
# 限制，profile.properties 记录本次输入参数和 Argon2/JVM profile。
record_profile() {
  local backend_container_id
  backend_container_id="$("${COMPOSE[@]}" ps -q backend)"

  "${COMPOSE[@]}" config >"$VERIFY_EVIDENCE_DIR/compose-resolved.yaml"
  docker inspect "$backend_container_id" >"$VERIFY_EVIDENCE_DIR/backend-inspect-before.json"
  docker image inspect "$VERIFY_JAVA_IMAGE" >"$VERIFY_EVIDENCE_DIR/java-image-inspect.json"
  "${COMPOSE[@]}" exec -T backend env -u JAVA_TOOL_OPTIONS java -version \
    >"$VERIFY_EVIDENCE_DIR/jvm-version.txt" 2>&1

  {
    printf 'run_id=%s\n' "$RUN_ID"
    printf 'git_commit=%s\n' "$(git -C "$REPOSITORY_DIR" rev-parse HEAD)"
    printf 'git_worktree_changes=%s\n' "$(git -C "$REPOSITORY_DIR" status --porcelain | wc -l | tr -d ' ')"
    printf 'jar_sha256=%s\n' "$(shasum -a 256 "$VERIFY_JAR_PATH" | awk '{print $1}')"
    printf 'java_image=%s\n' "$VERIFY_JAVA_IMAGE"
    printf 'backend_cpus=%s\n' "$VERIFY_BACKEND_CPUS"
    printf 'backend_memory=%s\n' "$VERIFY_BACKEND_MEMORY"
    printf 'jvm_xms=128m\n'
    printf 'jvm_xmx=256m\n'
    printf 'argon2_encoding_version=argon2id-v1\n'
    printf 'argon2_memory_kib=19456\n'
    printf 'argon2_iterations=2\n'
    printf 'argon2_parallelism=1\n'
    printf 'password_hash_max_concurrent=1\n'
    printf 'http_concurrency=%s\n' "$VERIFY_CONCURRENCY"
  } >"$VERIFY_EVIDENCE_DIR/profile.properties"

  jar tf "$VERIFY_JAR_PATH" |
    grep 'BOOT-INF/lib/bcprov-jdk18on-' \
      >"$VERIFY_EVIDENCE_DIR/argon2-provider-artifact.txt"
}

# 在请求执行期间持续采集 Backend Container 的 CPU、memory 和 PID 数量。
start_resource_sampler() {
  local backend_container_id
  backend_container_id="$("${COMPOSE[@]}" ps -q backend)"

  printf 'timestamp\tcpu\tmemory_usage\tmemory_percent\tpids\n' \
    >"$VERIFY_EVIDENCE_DIR/container-stats.tsv"
  (
    while [[ ! -f "$VERIFY_EVIDENCE_DIR/stop-resource-sampler" ]]
    do
      printf '%s\t' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
        >>"$VERIFY_EVIDENCE_DIR/container-stats.tsv"
      docker stats --no-stream \
        --format '{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.PIDs}}' \
        "$backend_container_id" >>"$VERIFY_EVIDENCE_DIR/container-stats.tsv" 2>/dev/null || true
    done
  ) &
  RESOURCE_SAMPLER_PID=$!
}

stop_resource_sampler() {
  touch "$VERIFY_EVIDENCE_DIR/stop-resource-sampler"
  wait "$RESOURCE_SAMPLER_PID" || true
  RESOURCE_SAMPLER_PID=""
}

# Spring Security 的 SPA CSRF 是 cookie + header 双提交模式。先访问 /auth/me 获取
# XSRF-TOKEN，后续 login 请求同时携带 cookie 和 X-XSRF-TOKEN header。
bootstrap_csrf() {
  local me_status
  me_status="$(curl -sS -c "$COOKIE_JAR" \
    -o "$VERIFY_EVIDENCE_DIR/csrf-bootstrap-body.json" \
    -w '%{http_code}' "$BASE_URL/api/auth/me")"
  printf '%s\n' "$me_status" >"$VERIFY_EVIDENCE_DIR/csrf-bootstrap-status.txt"

  CSRF_TOKEN="$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$COOKIE_JAR" | tail -n 1)"
  if [[ -z "$CSRF_TOKEN" ]]
  then
    printf 'CSRF bootstrap did not produce XSRF-TOKEN.\n' >&2
    exit 1
  fi
}

# -----------------------------------------------------------------------------
# 3. 核心负载
# -----------------------------------------------------------------------------
# 每个请求使用不同的未知邮箱，确保它们都会走真实 unknown-account dummy Argon2
# verify，同时不会触发单邮箱 5 次/5 分钟的 Rate Limit。
#
# PASSWORD_HASH_MAX_CONCURRENT=1 时，预期一个请求取得 permit 并最终返回 401；其余
# 同时到达的请求无法取得 permit，应当 fail-fast 返回 503，而不是排队等待 hash。
run_saturation_burst() {
  local request_id
  local transport_failures=0
  local burst_id
  local -a request_pids=()

  burst_id="$(date +%s)"
  for ((request_id = 1; request_id <= VERIFY_CONCURRENCY; request_id++))
  do
    (
      curl -sS \
        -b "$COOKIE_JAR" \
        -H "X-XSRF-TOKEN: $CSRF_TOKEN" \
        -H 'Content-Type: application/x-www-form-urlencoded' \
        --data-urlencode "email=capacity-${burst_id}-${request_id}@example.com" \
        --data-urlencode 'password=Capacity-dummy-password-123!' \
        -D "$REQUEST_DIR/${request_id}.headers.txt" \
        -o "$REQUEST_DIR/${request_id}.body.json" \
        -w "request=${request_id} status=%{http_code} latency=%{time_total}\n" \
        "$BASE_URL/api/auth/login" \
        >"$REQUEST_DIR/${request_id}.metrics.txt" \
        2>"$REQUEST_DIR/${request_id}.stderr.txt"
    ) &
    request_pids+=("$!")
  done

  for request_pid in "${request_pids[@]}"
  do
    if ! wait "$request_pid"
    then
      transport_failures=$((transport_failures + 1))
    fi
  done
  printf '%s\n' "$transport_failures" >"$VERIFY_EVIDENCE_DIR/transport-failures.txt"
}

# saturation burst 全部结束后再发送一个请求。返回 401 表示新请求能够再次取得
# permit，从外部行为上验证没有 permit leak，并且服务在负载停止后恢复。
run_recovery_request() {
  sleep "$VERIFY_RECOVERY_DELAY_SECONDS"
  curl -sS \
    -b "$COOKIE_JAR" \
    -H "X-XSRF-TOKEN: $CSRF_TOKEN" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode "email=recovery-${RUN_ID}@example.com" \
    --data-urlencode 'password=Capacity-recovery-password-123!' \
    -D "$VERIFY_EVIDENCE_DIR/recovery.headers.txt" \
    -o "$VERIFY_EVIDENCE_DIR/recovery.body.json" \
    -w 'status=%{http_code} latency=%{time_total}\n' \
    "$BASE_URL/api/auth/login" \
    >"$VERIFY_EVIDENCE_DIR/recovery.metrics.txt"
}

# 这里只对 401 和 503 分组计算 nearest-rank percentile。样本量很小，latency 仅作为
# PROVISIONAL evidence，不用来推导 Hosted production capacity。
summarize_latency() {
  local status="$1"
  local -a values=()
  local count
  local p50_index
  local p95_index
  local p99_index

  mapfile -t values < <(
    awk -v expected_status="$status" '
      {
        current_status = ""
        latency = ""
        for (field = 1; field <= NF; field++) {
          if ($field ~ /^status=/) {
            split($field, status_parts, "=")
            current_status = status_parts[2]
          }
          if ($field ~ /^latency=/) {
            split($field, latency_parts, "=")
            latency = latency_parts[2]
          }
        }
        if (current_status == expected_status) {
          print latency
        }
      }
    ' "$REQUEST_DIR"/*.metrics.txt | sort -n
  )

  count="${#values[@]}"
  if ((count == 0))
  then
    printf 'status=%s count=0\n' "$status"
    return
  fi

  p50_index=$(((50 * count + 99) / 100 - 1))
  p95_index=$(((95 * count + 99) / 100 - 1))
  p99_index=$(((99 * count + 99) / 100 - 1))
  printf 'status=%s count=%s p50=%s p95=%s p99=%s\n' \
    "$status" "$count" \
    "${values[$p50_index]}" "${values[$p95_index]}" "${values[$p99_index]}"
}

# -----------------------------------------------------------------------------
# 4. PASS / FAIL 判定
# -----------------------------------------------------------------------------
# PASS 必须同时满足：burst 中至少一个 401、至少一个 503、没有其他 HTTP status 或
# transport failure；recovery 返回 401；Backend 没有 OOM kill 或 restart。
write_summary() {
  local backend_container_id
  local count_401
  local count_503
  local other_status_count
  local recovery_status
  local transport_failures
  local oom_killed
  local restart_count
  local result="PASS"

  backend_container_id="$("${COMPOSE[@]}" ps -q backend)"
  docker inspect "$backend_container_id" >"$VERIFY_EVIDENCE_DIR/backend-inspect-after.json"
  "${COMPOSE[@]}" logs backend >"$VERIFY_EVIDENCE_DIR/backend.log" 2>&1

  sed -n 's/.*status=\([0-9][0-9][0-9]\).*/\1/p' "$REQUEST_DIR"/*.metrics.txt |
    sort | uniq -c >"$VERIFY_EVIDENCE_DIR/status-summary.txt"
  summarize_latency 401 >"$VERIFY_EVIDENCE_DIR/latency-summary.txt"
  summarize_latency 503 >>"$VERIFY_EVIDENCE_DIR/latency-summary.txt"

  count_401="$(awk '/status=401/ { count++ } END { print count + 0 }' "$REQUEST_DIR"/*.metrics.txt)"
  count_503="$(awk '/status=503/ { count++ } END { print count + 0 }' "$REQUEST_DIR"/*.metrics.txt)"
  other_status_count="$(awk '
    /status=/ && $0 !~ /status=(401|503)/ { count++ }
    END { print count + 0 }
  ' "$REQUEST_DIR"/*.metrics.txt)"
  recovery_status="$(sed -n 's/.*status=\([0-9][0-9][0-9]\).*/\1/p' \
    "$VERIFY_EVIDENCE_DIR/recovery.metrics.txt")"
  transport_failures="$(command cat "$VERIFY_EVIDENCE_DIR/transport-failures.txt")"
  oom_killed="$(docker inspect --format '{{.State.OOMKilled}}' "$backend_container_id")"
  restart_count="$(docker inspect --format '{{.RestartCount}}' "$backend_container_id")"
  rm -- "$COOKIE_JAR"

  if ((count_401 < 1 || count_503 < 1 || other_status_count != 0 || transport_failures != 0))
  then
    result="FAIL"
  fi
  if [[ "$recovery_status" != "401" || "$oom_killed" != "false" || "$restart_count" != "0" ]]
  then
    result="FAIL"
  fi

  {
    printf '# M0-S4C2d-1 Provisional Saturation / Recovery Evidence\n\n'
    printf 'Result: **%s**\n\n' "$result"
    printf 'Classification: **PROVISIONAL — restricted local Container only**\n\n'
    printf -- '- Backend profile: `%s CPU / %s memory / JVM Xmx 256m`\n' \
      "$VERIFY_BACKEND_CPUS" "$VERIFY_BACKEND_MEMORY"
    printf -- '- Argon2: `argon2id-v1`, configured concurrency `1`\n'
    printf -- '- Concurrent unknown-account login requests: `%s`\n' "$VERIFY_CONCURRENCY"
    printf -- '- Saturation results: `401=%s`, `503=%s`, `other=%s`\n' \
      "$count_401" "$count_503" "$other_status_count"
    printf -- '- Transport failures: `%s`\n' "$transport_failures"
    printf -- '- Recovery request status after `%ss`: `%s`\n' \
      "$VERIFY_RECOVERY_DELAY_SECONDS" "$recovery_status"
    printf -- '- Backend OOMKilled: `%s`; restart count: `%s`\n\n' \
      "$oom_killed" "$restart_count"
    printf 'Latency evidence:\n\n```text\n'
    command cat "$VERIFY_EVIDENCE_DIR/latency-summary.txt"
    printf '```\n\n'
    printf 'Resource and GC evidence are stored in `container-stats.tsv` and `gc.log`.\n\n'
    printf 'This result does not confirm Hosted production capacity. M6 target-hardware testing remains required.\n'
  } >"$VERIFY_EVIDENCE_DIR/summary.md"

  printf 'Verification result: %s\n' "$result"
  printf 'Evidence: %s\n' "$VERIFY_EVIDENCE_DIR/summary.md"
  if [[ "$result" != "PASS" ]]
  then
    exit 1
  fi
}

# -----------------------------------------------------------------------------
# 5. 主流程——Review 时可以先只读这里，再按需跳转到上面的对应函数
# -----------------------------------------------------------------------------
printf 'Building executable server jar...\n'
"$SERVER_DIR/mvnw" -q -f "$SERVER_DIR/pom.xml" -DskipTests package

printf 'Starting isolated restricted-container profile...\n'
"${COMPOSE[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d
wait_for_backend
record_profile
bootstrap_csrf
start_resource_sampler
run_saturation_burst
run_recovery_request
stop_resource_sampler
write_summary
