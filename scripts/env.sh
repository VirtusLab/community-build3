export CB_SCALA_VERSION=3.0.1-RC1-bin-COMMUNITY-SNAPSHOT

export CM_K8S_NAMESPACE=scala3-community-build
kubectl create namespace $CM_K8S_NAMESPACE --dry-run=client -o yaml | kubectl apply -f - 

function scbk() {
  kubectl -n $CM_K8S_NAMESPACE "$@"
}
