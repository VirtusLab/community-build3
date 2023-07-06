FROM ubuntu:22.04

ARG JDK_VERSION

ENV LANG=C.UTF-8
ENV DEBIAN_FRONTEND=noninteractive
ENV TZ=Europe/Zurich

# install requirements, install and configure sdkman
# see https://sdkman.io/usage for configuration options
ENV SDKMAN_DIR=/root/.sdkman
RUN apt-get update && apt-get upgrade -y && \
    apt-get install -y software-properties-common && \
    apt-get install -y curl ca-certificates zip unzip openssl findutils bash git ssh htop nano vim-tiny zile && \
    (curl -fsSL https://deb.nodesource.com/setup_18.x | bash -) && apt-get install -y nodejs && \
    curl -s "https://get.sdkman.io" | bash && \
    echo "sdkman_auto_answer=true" > $SDKMAN_DIR/etc/config && \
    echo "sdkman_auto_selfupdate=false" >> $SDKMAN_DIR/etc/config

# Source sdkman to make the sdk command available and install candidate
RUN bash -c "source $SDKMAN_DIR/bin/sdkman-init.sh && sdk install java $JDK_VERSION && sdk install sbt 1.9.1" 
# Add candidate path to $PATH environment variable
ENV JAVA_HOME="$SDKMAN_DIR/candidates/java/current"
ENV SBT_HOME="$SDKMAN_DIR/candidates/sbt/current"
ENV PATH="$JAVA_HOME/bin:$SBT_HOME/bin:$PATH"

WORKDIR /build
COPY . /build/

ENV SBT_VERSIONS="1.7.3 1.8.2 1.9.1"
RUN ./warmup.sh
