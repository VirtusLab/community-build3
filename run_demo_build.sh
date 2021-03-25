#!/usr/bin/bash

set -e

./run_maven_server_bg.sh

export SCALA_BASE_VERSION=3.0.0-RC1


kill -9 $(cat server.pid)
