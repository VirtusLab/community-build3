function scbk() {
  if [ -z "$CB_K8S_NAMESPACE" ]; then
    echo >&2 "CB_K8S_NAMESPACE env variable has to be set"
    exit 1
  fi

  kubectl -n "$CB_K8S_NAMESPACE" "$@"
}

function checkJavaVersion() {
  version="$1"
  supportedVersions=(17 21)
  
  if [ -z "$version" ]; then
    echo >&2 "Java version has to be set"
    exit 1
  fi
  if [[ ! " ${supportedVersions[*]} " =~ " ${version} " ]]; then
    echo >&2 "Java $version is not supported, available versions: ${supportedVersions[*]}"
    exit 1
  fi
}

function buildTag() {
  if [ $# -ne 2 ]; then 
    echo >&2 "Wrong number of function arguments invoked in $0, expected ${FUNCNAME} <revision> <jdkVersion>, got $#: $@"
    exit 1
  fi

  revision="$1"
  jdkVersion="$2"
  checkJavaVersion "${jdkVersion}"

  echo "jdk${jdkVersion}-$revision"
}
