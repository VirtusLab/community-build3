FROM lampepfl/dotty:2021-03-22

ARG KEYTOOL_STORE_PASS

# Workaround for https://github.com/nodesource/distributions/issues/1266
RUN apt-get update; apt-get install ca-certificates && apt-get update

RUN apt-get install -y openjdk-11-jdk-headless
RUN apt-get install dnsutils -y

RUN update-java-alternatives --set java-1.11.0-openjdk-amd64

WORKDIR /build
COPY . /build/

ENV SBT_VERSIONS="1.5.5 1.6.0-M1"

RUN ./warmup.sh
