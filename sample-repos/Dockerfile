FROM alpine/git:1.0.30
RUN apk add git-daemon
COPY . /repos
RUN cd /repos/foo && \
  git init && \
  git config user.name "Scala3 Community Build" && \
  git config user.email "<>" && \
  git add --all && \
  git commit -m "init"
RUN cd /repos/bar && \
  git init && \
  git add --all && \
  git config user.name "Scala3 Community Build" && \
  git config user.email "<>" && \
  git commit -m "init"