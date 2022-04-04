#!/usr/bin/env bash
set -e

if [ $# -ne 9 ]; then
  echo "Wrong number of script arguments, got $#, expected 7"
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

buildSummary="$(cat ${buildSummaryFile})"

json=$(jq -n \
          --arg res "$buildResult" \
          --arg ts "$timestamp" \
          --arg pn "$projectName" \
          --arg ver "$version" \
          --arg scVer "$scalaVersion" \
          --arg buildId "$buildId" \
          --argjson sum "$buildSummary" \
          --rawfile logs "$logsFile" \
          '{projectName: $pn, version: $ver, scalaVersion: $scVer, status: $res, timestamp: $ts, buildId: $buildId, summary: $sum, logs: $logs}')

jsonFile=$(mktemp /tmp/feed-elastic-tmp.XXXXXX)

echo "$json" > "$jsonFile"
          
response=$(curl -v -i -k -w "\n%{http_code}" --user "$ELASTIC_USERNAME:$ELASTIC_PASSWORD" -H "Content-Type: application/json" "${elasticUrl}/project-build-summary/_doc" -d "@${jsonFile}")
responseStatus=$(tail -n1 <<< "$response")

rm "$jsonFile"

if [[ "$responseStatus" != "201" ]]; then
  echo "The request resulted in unexpected status code: $responseStatus"
  exit 1
fi
