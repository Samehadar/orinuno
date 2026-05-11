FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Multi-module reactor (see pom.xml `<modules>`):
#   orinuno-source-contract — sealed event contract shared with consumers
#   kodik-sdk-drift          — Kodik schema-drift detector
#   jutsu-sdk                — jut.su parser SDK
#   sibnet-sdk / aniboom-sdk — decoder-only source SDKs
#   orinuno-app              — the Spring Boot service
COPY pom.xml .
COPY orinuno-source-contract/pom.xml orinuno-source-contract/pom.xml
COPY kodik-sdk-drift/pom.xml kodik-sdk-drift/pom.xml
COPY jutsu-sdk/pom.xml jutsu-sdk/pom.xml
COPY sibnet-sdk/pom.xml sibnet-sdk/pom.xml
COPY aniboom-sdk/pom.xml aniboom-sdk/pom.xml
COPY orinuno-app/pom.xml orinuno-app/pom.xml
RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY orinuno-source-contract/src orinuno-source-contract/src
COPY kodik-sdk-drift/src kodik-sdk-drift/src
COPY jutsu-sdk/src jutsu-sdk/src
COPY sibnet-sdk/src sibnet-sdk/src
COPY aniboom-sdk/src aniboom-sdk/src
COPY orinuno-app/src orinuno-app/src
COPY orinuno-app/spotbugs-exclude.xml orinuno-app/spotbugs-exclude.xml
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    nodejs npm \
    && npm i -g playwright@1.58.0 \
    && npx playwright install chromium --with-deps \
    && apt-get remove -y nodejs npm \
    && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/* /root/.npm /tmp/*

COPY --from=build /app/orinuno-app/target/orinuno.jar app.jar

ENV PLAYWRIGHT_BROWSERS_PATH=/root/.cache/ms-playwright

EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
