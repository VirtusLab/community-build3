FROM gradle:jdk11-hotspot
WORKDIR /proxy
COPY . /proxy
EXPOSE 443
RUN gradle build
ENTRYPOINT ["gradle", "bootRun"]