scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]:-${(%):-%x}}" )" &> /dev/null && pwd )"
source $scriptDir/utils.sh

kubectl config use-context osj-scala-euw-prod-aks-cluster

export CB_K8S_NAMESPACE=jenkins-scala3
