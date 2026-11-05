FROM --platform=$BUILDPLATFORM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src/ src/
RUN mvn --batch-mode --no-transfer-progress verify

FROM icr.io/appcafe/open-liberty:26.0.0.9-full-java17-openj9-ubi-minimal
COPY --chown=1001:0 src/main/liberty/config/server.xml /config/server.xml
COPY --chown=1001:0 deploy/container.xml /config/configDropins/overrides/container.xml
COPY --from=build --chown=1001:0 /build/target/osm-mtb-trailzustand.war /config/apps/
