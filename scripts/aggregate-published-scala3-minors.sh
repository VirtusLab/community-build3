#!/usr/bin/env bash
set -euo pipefail

if [[ $# -gt 1 ]]; then
  echo "Usage: $0 [buildConfig.json]" >&2
  exit 1
fi

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
configFile="${1:-${scriptDir}/../.github/workflows/buildConfig.json}"

if [[ ! -f "$configFile" ]]; then
  echo "Config file not found: $configFile" >&2
  exit 1
fi
configFile="$(cd "$(dirname "$configFile")" &>/dev/null && pwd -P)/$(basename "$configFile")"

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required to parse $configFile" >&2
  exit 1
fi

summaryQuery='
  def is_scala3_published:
    (.value.publishedScalaVersion | type) == "string"
    and (.value.publishedScalaVersion | test("^3\\.[0-9]+(?:\\.|$)"));

  def scala3_minor:
    .value.publishedScalaVersion
    | capture("^(?<major>[0-9]+)\\.(?<minor>[0-9]+)")
    | "\(.major).\(.minor)";

  # `targets` stores published module coordinates as whitespace-separated values.
  def library_count:
    .value.targets
    | if type == "string"
      then gsub("\\s+"; " ") | split(" ") | map(select(length > 0)) | length
      else 0
      end;

  to_entries as $entries
  | ($entries | map(select(is_scala3_published))) as $published
  | {
      total_entries: ($entries | length),
      scala3_entries: ($published | length),
      skipped_entries: (($entries | length) - ($published | length)),
      rows: (
        $published
        | map({minor: scala3_minor, libraries: library_count})
        | group_by(.minor)
        | map({
            minor: .[0].minor,
            libraries: (map(.libraries) | add),
            projects: length
          })
        | sort_by(.minor | split(".") | map(tonumber))
      )
    }
'

summaryJson="$(jq -c "$summaryQuery" "$configFile")"
scala3Entries="$(jq -r '.scala3_entries' <<<"$summaryJson")"
skippedEntries="$(jq -r '.skipped_entries' <<<"$summaryJson")"

printf 'Config file: %s\n' "$configFile"
printf 'Projects with published Scala 3 version: %s\n' "$scala3Entries"
printf 'Projects skipped: %s\n' "$skippedEntries"
printf '\n%-14s %12s %12s\n' "Scala 3 minor" "libraries" "projects"
printf '%-14s %12s %12s\n' "--------------" "---------" "--------"

totalLibraries=0
totalProjects=0
while IFS=$'\t' read -r minor libraries projects; do
  [[ -n "$minor" ]] || continue
  printf '%-14s %12s %12s\n' "$minor" "$libraries" "$projects"
  ((totalLibraries += libraries))
  ((totalProjects += projects))
done < <(jq -r '.rows[] | [.minor, (.libraries | tostring), (.projects | tostring)] | @tsv' <<<"$summaryJson")

printf '%-14s %12s %12s\n' "total" "$totalLibraries" "$totalProjects"
