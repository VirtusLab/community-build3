scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]:-${(%):-%x}}" )" &> /dev/null && pwd )"
source $scriptDir/utils.sh

kubectl config use-context minikube

export CM_K8S_NAMESPACE=scala3-community-build
export CM_K8S_JENKINS_OPERATOR_NAMESPACE=scala3-community-build-jenkins-operator

# Create namespaces for local development if they don't exist yet
kubectl get namespace | grep "$CM_K8S_NAMESPACE " > /dev/null \
  || kubectl create namespace $CM_K8S_NAMESPACE --dry-run=client -o yaml | kubectl apply -f -
kubectl get namespace | grep "$CM_K8S_JENKINS_OPERATOR_NAMESPACE " > /dev/null \
  || kubectl create namespace $CM_K8S_JENKINS_OPERATOR_NAMESPACE --dry-run=client -o yaml | kubectl apply -f -