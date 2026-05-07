FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Multi-module reactor: kodik-sdk-drift + jutsu-sdk + sibnet-sdk + aniboom-sdk + orinuno-app
# (SDK-SPLIT 2026-05-03 expanded the reactor; ADR 0016 P1a kept the same module list).
COPY pom.xml .
COPY kodik-sdk-drift/pom.xml kodik-sdk-drift/pom.xml
COPY jutsu-sdk/pom.xml jutsu-sdk/pom.xml
COPY sibnet-sdk/pom.xml sibnet-sdk/pom.xml
COPY aniboom-sdk/pom.xml aniboom-sdk/pom.xml
COPY orinuno-app/pom.xml orinuno-app/pom.xml
RUN mvn -B -q dependency:go-offline -DskipTests || true

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
