FROM alpine/git:1.0.30
RUN apk add git-daemon
COPY . /repos
RUN cd /repos && \
    ash init-repo.sh foo && \
    ash init-repo.sh bar && \
    ash init-repo.sh failing
