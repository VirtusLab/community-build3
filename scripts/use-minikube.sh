scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]:-${(%):-%x}}" )" &> /dev/null && pwd )"
source $scriptDir/utils.sh

kubectl config use-context minikube

export CB_K8S_NAMESPACE=scala3-community-build

# Create namespaces for local development if they don't exist yet
kubectl get namespace | grep "$CB_K8S_NAMESPACE " > /dev/null \
  || kubectl create namespace $CB_K8S_NAMESPACE --dry-run=client -o yaml | kubectl apply -f -
