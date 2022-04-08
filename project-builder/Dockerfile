ARG BASE_IMAGE
FROM $BASE_IMAGE
RUN apt install -y jq
ENV PATH="/root/.local/share/coursier/bin:${PATH}"
RUN curl -fL https://github.com/coursier/launchers/raw/master/cs-x86_64-pc-linux.gz | gzip -d > cs \ 
  && chmod +x cs \
  && ./cs install mill:0.9.12 scalafix \
  && sh -c "mill --version" \ 
  && sh -c "scalafix --version"

# Project dependencies
RUN apt -y install git-lfs 

COPY . /build/
