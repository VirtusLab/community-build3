FROM lampepfl/dotty:2020-11-19

WORKDIR /build
COPY . /build/

run apt-get update && apt-get install -y openjdk-11-jdk-headless

run update-java-alternatives --set java-1.11.0-openjdk-amd64

env SBT_VERSION=1.4.9

run ./warmup.sh