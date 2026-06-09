#!/usr/bin/env bash

opencb_status_file() {
  echo "${CB_STATUS_FILE:-$PWD/build-status.txt}"
}

opencb_summary_file() {
  echo "${CB_SUMMARY_FILE:-$PWD/build-summary.txt}"
}

opencb_build_tool_file() {
  echo "${CB_BUILD_TOOL_FILE:-$PWD/build-tool.txt}"
}

opencb_init_build_status() {
  export CB_STATUS_FILE="$(opencb_status_file)"
  export CB_SUMMARY_FILE="$(opencb_summary_file)"
  export CB_BUILD_TOOL_FILE="$(opencb_build_tool_file)"
  touch build-logs.txt "$CB_SUMMARY_FILE" 2>/dev/null || true
  echo "failure" > "$CB_STATUS_FILE"
  echo "unknown" > "$CB_BUILD_TOOL_FILE"
}

opencb_mark_build_started() {
  echo "started" > "$(opencb_status_file)"
}

opencb_mark_build_failure() {
  echo "failure" > "$(opencb_status_file)"
}

opencb_mark_build_timeout() {
  echo "timeout" > "$(opencb_status_file)"
}

opencb_record_process_exit() {
  local exit_code="$1"
  if [[ $exit_code -eq 124 ]]; then
    opencb_mark_build_timeout
  elif [[ $exit_code -ne 0 ]]; then
    opencb_mark_build_failure
  fi
  return "$exit_code"
}

opencb_finalize_build_status() {
  local exit_code=$?
  local status_file
  local current=""

  status_file="$(opencb_status_file)"
  if [[ -f "$status_file" ]]; then
    current=$(tr -d '\r\n' <"$status_file" || true)
  fi

  if [[ $exit_code -eq 124 ]]; then
    opencb_mark_build_timeout
  elif [[ $exit_code -ne 0 ]]; then
    opencb_mark_build_failure
  elif [[ "$current" == "started" ]]; then
    opencb_mark_build_failure
  fi

  return "$exit_code"
}

opencb_print_build_result() {
  local project_name="${1:?project name required}"
  local status_file
  local status="unknown"

  status_file="$(opencb_status_file)"
  if [[ -f "$status_file" ]]; then
    status=$(tr -d '\r\n' <"$status_file" || echo "unknown")
  fi

  echo "------"
  echo "$project_name status=$status"
  echo "-------"

  [[ "$status" == "success" ]]
}
