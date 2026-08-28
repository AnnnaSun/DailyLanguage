#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=verification-support.sh
source "$SCRIPT_DIR/verification-support.sh"

VERIFY_CONCURRENCY="${VERIFY_CONCURRENCY:-12}"
if ! [[ "$VERIFY_CONCURRENCY" =~ ^([2-9]|1[0-9])$ ]]
then
  printf 'VERIFY_CONCURRENCY must be between 2 and 19; current value=%s\n' "$VERIFY_CONCURRENCY" >&2
  exit 1
fi

verification_initialize saturation
COOKIE_JAR="$VERIFY_EVIDENCE_DIR/cookies.txt"
verification_register_sensitive_file "$COOKIE_JAR"

# 不同未知邮箱避免单邮箱 Rate Limit；19 次上限为 recovery probe 保留第 20 次 IP 配额。
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

# 这是脚本主动发送的普通未知账号 login，用来验证负载停止后 permit 已释放。
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

write_saturation_summary() {
  local count_401
  local count_503
  local other_status_count
  local recovery_status
  local transport_failures
  local result="PASS"

  verification_capture_backend_after
  verification_summarize_latency saturation 401 "$REQUEST_DIR"/*.metrics.txt \
    >"$VERIFY_EVIDENCE_DIR/latency-summary.txt"
  verification_summarize_latency saturation 503 "$REQUEST_DIR"/*.metrics.txt \
    >>"$VERIFY_EVIDENCE_DIR/latency-summary.txt"

  count_401="$(verification_count_status 401 "$REQUEST_DIR"/*.metrics.txt)"
  count_503="$(verification_count_status 503 "$REQUEST_DIR"/*.metrics.txt)"
  other_status_count="$(verification_count_unexpected_status '^(401|503)$' "$REQUEST_DIR"/*.metrics.txt)"
  recovery_status="$(verification_status_from_metrics "$VERIFY_EVIDENCE_DIR/recovery.metrics.txt")"
  transport_failures="$(command cat "$VERIFY_EVIDENCE_DIR/transport-failures.txt")"
  rm -- "$COOKIE_JAR"

  if ((count_401 < 1 || count_503 < 1 || other_status_count != 0 || transport_failures != 0))
  then
    result="FAIL"
  fi
  if [[ "$recovery_status" != "401" || "$OOM_KILLED" != "false" || "$RESTART_COUNT" != "0" ]]
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
      "$OOM_KILLED" "$RESTART_COUNT"
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

# 主流程：环境 → CSRF → saturation → recovery → evidence。
verification_build_and_start
verification_bootstrap_csrf "$COOKIE_JAR"
verification_start_resource_sampler
run_saturation_burst
run_recovery_request
verification_stop_resource_sampler
write_saturation_summary
