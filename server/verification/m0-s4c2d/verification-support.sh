#!/usr/bin/env bash

# C2d runners 共用的 Container 生命周期与 evidence helper。
# 本文件只被 verify-*.sh source，不单独执行。

VERIFICATION_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(cd -- "$VERIFICATION_DIR/../.." && pwd)"
REPOSITORY_DIR="$(cd -- "$SERVER_DIR/.." && pwd)"

VERIFY_BACKEND_PORT="${VERIFY_BACKEND_PORT:-18080}"
VERIFY_BACKEND_CPUS="${VERIFY_BACKEND_CPUS:-1.0}"
VERIFY_BACKEND_MEMORY="${VERIFY_BACKEND_MEMORY:-512m}"
VERIFY_JAVA_IMAGE="${VERIFY_JAVA_IMAGE:-eclipse-temurin:25-jre}"
VERIFY_RECOVERY_DELAY_SECONDS="${VERIFY_RECOVERY_DELAY_SECONDS:-2}"
VERIFY_KEEP_CONTAINERS="${VERIFY_KEEP_CONTAINERS:-false}"

VERIFY_JAR_PATH="$SERVER_DIR/target/daily-language-server-0.0.1-SNAPSHOT.jar"
BASE_URL="http://localhost:$VERIFY_BACKEND_PORT"
COMPOSE_PROJECT_NAME="daily-language-m0-s4c2d"

declare -a COMPOSE=()
declare -a VERIFICATION_SENSITIVE_FILES=()

verification_initialize() {
  VERIFY_SCENARIO="$1"
  RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
  VERIFY_EVIDENCE_DIR="$VERIFICATION_DIR/evidence/$RUN_ID"
  REQUEST_DIR="$VERIFY_EVIDENCE_DIR/requests"
  mkdir -p "$REQUEST_DIR"

  export VERIFY_BACKEND_PORT VERIFY_BACKEND_CPUS VERIFY_BACKEND_MEMORY
  export VERIFY_JAVA_IMAGE VERIFY_EVIDENCE_DIR VERIFY_JAR_PATH
  COMPOSE=(
    docker compose
    --project-name "$COMPOSE_PROJECT_NAME"
    --file "$VERIFICATION_DIR/compose.yaml"
  )
  trap verification_cleanup EXIT
}

# 无论成功还是失败，只清理这个固定 Compose project 的 Container 和临时 volume。
verification_cleanup() {
  local sensitive_file
  if [[ -n "${RESOURCE_SAMPLER_PID:-}" ]]
  then
    touch "$VERIFY_EVIDENCE_DIR/stop-resource-sampler"
    wait "$RESOURCE_SAMPLER_PID" 2>/dev/null || true
    RESOURCE_SAMPLER_PID=""
  fi
  for sensitive_file in "${VERIFICATION_SENSITIVE_FILES[@]}"
  do
    if [[ -f "$sensitive_file" ]]
    then
      rm -- "$sensitive_file"
    fi
  done
  if [[ "$VERIFY_KEEP_CONTAINERS" == "true" ]]
  then
    printf 'Verification containers retained for inspection.\n'
    return
  fi
  "${COMPOSE[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
}

# Cookie Jar 包含 CSRF / Session credential；成功和失败路径都不把它保留为 evidence。
verification_register_sensitive_file() {
  VERIFICATION_SENSITIVE_FILES+=("$1")
}

verification_wait_for_backend() {
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

# 同时记录输入 profile 和 Docker 实际应用的 CPU/memory 限制，避免只相信 Compose 文本。
verification_record_profile() {
  local backend_container_id
  backend_container_id="$("${COMPOSE[@]}" ps -q backend)"

  "${COMPOSE[@]}" config >"$VERIFY_EVIDENCE_DIR/compose-resolved.yaml"
  docker inspect "$backend_container_id" >"$VERIFY_EVIDENCE_DIR/backend-inspect-before.json"
  docker image inspect "$VERIFY_JAVA_IMAGE" >"$VERIFY_EVIDENCE_DIR/java-image-inspect.json"
  "${COMPOSE[@]}" exec -T backend env -u JAVA_TOOL_OPTIONS java -version \
    >"$VERIFY_EVIDENCE_DIR/jvm-version.txt" 2>&1

  {
    printf 'scenario=%s\n' "$VERIFY_SCENARIO"
    printf 'run_id=%s\n' "$RUN_ID"
    printf 'git_commit=%s\n' "$(git -C "$REPOSITORY_DIR" rev-parse HEAD)"
    printf 'git_worktree_changes=%s\n' "$(git -C "$REPOSITORY_DIR" status --porcelain | wc -l | tr -d ' ')"
    printf 'jar_sha256=%s\n' "$(shasum -a 256 "$VERIFY_JAR_PATH" | awk '{print $1}')"
    printf 'java_image=%s\n' "$VERIFY_JAVA_IMAGE"
    printf 'backend_cpus_requested=%s\n' "$VERIFY_BACKEND_CPUS"
    printf 'backend_memory_requested=%s\n' "$VERIFY_BACKEND_MEMORY"
    printf 'backend_nano_cpus_actual=%s\n' \
      "$(docker inspect --format '{{.HostConfig.NanoCpus}}' "$backend_container_id")"
    printf 'backend_memory_bytes_actual=%s\n' \
      "$(docker inspect --format '{{.HostConfig.Memory}}' "$backend_container_id")"
    printf 'jvm_xms=128m\n'
    printf 'jvm_xmx=256m\n'
    printf 'argon2_encoding_version=argon2id-v1\n'
    printf 'argon2_memory_kib=19456\n'
    printf 'argon2_iterations=2\n'
    printf 'argon2_parallelism=1\n'
    printf 'password_hash_max_concurrent=1\n'
  } >"$VERIFY_EVIDENCE_DIR/profile.properties"

  jar tf "$VERIFY_JAR_PATH" |
    grep 'BOOT-INF/lib/bcprov-jdk18on-' \
      >"$VERIFY_EVIDENCE_DIR/argon2-provider-artifact.txt"
}

verification_build_and_start() {
  printf 'Building executable server jar...\n'
  "$SERVER_DIR/mvnw" -q -f "$SERVER_DIR/pom.xml" -DskipTests package

  printf 'Starting isolated restricted-container profile...\n'
  "${COMPOSE[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  "${COMPOSE[@]}" up -d
  verification_wait_for_backend
  verification_record_profile
}

verification_start_resource_sampler() {
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

verification_stop_resource_sampler() {
  touch "$VERIFY_EVIDENCE_DIR/stop-resource-sampler"
  wait "$RESOURCE_SAMPLER_PID" || true
  RESOURCE_SAMPLER_PID=""
}

# SPA CSRF 使用 cookie + header 双提交。Public registration profile 下 /auth/me 应为 401，
# 但 response 仍须发放 XSRF-TOKEN。
verification_bootstrap_csrf() {
  local cookie_jar="$1"
  local me_status
  me_status="$(curl -sS -c "$cookie_jar" \
    -o "$VERIFY_EVIDENCE_DIR/csrf-bootstrap-body.json" \
    -w '%{http_code}' "$BASE_URL/api/auth/me")"
  printf '%s\n' "$me_status" >"$VERIFY_EVIDENCE_DIR/csrf-bootstrap-status.txt"
  if [[ "$me_status" != "401" ]]
  then
    printf 'Expected CSRF bootstrap /auth/me status 401; actual=%s\n' "$me_status" >&2
    exit 1
  fi

  CSRF_TOKEN="$(verification_cookie_value "$cookie_jar" XSRF-TOKEN)"
  if [[ -z "$CSRF_TOKEN" ]]
  then
    printf 'CSRF bootstrap did not produce XSRF-TOKEN.\n' >&2
    exit 1
  fi
}

verification_cookie_value() {
  local cookie_jar="$1"
  local cookie_name="$2"
  awk -v expected_name="$cookie_name" '$6 == expected_name { print $7 }' "$cookie_jar" | tail -n 1
}

verification_status_from_metrics() {
  sed -n 's/.*status=\([0-9][0-9][0-9]\).*/\1/p' "$1"
}

verification_count_status() {
  local expected_status="$1"
  shift
  awk -v expected_status="$expected_status" '
    {
      for (field = 1; field <= NF; field++) {
        if ($field == "status=" expected_status) {
          count++
        }
      }
    }
    END { print count + 0 }
  ' "$@"
}

verification_count_unexpected_status() {
  local allowed_status_pattern="$1"
  shift
  awk -v allowed_status_pattern="$allowed_status_pattern" '
    {
      for (field = 1; field <= NF; field++) {
        if ($field ~ /^status=/) {
          split($field, status_parts, "=")
          if (status_parts[2] !~ allowed_status_pattern) {
            count++
          }
        }
      }
    }
    END { print count + 0 }
  ' "$@"
}

verification_summarize_latency() {
  local label="$1"
  local expected_status="$2"
  shift 2
  local -a values=()
  local count
  local p50_index
  local p95_index
  local p99_index

  mapfile -t values < <(
    awk -v expected_status="$expected_status" '
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
    ' "$@" | sort -n
  )

  count="${#values[@]}"
  if ((count == 0))
  then
    printf 'category=%s status=%s count=0\n' "$label" "$expected_status"
    return
  fi

  p50_index=$(((50 * count + 99) / 100 - 1))
  p95_index=$(((95 * count + 99) / 100 - 1))
  p99_index=$(((99 * count + 99) / 100 - 1))
  printf 'category=%s status=%s count=%s p50=%s p95=%s p99=%s\n' \
    "$label" "$expected_status" "$count" \
    "${values[$p50_index]}" "${values[$p95_index]}" "${values[$p99_index]}"
}

verification_capture_backend_after() {
  local backend_container_id
  backend_container_id="$("${COMPOSE[@]}" ps -q backend)"
  docker inspect "$backend_container_id" >"$VERIFY_EVIDENCE_DIR/backend-inspect-after.json"
  "${COMPOSE[@]}" logs backend >"$VERIFY_EVIDENCE_DIR/backend.log" 2>&1
  OOM_KILLED="$(docker inspect --format '{{.State.OOMKilled}}' "$backend_container_id")"
  RESTART_COUNT="$(docker inspect --format '{{.RestartCount}}' "$backend_container_id")"
}
