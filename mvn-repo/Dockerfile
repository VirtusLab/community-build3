FROM gradle:jdk11-hotspot
WORKDIR /mvn-repo
COPY . /mvn-repo
EXPOSE 8080
RUN gradle clean bootJar

# Setup cron jobs
RUN apt-get update && apt-get install -y cron
RUN crontab -u root /mvn-repo/cronjobs/*
ENTRYPOINT ["/bin/bash", "-c", "cron && /mvn-repo/runJar.sh"]
