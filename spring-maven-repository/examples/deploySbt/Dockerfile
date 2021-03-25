FROM lampepfl/dotty
WORKDIR /build
COPY . /build/
ENTRYPOINT [ "sbt compile && sbt publish” ]