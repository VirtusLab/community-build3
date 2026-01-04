#!/usr/bin/env bash
set -e

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
source "$scriptDir/utils.sh"

# Generate secrets if not already set
GITHUB_CLIENT_ID="${GITHUB_CLIENT_ID:-}"
GITHUB_CLIENT_SECRET="${GITHUB_CLIENT_SECRET:-}"
JWT_SECRET="${JWT_SECRET:-$(openssl rand -base64 32)}"

# GitHub Container Registry credentials (for pulling images)
GHCR_USERNAME="${GHCR_USERNAME:-}"
GHCR_TOKEN="${GHCR_TOKEN:-}"

# Elasticsearch credentials - fetch from ECK secret if not provided
ES_USERNAME="${ES_USERNAME:-elastic}"
ES_PASSWORD="${ES_PASSWORD:-$(scbk get secret community-build-es-elastic-user -o go-template='{{.data.elastic | base64decode}}')}"

if [ -z "$GITHUB_CLIENT_SECRET" ]; then
  echo "Error: GITHUB_CLIENT_SECRET environment variable must be set"
  echo "Get it from: https://github.com/settings/applications"
  exit 1
fi

if [ -z "$ES_PASSWORD" ]; then
  echo "Error: ES_PASSWORD not set and could not be fetched from community-build-es-elastic-user secret"
  exit 1
fi

# Create ghcr.io image pull secret if credentials provided
if [ -n "$GHCR_USERNAME" ] && [ -n "$GHCR_TOKEN" ]; then
  echo "Creating/updating ghcr.io image pull secret..."
  scbk create secret docker-registry ghcr-secret \
    --docker-server=ghcr.io \
    --docker-username="$GHCR_USERNAME" \
    --docker-password="$GHCR_TOKEN" \
    --docker-email="${GHCR_EMAIL:-$GHCR_USERNAME@users.noreply.github.com}" \
    --dry-run=client -o yaml | scbk apply -f -
else
  echo "Note: GHCR_USERNAME/GHCR_TOKEN not set, skipping ghcr-secret creation"
  echo "      If ghcr-secret doesn't exist, image pulls will fail"
fi

echo "Creating/updating dashboard secrets..."
scbk create secret generic dashboard-secrets \
  --from-literal=github-client-id="$GITHUB_CLIENT_ID" \
  --from-literal=github-client-secret="$GITHUB_CLIENT_SECRET" \
  --from-literal=jwt-secret="$JWT_SECRET" \
  --from-literal=es-username="$ES_USERNAME" \
  --from-literal=es-password="$ES_PASSWORD" \
  --dry-run=client -o yaml | scbk apply -f -

echo "Creating persistent volume claim..."
scbk apply -f "$scriptDir/../k8s/dashboard-data.yaml"

echo "Deploying dashboard..."
dashboardYaml="$scriptDir/../k8s/dashboard.yaml"
if [[ ! -z "${CB_VERSION}" ]]; then
  cat "${dashboardYaml}" | sed -E "s|(image: ghcr.io/virtuslab/scala-community-build-dashboard):.*|\1:${CB_VERSION}|" - | scbk apply -f -
else
  scbk apply -f "${dashboardYaml}"
fi

echo ""
echo "=== Dashboard Deployment Complete ==="
echo "Waiting for deployment to be ready..."
scbk rollout status deployment/dashboard --timeout=120s

echo ""
echo "Dashboard URL: https://scala3.westeurope.cloudapp.azure.com/dashboard"
echo ""
echo "To check logs:"
echo "  scbk logs -f deployment/dashboard"

