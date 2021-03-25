# # base-dotty image
FROM lampepfl/dotty:2020-11-19

RUN mkdir -p /build
WORKDIR /build
COPY . /build/

RUN apt update
RUN apt install --only-upgrade openjdk-8-jdk-headless openjdk-8-jre-headless -y

# only for debug inside container ping/ifconfig/ssl test
RUN apt install iputils-ping -y
RUN apt install net-tools
RUN wget https://confluence.atlassian.com/kb/files/779355358/779355357/1/1441897666313/SSLPoke.class
# tool to debugging SSL connection - usage `java SSLPoke repo1.maven.org 443`

# only for remote code debug
ENV JAVA_TOOL_OPTIONS=-agentlib:jdwp=transport=dt_socket,address=0.0.0.0:5005,server=y,suspend=n
EXPOSE 5005


ADD ./keystorage/mvnProxy.crt /usr/share/ca-certificates/mvnProxy.crt
RUN chmod 644 /usr/share/ca-certificates/mvnProxy.crt && update-ca-certificates
RUN keytool -keystore /usr/lib/jvm/java-8-openjdk-amd64/jre/lib/security/cacerts -noprompt -trustcacerts -importcert -alias mvnProxy -file /build/keystorage/mvnProxy.crt -storepass changeit

# CMD ["sbt","compile"]
ENTRYPOINT ["bash"]
