FROM lampepfl/dotty:2021-03-22

ARG KEYTOOL_STORE_PASS

WORKDIR /build
COPY . /build/

RUN apt update
RUN apt install -y openjdk-11-jdk-headless
RUN apt install dnsutils -y

RUN update-java-alternatives --set java-1.11.0-openjdk-amd64

ENV DEFAULT_KEYTOOL_STORE_PASS=changeit

ADD ./keystorage/repo1.maven.org.crt /usr/share/ca-certificates/repo1.maven.org.crt
RUN chmod 644 /usr/share/ca-certificates/repo1.maven.org.crt && update-ca-certificates
RUN keytool -keystore /usr/lib/jvm/java-11-openjdk-amd64/lib/security/cacerts -noprompt -trustcacerts -importcert -alias repo1.maven.org -file /build/keystorage/repo1.maven.org.crt -storepass $DEFAULT_KEYTOOL_STORE_PASS

ADD ./keystorage/repo.maven.apache.org.crt /usr/share/ca-certificates/repo.maven.apache.org.crt
RUN chmod 644 /usr/share/ca-certificates/repo.maven.apache.org.crt && update-ca-certificates
RUN keytool -keystore /usr/lib/jvm/java-11-openjdk-amd64/lib/security/cacerts -noprompt -trustcacerts -importcert -alias repo.maven.apache.org -file /build/keystorage/repo.maven.apache.org.crt -storepass $DEFAULT_KEYTOOL_STORE_PASS

ADD ./keystorage/repo1.maven.org.fake.crt /usr/share/ca-certificates/repo1.maven.org.fake.crt
RUN chmod 644 /usr/share/ca-certificates/repo1.maven.org.fake.crt && update-ca-certificates
RUN keytool -keystore /usr/lib/jvm/java-11-openjdk-amd64/lib/security/cacerts -noprompt -trustcacerts -importcert -alias repo1.maven.org.fake -file /build/keystorage/repo1.maven.org.fake.crt -storepass $DEFAULT_KEYTOOL_STORE_PASS

RUN keytool -keystore /usr/lib/jvm/java-11-openjdk-amd64/lib/security/cacerts -storepasswd -storepass changeit -new $KEYTOOL_STORE_PASS

ENV SBT_VERSION=1.4.9

# only for debug inside container ping/ifconfig/ssl test:

# RUN apt install iputils-ping -y
# RUN apt install net-tools
# RUN wget https://confluence.atlassian.com/kb/files/779355358/779355357/1/1441897666313/SSLPoke.class

# tool to debugging SSL connection - usage `java SSLPoke repo1.maven.org 443`

# only for remote code debug:

# ENV JAVA_TOOL_OPTIONS=-agentlib:jdwp=transport=dt_socket,address=0.0.0.0:5005,server=y,suspend=n
# EXPOSE 5005

# And then add -p 5005:5005 while launching the container


RUN ./warmup.sh
