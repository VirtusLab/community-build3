#!/usr/bin/env bash
set -e

if [ -z "$CB_LICENSE_CLIENT" ]; then
  echo >&2 "CB_LICENSE_CLIENT env variable has to be set"
  exit 1
fi

if [ -z "$CB_LICENSE_KEY" ]; then
  echo >&2 "CB_LICENSE_KEY env variable has to be set"
  exit 1
fi

if [ -z "$CB_K8S_JENKINS_OPERATOR_NAMESPACE" ]; then
  echo >&2 "CB_K8S_JENKINS_OPERATOR_NAMESPACE env variable has to be set"
  exit 1
fi

if [ -z "$CB_K8S_NAMESPACE" ]; then
  echo >&2 "CB_K8S_NAMESPACE env variable has to be set"
  exit 1
fi

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source $scriptDir/utils.sh

cat <<EOF | scbok apply -f - --dry-run=client -o yaml | kubectl apply -f -
apiVersion: v1
kind: Secret
metadata:
  name: license
stringData:
  clientName: "$CB_LICENSE_CLIENT"
  licenseKey: "$CB_LICENSE_KEY"
EOF

# helm repo add carthago https://carthago-cloud.github.io/op-jenkins-helm/ 
helm repo update carthago

helm --namespace="$CB_K8S_JENKINS_OPERATOR_NAMESPACE" \
  install carthago-op-jenkins carthago/carthago-op-jenkins -f k8s/jenkins-operator.yaml \
  --set operator.watchedNamespaces[0]="$CB_K8S_NAMESPACE"
