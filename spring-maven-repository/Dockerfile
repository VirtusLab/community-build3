FROM gradle:jdk11-hotspot
WORKDIR /proxy
COPY . /proxy
RUN gradle build
CMD ["gradle", "bootRun"]