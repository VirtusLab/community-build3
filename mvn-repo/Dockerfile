FROM gradle:jdk11-hotspot
WORKDIR /mvn-repo
COPY . /mvn-repo
EXPOSE 8080
RUN gradle clean bootJar

# Setup cron jobs
RUN apt-get update && apt-get install -y cron
RUN crontab /mvn-repo/cronjobs/*
ENTRYPOINT ["/mvn-repo/runJar.sh"]
