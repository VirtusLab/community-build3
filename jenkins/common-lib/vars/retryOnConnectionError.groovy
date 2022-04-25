#!/usr/bin/env groovy

// If maxRetries is negative it would try to retry execution infinitely
def call(Closure body, int maxRetries = 30, int maxDelay = 15){
  def delayBeforeRetry = 0
  while(true){
    try {
      return body()
    } catch(io.fabric8.kubernetes.client.KubernetesClientException ex) {
      def baseMsg = "Catched k8s client exception: ${ex}"
      if(maxRetries == 0) {
        println "${baseMsg} - exceeded retry limit"
        throw ex
      } else {
        if(maxRetries > 0){
          maxRetries -= 1
        }
        println "${baseMsg} - retrying after delay ${delayBeforeRetry}, remaining retries: ${maxRetries}"
        sleep(delayBeforeRetry) // seconds
        delayBeforeRetry = Math.min(maxDelay, delayBeforeRetry + 1)
      }
    }
  }
}