#!/usr/bin/env bash
set -e

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
source "$scriptDir/utils.sh"

echo "Stopping dashboard deployment..."

scbk delete -f "$scriptDir/../k8s/dashboard.yaml" --ignore-not-found

echo "Dashboard stopped."
echo ""
echo "Note: PersistentVolumeClaim 'dashboard-data' is preserved."
echo "To delete it (WARNING: this deletes the SQLite database):"
echo "  scbk delete -f $scriptDir/../k8s/dashboard-data.yaml"

