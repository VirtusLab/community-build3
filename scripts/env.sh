if [ -z "${CM_K8S_NAMESPACE+x}" ]; then
  export CM_K8S_NAMESPACE=scala3-community-build
  kubectl create namespace $CM_K8S_NAMESPACE --dry-run=client -o yaml | kubectl apply -f -
fi

if [ -z "${CM_K8S_JENKINS_OPERATOR_NAMESPACE+x}" ]; then
  export CM_K8S_JENKINS_OPERATOR_NAMESPACE=scala3-community-build-jenkins-operator
  kubectl create namespace $CM_K8S_JENKINS_OPERATOR_NAMESPACE --dry-run=client -o yaml | kubectl apply -f - 
fi

function scbk() {
  kubectl -n $CM_K8S_NAMESPACE "$@"
}

function scbok() {
  kubectl -n $CM_K8S_JENKINS_OPERATOR_NAMESPACE "$@"
}
