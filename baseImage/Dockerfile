# # base-dotty image
# FROM lampepfl/dotty:2020-11-19
# RUN apt update
# # RUN apt-get upgrade -y
# RUN apt install iputils-ping -y
# RUN apt install --only-upgrade openjdk-8-jdk-headless openjdk-8-jre-headless -y
# RUN mkdir -p /build
# WORKDIR /build
# RUN sbt version

FROM lampepfl/dotty:2020-11-19
# RUN apt install iputils-ping
# RUN apt install --only-upgrade openjdk-8-jdk-headless openjdk-8-jre-headless
WORKDIR /build
COPY . /build/

ADD ./keystorage/mvnProxy.crt /usr/share/ca-certificates/mvnProxy.crt
RUN chmod 644 /usr/share/ca-certificates/mvnProxy.crt && update-ca-certificates
# optional storage
# ADD ./keystorage/mvnProxy.crt /etc/ssl/certs/mvnProxy.crt
# RUN chmod 644 /etc/ssl/certs/mvnProxy.crt && update-ca-certificates
RUN keytool -keystore /usr/lib/jvm/java-8-openjdk-amd64/jre/lib/security/cacerts -noprompt -trustcacerts -importcert -alias mvnProxy -file /build/keystorage/mvnProxy.crt -storepass changeit


run ./warmup.sh

# ADD ./keystorage/mvnProxy.crt /usr/share/ca-certificates/mvnProxy.crt
# RUN chmod 644 /usr/share/ca-certificates/mvnProxy.crt && update-ca-certificates
# optional storage
# ADD ./keystorage/mvnProxy.crt /etc/ssl/certs/mvnProxy.crt
# RUN chmod 644 /etc/ssl/certs/mvnProxy.crt && update-ca-certificates
# RUN keytool -keystore /usr/lib/jvm/java-8-openjdk-amd64/jre/lib/security/cacerts -noprompt -trustcacerts -importcert -alias mvnProxy -file /build/keystorage/mvnProxy.crt -storepass changeit
# RUN keytool -keystore /usr/lib/jvm/java-15-openjdk-amd64/lib/security/cacerts -noprompt -trustcacerts -importcert -alias mvnProxy -file /build/keystorage/mvnProxy.crt -storepass changeit
# note that default password for java keystorage is "changeit"

