FROM lampepfl/dotty:2021-03-22

ARG JDK_VERSION

# Workaround for https://github.com/nodesource/distributions/issues/1266
RUN apt-get update; apt-get install ca-certificates && apt-get update

RUN apt-get install -y openjdk-${JDK_VERSION}-jdk-headless dnsutils
RUN update-java-alternatives --set java-1.${JDK_VERSION}.0-openjdk-amd64

WORKDIR /build
COPY . /build/

ENV SBT_VERSIONS="1.6.2"

RUN ./warmup.sh
