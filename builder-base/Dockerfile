FROM lampepfl/dotty:2021-03-22

ARG JDK_VERSION

# install requirements, install and configure sdkman
# see https://sdkman.io/usage for configuration options
ENV SDKMAN_DIR=/root/.sdkman
RUN apt -y install curl ca-certificates zip unzip openssl findutils && \
    curl -s "https://get.sdkman.io" | bash && \
    echo "sdkman_auto_answer=true" > $SDKMAN_DIR/etc/config && \
    echo "sdkman_auto_selfupdate=false" >> $SDKMAN_DIR/etc/config

# Source sdkman to make the sdk command available and install candidate
RUN bash -c "source $SDKMAN_DIR/bin/sdkman-init.sh && sdk install java $JDK_VERSION"
# Add candidate path to $PATH environment variable
ENV JAVA_HOME="$SDKMAN_DIR/candidates/java/current"
ENV PATH="$JAVA_HOME/bin:$PATH"

WORKDIR /build
COPY . /build/

ENV SBT_VERSIONS="1.6.2 1.7.0 1.7.1 1.7.2 1.7.3 1.8.0 1.8.1 1.8.2"
RUN ./warmup.sh
