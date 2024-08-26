FROM ubuntu:20.04
LABEL org.opencontainers.image.source="https://github.com/VirtusLab/community-build3"

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

ARG JDK_VERSION
# Source sdkman to make the sdk command available and install candidate
RUN bash -c "source $SDKMAN_DIR/bin/sdkman-init.sh && sdk install java $JDK_VERSION && sdk install sbt 1.9.6" 
# Add candidate path to $PATH environment variable
ENV JAVA_HOME="$SDKMAN_DIR/candidates/java/current"
ENV SBT_HOME="$SDKMAN_DIR/candidates/sbt/current"
ENV PATH="$JAVA_HOME/bin:$SBT_HOME/bin:$PATH"

WORKDIR /build
COPY . /build/

ENV SBT_VERSIONS="1.9.6"
RUN ./warmup.sh
