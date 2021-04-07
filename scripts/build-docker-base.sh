#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# TODO: For production set KEYTOOL_STORE_PASS in a safer way (e.g. use BuildKit's --secret)
docker build -t communitybuild3/base --build-arg KEYTOOL_STORE_PASS=keytool_store_pass $scriptDir/../base-image
