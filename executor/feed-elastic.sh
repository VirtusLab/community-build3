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

buildSummary=$(cat $buildSummaryFile)
logs=$(cat $logsFile)

json=$(jq -n \
          --arg res "$buildResult" \
          --arg ts "$timestamp" \
          --arg pn "$projectName" \
          --arg sum "$buildSummary" \
          --arg logs "$logs" \
          '{res: $res, build_timestamp: $ts, project_name: $pn, detailed_result: $sum, logs: $logs}')
          
response=$(curl -v -i -k --user "$ELASTIC_USERNAME:$ELASTIC_PASSWORD" -H "Content-Type: application/json" "${elasticUrl}/community-build/doc" -d "${json}")
echo "$response"
responseStatus=$(echo "$response" | grep '^HTTP' | awk '{print $2}')

if [[ "$responseStatus" != "201" ]]; then
  exit -1 
fi
