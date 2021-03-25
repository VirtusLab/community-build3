#!/usr/bin/bash

set -e

cd executor 

docker build -t communitybuild3/executor . 

docker run --add-host repo1.maven.org:172.17.0.1  -it communitybuild3/executor bash