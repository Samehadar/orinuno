FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# PR3 multi-module layout: kodik-sdk-drift + orinuno-app under a reactor pom.
COPY pom.xml .
COPY kodik-sdk-drift/pom.xml kodik-sdk-drift/pom.xml
COPY orinuno-app/pom.xml orinuno-app/pom.xml
RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY kodik-sdk-drift/src kodik-sdk-drift/src
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
