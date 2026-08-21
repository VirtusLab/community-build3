#!/usr/bin/env bash
set -e

# Exit code reported when the build was indexed, but its logs had to be shrunk or dropped.
PartialIndexingExitCode=2

if [ $# -ne 11 ]; then
  echo "Wrong number of script arguments, got $#, expected 11"
  exit 1
fi

elasticUrl="$1"
projectName="$2"
buildResult="$3"
timestamp="$4"
buildSummaryFile="$5"
logsFile="$6"
version="$7"
scalaVersion="$8"
buildId="$9"
buildUrl="${10}"
buildTool="${11}"

# Logs are the only unbounded part of the document and Elasticsearch rejects oversized
# requests. Each retry shrinks them, the last attempt indexes the summary with no logs.
# An empty limit means no limit, 0 means an empty logs field.
logByteLimits=("" 8388608 1048576 65536 0)

tmpFiles=()
cleanup() {
  if [[ ${#tmpFiles[@]} -gt 0 ]]; then
    rm -f "${tmpFiles[@]}"
  fi
}
trap cleanup EXIT

buildSummary="$(cat "${buildSummaryFile}")"
if [[ -z "${buildSummary}" ]]; then
  buildSummary="[]"
elif ! jq -e . >/dev/null 2>&1 <<<"${buildSummary}"; then
  echo "Build summary in ${buildSummaryFile} is not a valid JSON, would index an empty summary"
  buildSummary="[]"
fi

logsSize=$(wc -c <"$logsFile")

keepsCompleteLogs() {
  local maxBytes="$1"
  [[ -z "$maxBytes" ]] || { (( maxBytes > 0 )) && (( logsSize <= maxBytes )); }
}

# Sets attemptLogsFile to a file with logs fitting in $1 bytes and logsReduced accordingly
prepareLogs() {
  local maxBytes="$1"
  if keepsCompleteLogs "$maxBytes"; then
    attemptLogsFile="$logsFile"
    logsReduced=false
    return 0
  fi

  attemptLogsFile=$(mktemp /tmp/feed-elastic-logs.XXXXXX)
  tmpFiles+=("$attemptLogsFile")
  logsReduced=true
  if (( maxBytes == 0 )); then
    : >"$attemptLogsFile"
    return 0
  fi

  # Keep the tail, where build failures are reported, and a bit of the head with the build setup
  local headBytes=$(( maxBytes / 4 ))
  local tailBytes=$(( maxBytes - headBytes ))
  {
    head -c "$headBytes" "$logsFile"
    printf '\n...[%d of %d bytes of logs omitted, see the build URL for full logs]...\n' \
      "$(( logsSize - maxBytes ))" "$logsSize"
    tail -c "$tailBytes" "$logsFile"
  } >"$attemptLogsFile"
}

describeLimit() {
  local maxBytes="$1"
  if keepsCompleteLogs "$maxBytes"; then
    echo "complete logs"
  elif (( maxBytes == 0 )); then
    echo "no logs"
  else
    echo "logs limited to ${maxBytes} bytes"
  fi
}

postDocument() {
  local attemptLogs="$1"
  local json jsonFile response responseStatus curlExit

  json=$(jq -n \
            --arg res "$buildResult" \
            --arg ts "$timestamp" \
            --arg pn "$projectName" \
            --arg ver "$version" \
            --arg scVer "$scalaVersion" \
            --arg buildId "$buildId" \
            --arg buildURL "$buildUrl" \
            --arg buildTool "$buildTool" \
            --argjson sum "$buildSummary" \
            --rawfile logs "$attemptLogs" \
            '{projectName: $pn, version: $ver, scalaVersion: $scVer, status: $res, timestamp: $ts, buildId: $buildId, buildURL: $buildURL, buildTool: $buildTool, summary: $sum, logs: $logs}') || {
    echo "Failed to create the indexed document"
    return 1
  }

  jsonFile=$(mktemp /tmp/feed-elastic-tmp.XXXXXX)
  tmpFiles+=("$jsonFile")
  echo "$json" > "$jsonFile"
  echo "Indexed document size: $(wc -c <"$jsonFile") bytes"

  set +e
  response=$(curl -i -k -w "\n%{http_code}" --user "$ELASTIC_USERNAME:$ELASTIC_PASSWORD" -H "Content-Type: application/json" "${elasticUrl}/project-build-summary/_doc" -d "@${jsonFile}")
  curlExit=$?
  set -e
  echo "Response: ${response}"

  if [[ $curlExit -ne 0 ]]; then
    echo "The request failed with curl exit code: $curlExit"
    return 1
  fi

  responseStatus=$(tail -n1 <<< "$response")
  if [[ "$responseStatus" != "201" ]]; then
    echo "The request resulted in unexpected status code: $responseStatus"
    return 1
  fi
}

attempt=0
for maxLogBytes in "${logByteLimits[@]}"; do
  attempt=$(( attempt + 1 ))
  echo "Indexing ${projectName} with $(describeLimit "$maxLogBytes")"
  prepareLogs "$maxLogBytes"

  if postDocument "$attemptLogsFile"; then
    echo "Indexed ${projectName} with $(describeLimit "$maxLogBytes")"
    if [[ "$logsReduced" == true ]]; then
      exit $PartialIndexingExitCode
    fi
    exit 0
  fi

  if (( attempt < ${#logByteLimits[@]} )); then
    echo "Indexing failed, would retry with $(describeLimit "${logByteLimits[$attempt]}")"
    sleep $(( attempt * 5 ))
  fi
done

echo "Indexing ${projectName} failed in all ${attempt} attempts"
exit 1
