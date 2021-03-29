FROM gradle:jdk11-hotspot
WORKDIR /mvn-repo
COPY . /mvn-repo
EXPOSE 8080
RUN gradle build
ENTRYPOINT ["gradle", "bootRun"]