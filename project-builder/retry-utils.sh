#!/usr/bin/env bash

# Helpers for retrying builds rejected by remote repositories with HTTP 429 (rate limiting).
# Maven Central throttles CI IP ranges, which breaks resolution at any stage: sbt launcher
# bootstrap, meta-build plugin resolution or dependency download.

OPENCB_RATE_LIMIT_MAX_RETRIES="${OPENCB_RATE_LIMIT_MAX_RETRIES:-4}"
OPENCB_RATE_LIMIT_BASE_DELAY="${OPENCB_RATE_LIMIT_BASE_DELAY:-60}"
OPENCB_RATE_LIMIT_MAX_DELAY="${OPENCB_RATE_LIMIT_MAX_DELAY:-300}"
# Only the tail of the logs is inspected: a build aborted by rate limiting reports it at the
# very end, while 429s recovered from by the build tool itself appear early and must not
# trigger retries of a build failing for an unrelated reason.
OPENCB_RATE_LIMIT_LOG_TAIL_LINES="${OPENCB_RATE_LIMIT_LOG_TAIL_LINES:-500}"

# Patterns always contain the status code, plain "Too Many Requests" would also match
# project sources and test output (eg. HTTP status enums).
opencb_log_has_rate_limit() {
  local logFile="${1:-}"
  [[ -f "$logFile" ]] || return 1
  tail -n "$OPENCB_RATE_LIMIT_LOG_TAIL_LINES" -- "$logFile" | grep -qF \
    -e 'response code: 429' \
    -e 'status code: 429' \
    -e '429 Too Many Requests' \
    -e 'HTTP 429' \
    -e 'code 429'
}

# Exponential backoff with jitter, so parallel jobs throttled at the same moment
# do not all come back at once.
opencb_rate_limit_delay() {
  local attempt="${1:-1}"
  local delay=$OPENCB_RATE_LIMIT_BASE_DELAY
  local i
  for ((i = 1; i < attempt; i++)); do
    delay=$((delay * 2))
    if ((delay >= OPENCB_RATE_LIMIT_MAX_DELAY)); then
      delay=$OPENCB_RATE_LIMIT_MAX_DELAY
      break
    fi
  done
  echo $((delay + RANDOM % 60))
}

opencb_wait_for_rate_limit() {
  local attempt="${1:-1}"
  local delay
  delay=$(opencb_rate_limit_delay "$attempt")
  echo "Remote repository rate limited the build (HTTP 429), retry $attempt/$OPENCB_RATE_LIMIT_MAX_RETRIES after ${delay}s" >&2
  sleep "$delay"
}

# Runs a command streaming its output to both stdout and the given log file,
# retrying the whole command as long as the failure was caused by rate limiting.
# Usage: opencb_run_retrying_rate_limits <log-file> <command> [args...]
opencb_run_retrying_rate_limits() {
  local logFile="${1:?log file required}"
  shift
  local attempt=0
  local exitCode=0
  local errExitEnabled=0
  [[ $- == *e* ]] && errExitEnabled=1

  while true; do
    set +e
    "$@" 2>&1 | tee "$logFile"
    exitCode=${PIPESTATUS[0]}
    ((errExitEnabled)) && set -e

    if [[ $exitCode -eq 0 ]]; then
      return 0
    fi
    if ((attempt >= OPENCB_RATE_LIMIT_MAX_RETRIES)) || ! opencb_log_has_rate_limit "$logFile"; then
      return "$exitCode"
    fi
    attempt=$((attempt + 1))
    opencb_wait_for_rate_limit "$attempt"
  done
}
