FROM lampepfl/dotty:2020-11-19

WORKDIR /build
COPY . /build/

RUN apt update
RUN apt install -y openjdk-11-jdk-headless
RUN apt install dnsutils -y

RUN update-java-alternatives --set java-1.11.0-openjdk-amd64

ADD ./keystorage/repo1.maven.org.crt /usr/share/ca-certificates/repo1.maven.org.crt
RUN chmod 644 /usr/share/ca-certificates/repo1.maven.org.crt && update-ca-certificates
RUN keytool -keystore /usr/lib/jvm/java-11-openjdk-amd64/lib/security/cacerts -noprompt -trustcacerts -importcert -alias repo1.maven.org -file /build/keystorage/repo1.maven.org.crt -storepass changeit


ADD ./keystorage/repo.maven.apache.org.crt /usr/share/ca-certificates/repo.maven.apache.org.crt
RUN chmod 644 /usr/share/ca-certificates/repo.maven.apache.org.crt && update-ca-certificates
RUN keytool -keystore /usr/lib/jvm/java-11-openjdk-amd64/lib/security/cacerts -noprompt -trustcacerts -importcert -alias repo.maven.apache.org -file /build/keystorage/repo.maven.apache.org.crt -storepass changeit


ADD ./keystorage/repo1.maven.org.fake.crt /usr/share/ca-certificates/repo1.maven.org.fake.crt
RUN chmod 644 /usr/share/ca-certificates/repo1.maven.org.fake.crt && update-ca-certificates
RUN keytool -keystore /usr/lib/jvm/java-11-openjdk-amd64/lib/security/cacerts -noprompt -trustcacerts -importcert -alias repo1.maven.org.fake -file /build/keystorage/repo1.maven.org.fake.crt -storepass changeit

ENV SBT_VERSION=1.4.9

RUN ./warmup.sh