#!/usr/bin/env bash
set -euo pipefail

DASHBOARD_URL="${DASHBOARD_URL:-https://scala3.westeurope.cloudapp.azure.com/dashboard}"

usage() {
  echo "Usage: $0 <target-scala-version> --vs <label> <base-scala-version> [...]" >&2
  exit 1
}

[[ $# -ge 1 ]] || usage
TARGET="$1"
shift

compare_url() {
  local base="$1"
  local target="$2"
  printf '%s/compare?baseScalaVersion=%s&targetScalaVersion=%s' "$DASHBOARD_URL" "$base" "$target"
}

declare -a LABELS=()
declare -a BASES=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --vs)
      [[ $# -ge 3 ]] || usage
      LABELS+=("$2")
      BASES+=("$3")
      shift 3
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      ;;
  esac
done

[[ ${#LABELS[@]} -gt 0 ]] || usage

if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  {
    echo "### Dashboard comparisons"
    echo ""
    echo "**Target (current build):** \`${TARGET}\`"
    echo ""
  } >> "$GITHUB_STEP_SUMMARY"
fi

echo "::group::Dashboard comparison links"
for i in "${!LABELS[@]}"; do
  label="${LABELS[$i]}"
  base="${BASES[$i]}"
  url="$(compare_url "$base" "$TARGET")"

  echo "${label}: ${url}"
  echo "::notice title=${label}::${url}"

  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    echo "- **${label}**: [\`${base}\` → \`${TARGET}\`](${url})" >> "$GITHUB_STEP_SUMMARY"
  fi
done
echo "::endgroup::"
