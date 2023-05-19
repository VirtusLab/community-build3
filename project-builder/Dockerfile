ARG BASE_IMAGE
FROM $BASE_IMAGE
RUN apt install -y jq
ENV PATH="/root/.local/share/coursier/bin:${PATH}"
RUN curl -fLo coursier https://github.com/coursier/launchers/raw/master/coursier && \
    chmod +x coursier && \
    ./coursier install mill:0.10.10 scalafix scala-cli:1.0.0-RC2 --contrib \
    && sh -c "mill --version" \
    && sh -c "scalafix --version" \
    && sh -c "scala-cli --version" && \
    (echo 'println("warmup finished")' | sh -c 'scala-cli run -S 3 - --main-class stdin_sc')

# Project dependencies
RUN apt update && apt -y install git-lfs protobuf-compiler python3
RUN java -version
COPY . /build/
