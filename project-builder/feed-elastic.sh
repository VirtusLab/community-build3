#!/usr/bin/env bash
set -e

if [ $# -ne 6 ]; then
  echo "Wrong number of script arguments"
  exit 1
fi

elasticUrl="$1"
projectName="$2"
buildResult="$3"
timestamp="$4"
buildSummaryFile="$5"
logsFile="$6"

json=$(jq -n \
          --arg res "$buildResult" \
          --arg ts "$timestamp" \
          --arg pn "$projectName" \
          --rawfile sum "$buildSummaryFile" \
          --rawfile logs "$logsFile" \
          '{res: $res, build_timestamp: $ts, project_name: $pn, detailed_result: $sum, logs: $logs}')

jsonFile=$(mktemp /tmp/feed-elastic-tmp.XXXXXX)

echo "$json" > "$jsonFile"
          
response=$(curl -v -i -k -w "\n%{http_code}" --user "$ELASTIC_USERNAME:$ELASTIC_PASSWORD" -H "Content-Type: application/json" "${elasticUrl}/community-build/doc" -d "@${jsonFile}")
responseStatus=$(tail -n1 <<< "$response")

rm "$jsonFile"

if [[ "$responseStatus" != "201" ]]; then
  echo "The request resulted in unexpected status code: $responseStatus"
  exit 1
fi
