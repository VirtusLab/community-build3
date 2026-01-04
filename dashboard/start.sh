#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
projectRoot="$scriptDir/.."

# Source Azure/K8s context
source "$projectRoot/scripts/use-azure.sh"
source "$projectRoot/scripts/utils.sh"

# Get Elasticsearch credentials from K8s secret
# For local development, use the public endpoint with /data path prefix
export ES_HOST="${ES_HOST:-scala3.westeurope.cloudapp.azure.com}"
export ES_PORT="${ES_PORT:-443}"
export ES_PATH_PREFIX="${ES_PATH_PREFIX:-/data}"
export ES_TLS_INSECURE="${ES_TLS_INSECURE:-false}"
export ES_USERNAME="${ES_USERNAME:-elastic}"
export ES_PASSWORD=$(scbk get secret "community-build-es-elastic-user" -o go-template="{{.data.${ES_USERNAME} | base64decode}}")

# SQLite database path (relative to dashboard directory)
export SQLITE_PATH="${SQLITE_PATH:-$projectRoot/dashboard.db}"

# GitHub OAuth configuration
export GITHUB_CLIENT_ID="${GITHUB_CLIENT_ID:-}"
export GITHUB_CLIENT_SECRET="${GITHUB_CLIENT_SECRET:-}"
export GITHUB_CALLBACK_URL="${GITHUB_CALLBACK_URL:-http://localhost:8080/auth/callback}"
export JWT_SECRET="${JWT_SECRET:-$(openssl rand -base64 32 2>/dev/null)}"

echo "=== Community Build Dashboard ==="
echo "ES_HOST: $ES_HOST"
echo "SQLITE_PATH: $SQLITE_PATH"
echo "GITHUB_CLIENT_ID: ${GITHUB_CLIENT_ID:+configured}"
echo "GITHUB_CLIENT_SECRET: ${GITHUB_CLIENT_SECRET:+configured}"
echo "================================="

cd "$projectRoot/dashboard"
exec scala-cli run .

