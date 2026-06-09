#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <projectName>" >&2
  exit 1
fi

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
# shellcheck source=build-status.sh
source "$scriptDir/build-status.sh"

if ! opencb_print_build_result "$1"; then
  if [[ -f build-logs.txt ]]; then
    echo "See build-logs.txt for details." >&2
  fi
  exit 1
fi
