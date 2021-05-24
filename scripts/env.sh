export CB_SCALA_VERSION=3.0.1-RC1-bin-COMMUNITY-SNAPSHOT
export CB_LOCAL_MVN_REPO_URL=http://localhost:8081/maven2/tmp

export CM_K8S_NAMESPACE=scala3-community-build
kubectl create namespace $CM_K8S_NAMESPACE --dry-run=client -o yaml | kubectl apply -f - 
alias scbk="kubectl -n $CM_K8S_NAMESPACE"
