ARG BASE_IMAGE
FROM $BASE_IMAGE
RUN apt install -y jq
# Version 0.10.0 seemed to have some problems when running locally
RUN sh -c "curl -L https://github.com/com-lihaoyi/mill/releases/download/0.9.12/0.9.12 > /usr/local/bin/mill && chmod +x /usr/local/bin/mill" \ 
  && sh -c "mill --version"
COPY . /build/
