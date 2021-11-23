function scbk() {
  if [ -z "$CM_K8S_NAMESPACE" ]; then
    echo >&2 "CM_K8S_NAMESPACE env variable has to be set"
    exit 1
  fi

  kubectl -n "$CM_K8S_NAMESPACE" "$@"
}

function scbok() {
  if [ -z "$CM_K8S_JENKINS_OPERATOR_NAMESPACE" ]; then
    echo >&2 "CM_K8S_JENKINS_OPERATOR_NAMESPACE env variable has to be set"
    exit 1
  fi

  kubectl -n "$CM_K8S_JENKINS_OPERATOR_NAMESPACE" "$@"
}
