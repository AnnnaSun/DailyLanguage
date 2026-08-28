#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=verification-support.sh
source "$SCRIPT_DIR/verification-support.sh"

VERIFY_CONCURRENCY="${VERIFY_CONCURRENCY:-12}"
if [[ "$VERIFY_CONCURRENCY" != "12" ]]
then
  printf 'Mixed workload currently requires VERIFY_CONCURRENCY=12; current value=%s\n' \
    "$VERIFY_CONCURRENCY" >&2
  exit 1
fi

verification_initialize mixed-workload
ANONYMOUS_COOKIE_JAR="$VERIFY_EVIDENCE_DIR/anonymous-cookies.txt"
SESSION_COOKIE_JAR="$VERIFY_EVIDENCE_DIR/session-cookies.txt"
verification_register_sensitive_file "$ANONYMOUS_COOKIE_JAR"
verification_register_sensitive_file "$SESSION_COOKIE_JAR"
KNOWN_CORRECT_EMAIL="mixed-correct-${RUN_ID}@example.com"
KNOWN_WRONG_EMAIL="mixed-wrong-${RUN_ID}@example.com"
UNKNOWN_BASELINE_EMAIL="mixed-unknown-${RUN_ID}@example.com"
CORRECT_PASSWORD="C2d-mixed-safe-${RUN_ID}-Ab9!"
WRONG_PASSWORD="C2d-mixed-wrong-${RUN_ID}-Z8!"

post_form() {
  local output_prefix="$1"
  local path="$2"
  local email="$3"
  local password="$4"
  local cookie_jar="$5"
  local response_cookie_jar="${6:-}"
  local -a cookie_output=()
  if [[ -n "$response_cookie_jar" ]]
  then
    cookie_output=(-c "$response_cookie_jar")
  fi

  curl -sS \
    -b "$cookie_jar" \
    "${cookie_output[@]}" \
    -H "X-XSRF-TOKEN: $CSRF_TOKEN" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode "email=$email" \
    --data-urlencode "password=$password" \
    -D "${output_prefix}.headers.txt" \
    -o "${output_prefix}.body.json" \
    -w 'status=%{http_code} latency=%{time_total}\n' \
    "$BASE_URL$path" \
    >"${output_prefix}.metrics.txt" \
    2>"${output_prefix}.stderr.txt"
}

require_status() {
  local label="$1"
  local expected_status="$2"
  local metrics_file="$3"
  local actual_status
  actual_status="$(verification_status_from_metrics "$metrics_file")"
  if [[ "$actual_status" != "$expected_status" ]]
  then
    printf '%s expected status %s; actual=%s\n' "$label" "$expected_status" "$actual_status" >&2
    exit 1
  fi
}

# Setup 在 resource sampling 前完成，只负责建立真实 known account 与 authenticated Session。
prepare_mixed_workload() {
  post_form "$VERIFY_EVIDENCE_DIR/register-correct" \
    /api/auth/registration "$KNOWN_CORRECT_EMAIL" "$CORRECT_PASSWORD" "$ANONYMOUS_COOKIE_JAR"
  require_status 'Correct-login account registration' 204 \
    "$VERIFY_EVIDENCE_DIR/register-correct.metrics.txt"

  post_form "$VERIFY_EVIDENCE_DIR/register-wrong" \
    /api/auth/registration "$KNOWN_WRONG_EMAIL" "$CORRECT_PASSWORD" "$ANONYMOUS_COOKIE_JAR"
  require_status 'Wrong-password account registration' 204 \
    "$VERIFY_EVIDENCE_DIR/register-wrong.metrics.txt"

  post_form "$VERIFY_EVIDENCE_DIR/baseline-correct" \
    /api/auth/login "$KNOWN_CORRECT_EMAIL" "$CORRECT_PASSWORD" \
    "$ANONYMOUS_COOKIE_JAR" "$SESSION_COOKIE_JAR"
  require_status 'Baseline correct login' 204 "$VERIFY_EVIDENCE_DIR/baseline-correct.metrics.txt"
  if [[ -z "$(verification_cookie_value "$SESSION_COOKIE_JAR" SESSION)" ]]
  then
    printf 'Baseline correct login did not produce a SESSION cookie.\n' >&2
    exit 1
  fi

  curl -sS -b "$SESSION_COOKIE_JAR" \
    -D "$VERIFY_EVIDENCE_DIR/baseline-me.headers.txt" \
    -o "$VERIFY_EVIDENCE_DIR/baseline-me.body.json" \
    -w 'status=%{http_code} latency=%{time_total}\n' \
    "$BASE_URL/api/auth/me" >"$VERIFY_EVIDENCE_DIR/baseline-me.metrics.txt"
  require_status 'Baseline authenticated /me' 200 "$VERIFY_EVIDENCE_DIR/baseline-me.metrics.txt"

  post_form "$VERIFY_EVIDENCE_DIR/baseline-wrong" \
    /api/auth/login "$KNOWN_WRONG_EMAIL" "$WRONG_PASSWORD" "$ANONYMOUS_COOKIE_JAR"
  require_status 'Baseline wrong-password login' 401 "$VERIFY_EVIDENCE_DIR/baseline-wrong.metrics.txt"

  post_form "$VERIFY_EVIDENCE_DIR/baseline-unknown" \
    /api/auth/login "$UNKNOWN_BASELINE_EMAIL" "$WRONG_PASSWORD" "$ANONYMOUS_COOKIE_JAR"
  require_status 'Baseline unknown-account login' 401 "$VERIFY_EVIDENCE_DIR/baseline-unknown.metrics.txt"
}

start_login_request() {
  local category="$1"
  local request_id="$2"
  local email="$3"
  local password="$4"
  post_form "$REQUEST_DIR/${category}-${request_id}" \
    /api/auth/login "$email" "$password" "$ANONYMOUS_COOKIE_JAR"
}

start_authenticated_read() {
  local request_id="$1"
  curl -sS -b "$SESSION_COOKIE_JAR" \
    -D "$REQUEST_DIR/authenticated-me-${request_id}.headers.txt" \
    -o "$REQUEST_DIR/authenticated-me-${request_id}.body.json" \
    -w "category=authenticated-me request=${request_id} status=%{http_code} latency=%{time_total}\n" \
    "$BASE_URL/api/auth/me" \
    >"$REQUEST_DIR/authenticated-me-${request_id}.metrics.txt" \
    2>"$REQUEST_DIR/authenticated-me-${request_id}.stderr.txt"
}

# 这里的 known-correct、known-wrong 和 unknown 只是“请求分类标签”，不是业务对象。
# 标签会成为 evidence 文件名前缀，例如：
#
#   known-wrong-1 请求
#       → requests/known-wrong-1.metrics.txt
#       → 文件记录 status=401/503 与 latency
#       → write_mixed_summary() 按 known-wrong-*.metrics.txt 汇总
#
# 三类 login 与预期结果：
#
#   known-correct：取得 permit 并完成真实 hash → 204；没取得 permit → 503
#   known-wrong：  取得 permit 并完成真实 hash → 401；没取得 permit → 503
#   unknown：      取得 permit 并完成 dummy hash → 401；没取得 permit → 503
#
# 3 correct + 3 wrong + 6 unknown 共用唯一 Argon2 permit；随后并发读取 4 次既有 Session。
run_mixed_burst() {
  local request_id
  local transport_failures=0
  local -a request_pids=()

  for request_id in 1 2 3
  do
    (start_login_request known-correct "$request_id" "$KNOWN_CORRECT_EMAIL" "$CORRECT_PASSWORD") &
    request_pids+=("$!")
    (start_login_request known-wrong "$request_id" "$KNOWN_WRONG_EMAIL" "$WRONG_PASSWORD") &
    request_pids+=("$!")
  done
  for request_id in 1 2 3 4 5 6
  do
    (start_login_request unknown "$request_id" \
      "mixed-burst-${RUN_ID}-${request_id}@example.com" "$WRONG_PASSWORD") &
    request_pids+=("$!")
  done

  sleep 0.05
  for request_id in 1 2 3 4
  do
    (start_authenticated_read "$request_id") &
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

run_recovery_and_session_checks() {
  sleep "$VERIFY_RECOVERY_DELAY_SECONDS"
  post_form "$VERIFY_EVIDENCE_DIR/recovery" /api/auth/login \
    "mixed-recovery-${RUN_ID}@example.com" "$WRONG_PASSWORD" "$ANONYMOUS_COOKIE_JAR"

  curl -sS -b "$SESSION_COOKIE_JAR" \
    -D "$VERIFY_EVIDENCE_DIR/post-burst-me.headers.txt" \
    -o "$VERIFY_EVIDENCE_DIR/post-burst-me.body.json" \
    -w 'status=%{http_code} latency=%{time_total}\n' \
    "$BASE_URL/api/auth/me" >"$VERIFY_EVIDENCE_DIR/post-burst-me.metrics.txt"
}

# 这里存在两个不同层级的“成功/失败”：
#
# 1. 单个 HTTP 请求结果：204 / 401 / 503。
#    503 对该 login 请求是失败，但它是 concurrency gate 正常 fail-fast 的预期证据。
# 2. 整场 verification 结果：PASS / FAIL。
#    PASS 要求同时看到“至少一个 hash 完成”和“至少一个请求因 capacity 被拒绝”，并且
#    Session、recovery、Container resource 状态都正常。
#
# 本函数依次完成：读取前面生成的 metrics → 分类计数 → 判断 PASS/FAIL → 写 summary.md。
write_mixed_summary() {
  local correct_204
  local correct_503
  local wrong_401
  local wrong_503
  local unknown_401
  local unknown_503
  local completed_hashes
  local saturated_hashes
  local unexpected_statuses
  local authenticated_me_200
  local transport_failures
  local recovery_status
  local post_burst_me_status
  local missing_session_headers=0
  local metrics_file
  local result="PASS"

  verification_capture_backend_after
  correct_204="$(verification_count_status 204 "$REQUEST_DIR"/known-correct-*.metrics.txt)"
  correct_503="$(verification_count_status 503 "$REQUEST_DIR"/known-correct-*.metrics.txt)"
  wrong_401="$(verification_count_status 401 "$REQUEST_DIR"/known-wrong-*.metrics.txt)"
  wrong_503="$(verification_count_status 503 "$REQUEST_DIR"/known-wrong-*.metrics.txt)"
  unknown_401="$(verification_count_status 401 "$REQUEST_DIR"/unknown-*.metrics.txt)"
  unknown_503="$(verification_count_status 503 "$REQUEST_DIR"/unknown-*.metrics.txt)"
  authenticated_me_200="$(verification_count_status 200 "$REQUEST_DIR"/authenticated-me-*.metrics.txt)"
  # 这里没有 JVM 内部 hash counter，只能根据最终 HTTP status 推断：
  # 204/401 表示请求取得 permit 并完成了 Argon2；503 表示未取得 permit、没有执行 Argon2。
  completed_hashes=$((correct_204 + wrong_401 + unknown_401))
  saturated_hashes=$((correct_503 + wrong_503 + unknown_503))

  # 各类别只接受与其 credential 语义相符的状态。比如 known-correct 返回 401、
  # known-wrong 返回 204 或任何请求返回 429，都计入 unexpected_statuses。
  unexpected_statuses=$((
    $(verification_count_unexpected_status '^(204|503)$' "$REQUEST_DIR"/known-correct-*.metrics.txt) +
    $(verification_count_unexpected_status '^(401|503)$' "$REQUEST_DIR"/known-wrong-*.metrics.txt) +
    $(verification_count_unexpected_status '^(401|503)$' "$REQUEST_DIR"/unknown-*.metrics.txt) +
    $(verification_count_unexpected_status '^200$' "$REQUEST_DIR"/authenticated-me-*.metrics.txt)
  ))
  transport_failures="$(command cat "$VERIFY_EVIDENCE_DIR/transport-failures.txt")"
  recovery_status="$(verification_status_from_metrics "$VERIFY_EVIDENCE_DIR/recovery.metrics.txt")"
  post_burst_me_status="$(verification_status_from_metrics "$VERIFY_EVIDENCE_DIR/post-burst-me.metrics.txt")"

  for metrics_file in "$REQUEST_DIR"/known-correct-*.metrics.txt
  do
    if [[ "$(verification_status_from_metrics "$metrics_file")" == "204" ]]
    then
      if ! grep -qi '^Set-Cookie: SESSION=' "${metrics_file%.metrics.txt}.headers.txt"
      then
        missing_session_headers=$((missing_session_headers + 1))
      fi
    fi
  done

  {
    verification_summarize_latency known-correct 204 "$REQUEST_DIR"/known-correct-*.metrics.txt
    verification_summarize_latency known-correct 503 "$REQUEST_DIR"/known-correct-*.metrics.txt
    verification_summarize_latency known-wrong 401 "$REQUEST_DIR"/known-wrong-*.metrics.txt
    verification_summarize_latency known-wrong 503 "$REQUEST_DIR"/known-wrong-*.metrics.txt
    verification_summarize_latency unknown 401 "$REQUEST_DIR"/unknown-*.metrics.txt
    verification_summarize_latency unknown 503 "$REQUEST_DIR"/unknown-*.metrics.txt
    verification_summarize_latency authenticated-me 200 "$REQUEST_DIR"/authenticated-me-*.metrics.txt
  } >"$VERIFY_EVIDENCE_DIR/latency-summary.txt"

  rm -- "$ANONYMOUS_COOKIE_JAR" "$SESSION_COOKIE_JAR"
  # 全部 503 不能证明 hash 能正常完成；全部 204/401 又不能证明 saturation fail-fast。
  # 因此整体 PASS 必须同时包含 completed hash 和 capacity-rejected login。
  if ((completed_hashes < 1 || saturated_hashes < 1 || unexpected_statuses != 0))
  then
    result="FAIL"
  fi
  if ((authenticated_me_200 != 4 || transport_failures != 0 || missing_session_headers != 0))
  then
    result="FAIL"
  fi
  if [[ "$recovery_status" != "401" || "$post_burst_me_status" != "200" ]]
  then
    result="FAIL"
  fi
  if [[ "$OOM_KILLED" != "false" || "$RESTART_COUNT" != "0" ]]
  then
    result="FAIL"
  fi

  {
    printf '# M0-S4C2d-2 Provisional Mixed Workload Evidence\n\n'
    printf 'Result: **%s**\n\n' "$result"
    printf 'Classification: **PROVISIONAL — restricted local Container only**\n\n'
    printf -- '- Backend profile: `%s CPU / %s memory / JVM Xmx 256m`\n' \
      "$VERIFY_BACKEND_CPUS" "$VERIFY_BACKEND_MEMORY"
    printf -- '- Argon2: `argon2id-v1`, configured concurrency `1`\n'
    printf -- '- Known correct: `204=%s`, `503=%s`\n' "$correct_204" "$correct_503"
    printf -- '- Known wrong: `401=%s`, `503=%s`\n' "$wrong_401" "$wrong_503"
    printf -- '- Unknown account: `401=%s`, `503=%s`\n' "$unknown_401" "$unknown_503"
    printf -- '- Authenticated /me: `200=%s/4`\n' "$authenticated_me_200"
    printf -- '- Unexpected statuses: `%s`; transport failures: `%s`\n' \
      "$unexpected_statuses" "$transport_failures"
    printf -- '- Recovery login: `%s`; post-burst authenticated /me: `%s`\n' \
      "$recovery_status" "$post_burst_me_status"
    printf -- '- Successful burst logins missing SESSION header: `%s`\n' "$missing_session_headers"
    printf -- '- Backend OOMKilled: `%s`; restart count: `%s`\n\n' \
      "$OOM_KILLED" "$RESTART_COUNT"
    printf 'Latency evidence:\n\n```text\n'
    command cat "$VERIFY_EVIDENCE_DIR/latency-summary.txt"
    printf '```\n\n'
    printf 'This result does not confirm Hosted production capacity. M6 target-hardware testing remains required.\n'
  } >"$VERIFY_EVIDENCE_DIR/summary.md"

  printf 'Verification result: %s\n' "$result"
  printf 'Evidence: %s\n' "$VERIFY_EVIDENCE_DIR/summary.md"
  if [[ "$result" != "PASS" ]]
  then
    exit 1
  fi
}

# 主流程：真实注册登录 setup → mixed burst → recovery/session probe → evidence。
verification_build_and_start
verification_bootstrap_csrf "$ANONYMOUS_COOKIE_JAR"
prepare_mixed_workload
verification_start_resource_sampler
run_mixed_burst
run_recovery_and_session_checks
verification_stop_resource_sampler
write_mixed_summary
