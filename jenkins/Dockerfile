FROM jenkins/jenkins:2.277.1-lts-jdk11

ENV JENKINS_USER admin
ENV JENKINS_PASS admin

ENV JAVA_OPTS -Djenkins.install.runSetupWizard=false

USER root

RUN apt-get update && apt-get install -y apt-transport-https \
       ca-certificates curl gnupg2 \
       sudo software-properties-common
RUN curl -fsSL https://download.docker.com/linux/debian/gpg | apt-key add -
RUN apt-key fingerprint 0EBFCD88
RUN add-apt-repository \
       "deb [arch=amd64] https://download.docker.com/linux/debian \
       $(lsb_release -cs) stable"
RUN apt-get update && apt-get install -y docker-ce docker-ce-cli

RUN usermod -G docker jenkins

USER jenkins

RUN jenkins-plugin-cli --plugins "blueocean:1.24.5 docker-workflow:1.26 job-dsl:1.77 depgraph-view:1.0.5 configuration-as-code:1.51 logstash:2.4.0"

COPY --chown=jenkins casc-configs /var/jenkins_home/casc-configs
COPY --chown=jenkins seeds /var/jenkins_home/seeds