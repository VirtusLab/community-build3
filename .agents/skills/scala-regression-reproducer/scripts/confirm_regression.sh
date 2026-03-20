#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: confirm_regression.sh [--dry-run] [project-key] [known-good-scala] [bad-scala]

Run the community-build project twice:
- known-good Scala version
- bad Scala version (defaults to the latest nightly by omitting the version)

Auto-discovery:
- project-key: infer from the checked-out repo remote and .github/workflows/buildConfig.json
- known-good-scala: use publishedScalaVersion from buildConfig.json
- bad-scala: omit to let scripts/run.sh pick the latest nightly

Run safety:
- Prefer SKIP_BUILD_SETUP=1 confirm_regression.sh ... once the project already exists in buildConfig.json
- scripts/run.sh regenerates .github/workflows/buildConfig.json for the designated project unless SKIP_BUILD_SETUP=1 is set
- If you regenerate buildConfig.json because the project entry is missing, restore it afterward with: git restore .github/workflows/buildConfig.json
- scripts/run.sh clones into $PWD/repo and deletes any existing repo/ directory there first; this helper runs each case from out/scala-regression-reproducer/<timestamp>/{good,bad} to contain that cleanup

Environment overrides:
- COMMUNITY_BUILD_ROOT: path containing .github/workflows/buildConfig.json and scripts/run.sh
- REPO_DIR: checked-out project repo to match against buildConfig.json
USAGE
}

dry_run=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    -n|--dry-run)
      dry_run=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      break
      ;;
    -*)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
    *)
      break
      ;;
  esac
done

project_name="${1:-}"
good_version="${2:-}"
bad_version="${3:-}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

find_community_build_root() {
  local dir
  dir="${COMMUNITY_BUILD_ROOT:-$PWD}"
  while [[ "$dir" != "/" ]]; do
    if [[ -f "$dir/.github/workflows/buildConfig.json" && -x "$dir/scripts/run.sh" ]]; then
      printf '%s\n' "$dir"
      return 0
    fi
    dir="$(dirname "$dir")"
  done
  return 1
}

normalize_repo_url() {
  local url="$1"
  url="${url%.git}"
  case "$url" in
    git@github.com:*)
      url="https://github.com/${url#git@github.com:}"
      ;;
    ssh://git@github.com/*)
      url="https://github.com/${url#ssh://git@github.com/}"
      ;;
  esac
  printf '%s\n' "$url"
}

community_build_root="$(find_community_build_root)"
run_script="$community_build_root/scripts/run.sh"
config_file="$community_build_root/.github/workflows/buildConfig.json"

if [[ -n "${REPO_DIR:-}" ]]; then
  repo_dir="$REPO_DIR"
elif git_root="$(git rev-parse --show-toplevel 2>/dev/null)" && [[ "$git_root" != "$community_build_root" ]]; then
  repo_dir="$git_root"
else
  repo_dir="$community_build_root/repo"
fi

if [[ ! -d "$repo_dir" ]]; then
  echo "Repo dir not found: $repo_dir" >&2
  exit 1
fi

if [[ -z "$project_name" ]]; then
  repo_remote_raw="$(git -C "$repo_dir" remote get-url origin 2>/dev/null || true)"
  repo_remote="$(normalize_repo_url "$repo_remote_raw")"
  if [[ -z "$repo_remote" ]]; then
    echo "Could not auto-discover project key: missing origin remote in $repo_dir" >&2
    exit 1
  fi
  project_name="$(jq -r --arg repo_remote "$repo_remote" '
    to_entries[]
    | select((.value.repoUrl | sub("\\.git$"; "")) == $repo_remote)
    | .key
  ' "$config_file" | head -n 1)"
  if [[ -z "$project_name" ]]; then
    echo "Could not map repo remote to a buildConfig project key: $repo_remote" >&2
    exit 1
  fi
fi

if [[ -z "$good_version" ]]; then
  good_version="$(jq -r --arg project "$project_name" '.[$project].publishedScalaVersion // empty' "$config_file")"
  if [[ -z "$good_version" || "$good_version" == "null" ]]; then
    echo "Could not auto-discover a known-good Scala version for $project_name; pass it explicitly." >&2
    exit 1
  fi
fi

timestamp="$(date +%Y%m%d-%H%M%S)"
out_dir="$community_build_root/out/scala-regression-reproducer/$timestamp"
mkdir -p "$out_dir"

echo "communityBuildRoot: $community_build_root"
echo "repoDir: $repo_dir"
echo "projectName: $project_name"
echo "goodVersion: $good_version"
if [[ -n "$bad_version" ]]; then
  echo "badVersion: $bad_version"
else
  echo "badVersion: <latest nightly via scripts/run.sh default>"
fi
echo "outputDir: $out_dir"

run_case() {
  local label="$1"
  local requested_version="$2"
  local case_dir="$out_dir/$label"
  mkdir -p "$case_dir"

  local -a cmd=("$run_script" "$project_name")
  if [[ -n "$requested_version" ]]; then
    cmd+=("$requested_version")
  fi

  printf '%s\n' "[$label] command: ${cmd[*]}"
  if (( dry_run )); then
    return 0
  fi

  set +e
  (
    cd "$case_dir"
    "${cmd[@]}" 2>&1 | tee run.log
  )
  local exit_code=$?
  set -e

  local actual_version=""
  if [[ -f "$case_dir/run.log" ]]; then
    actual_version="$(sed -n 's/^scalaVersion: //p' "$case_dir/run.log" | tail -n 1)"
  fi
  if [[ -z "$actual_version" ]]; then
    actual_version="$requested_version"
  fi

  local build_status="missing"
  if [[ -f "$case_dir/build-status.txt" ]]; then
    build_status="$(<"$case_dir/build-status.txt")"
  fi

  printf '%s\n' "$exit_code" > "$case_dir/exit-code.txt"
  printf '%s\n' "$build_status" > "$case_dir/status.txt"
  printf '%s\n' "$actual_version" > "$case_dir/scala-version.txt"

  printf '%s\n' "[$label] exitCode: $exit_code"
  printf '%s\n' "[$label] buildStatus: $build_status"
  printf '%s\n' "[$label] scalaVersion: ${actual_version:-<unknown>}"
}

run_case good "$good_version"
run_case bad "$bad_version"

if (( dry_run )); then
  exit 0
fi

good_status="$(<"$out_dir/good/status.txt")"
bad_status="$(<"$out_dir/bad/status.txt")"
good_actual="$(<"$out_dir/good/scala-version.txt")"
bad_actual="$(<"$out_dir/bad/scala-version.txt")"

echo "summary: good=$good_status ($good_actual), bad=$bad_status ($bad_actual)"

if [[ "$good_status" == "success" && "$bad_status" != "success" ]]; then
  echo "Regression confirmed. Inspect:"
  echo "  $out_dir/good"
  echo "  $out_dir/bad"
  exit 0
fi

if [[ "$good_status" != "success" ]]; then
  echo "Known-good run failed; regression is not confirmed." >&2
else
  echo "Bad run succeeded; regression is not confirmed." >&2
fi

echo "Inspect:"
echo "  $out_dir/good"
echo "  $out_dir/bad"
exit 1
