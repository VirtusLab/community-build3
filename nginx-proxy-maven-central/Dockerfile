FROM nginx

COPY ./keystore/repo1.maven.org.fake.crt /etc/nginx/repo1.maven.org.fake.crt
COPY ./keystore/repo1.maven.org.fake.key /etc/nginx/repo1.maven.org.fake.key
COPY ./keystore/cert_password /etc/nginx/cert_password

COPY ./nginx.conf /etc/nginx/nginx.conf

EXPOSE 443
