# Catalogue server image. Build the distribution first: ./gradlew :server:installDist
FROM eclipse-temurin:21-jre
RUN useradd --system --home /srv/trimio trimio && mkdir -p /srv/trimio/data && chown trimio /srv/trimio/data
COPY build/install/server /opt/trimio-server
USER trimio
WORKDIR /srv/trimio
ENV TRIMIO_DATA_DIR=/srv/trimio/data PORT=8080
EXPOSE 8080
VOLUME ["/srv/trimio/data"]
ENTRYPOINT ["/opt/trimio-server/bin/server"]
