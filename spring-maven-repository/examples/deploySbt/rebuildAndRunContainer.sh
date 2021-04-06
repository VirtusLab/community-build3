#!/usr/bin/env bash

docker rm deploy-sbt
docker build -t deploy-sbt .
docker run --add-host repo1.maven.org:$(ipconfig getifaddr en0) --add-host repo.maven.apache.org:$(ipconfig getifaddr en0) --add-host repo1.maven.org.fake:$(ipconfig getifaddr en0) -it --name deploy-sbt -p 5005:5005 --network builds-network deploy-sbt


docker run -it --name communitybuild3/base --entrypoint /bin/bash --network builds-network communitybuild3/base