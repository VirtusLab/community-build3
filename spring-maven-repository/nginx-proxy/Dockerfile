FROM nginx

ARG MAVEN_CERT_PASS

# copy certificates and keys
COPY keystore/repo1.maven.org.fake.crt /etc/nginx/repo1.maven.org.fake.crt
COPY keystore/repo1.maven.org.fake.key /etc/nginx/repo1.maven.org.fake.key

COPY keystore/repo1.maven.org.crt /etc/nginx/repo1.maven.org.crt
COPY keystore/repo1.maven.org.key /etc/nginx/repo1.maven.org.key

COPY keystore/repo.maven.apache.org.crt /etc/nginx/repo.maven.apache.org.crt
COPY keystore/repo.maven.apache.org.key /etc/nginx/repo.maven.apache.org.key

RUN echo "$MAVEN_CERT_PASS" > /etc/nginx/cert_password

# copy nginx configuration
COPY nginx.conf /etc/nginx/nginx.conf

EXPOSE 443
