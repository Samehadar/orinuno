FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    nodejs npm \
    && npm i -g playwright@1.58.0 \
    && npx playwright install chromium --with-deps \
    && apt-get remove -y nodejs npm \
    && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/* /root/.npm /tmp/*

COPY --from=build /app/target/orinuno.jar app.jar

ENV PLAYWRIGHT_BROWSERS_PATH=/root/.cache/ms-playwright

EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
