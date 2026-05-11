# Multi-stage build that produces four runtime images out of one reactor build:
#   target: app-runtime          → orinuno-app (Playwright + Chromium for HTML drift / live scrape)
#   target: source-kodik-runtime → orinuno-source-kodik (slim JRE — no browser deps)
#   target: source-jutsu-runtime → orinuno-source-jutsu (slim JRE — no browser deps)
#   target: meter-runtime        → meter OSS catalog collector (slim JRE)
#
# Reactor layout (see pom.xml `<modules>`):
#   orinuno-source-contract        — sealed SourceCatalogEvent contract
#   kodik-sdk                      — Spring-free Kodik HTTP/decoder/token SDK
#   kodik-sdk-spring-boot-starter  — auto-config glue for kodik-sdk
#   jutsu-sdk                      — jut.su parser SDK
#   sibnet-sdk / aniboom-sdk       — decoder-only source SDKs
#   orinuno-app                    — public API gateway / monolith host
#   orinuno-source-kodik           — standalone Kodik deployable (ADR 0018 Phase 2)
#   orinuno-source-jutsu           — standalone jut.su deployable (ADR 0019 Phase 4)
#   meter                          — OSS catalog collector (ADR 0018 Phase 5)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
COPY orinuno-source-contract/pom.xml orinuno-source-contract/pom.xml
COPY kodik-sdk/pom.xml kodik-sdk/pom.xml
COPY kodik-sdk-spring-boot-starter/pom.xml kodik-sdk-spring-boot-starter/pom.xml
COPY jutsu-sdk/pom.xml jutsu-sdk/pom.xml
COPY sibnet-sdk/pom.xml sibnet-sdk/pom.xml
COPY aniboom-sdk/pom.xml aniboom-sdk/pom.xml
COPY orinuno-app/pom.xml orinuno-app/pom.xml
COPY orinuno-source-kodik/pom.xml orinuno-source-kodik/pom.xml
COPY orinuno-source-jutsu/pom.xml orinuno-source-jutsu/pom.xml
COPY meter/pom.xml meter/pom.xml
RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY orinuno-source-contract/src orinuno-source-contract/src
COPY kodik-sdk/src kodik-sdk/src
COPY kodik-sdk-spring-boot-starter/src kodik-sdk-spring-boot-starter/src
COPY jutsu-sdk/src jutsu-sdk/src
COPY sibnet-sdk/src sibnet-sdk/src
COPY aniboom-sdk/src aniboom-sdk/src
COPY orinuno-app/src orinuno-app/src
COPY orinuno-app/spotbugs-exclude.xml orinuno-app/spotbugs-exclude.xml
COPY orinuno-source-kodik/src orinuno-source-kodik/src
COPY orinuno-source-jutsu/src orinuno-source-jutsu/src
COPY meter/src meter/src
RUN mvn -B -q -DskipTests package


# ─── orinuno-app runtime ───────────────────────────────────────────────────
# Optional Playwright + Chromium for the jut.su live-fallback / HTML drift
# paths. Toggle via build-arg INSTALL_PLAYWRIGHT=true (default false to keep
# image cold-build fast for the demo / smoke flows that don't exercise the
# live scrape path).
FROM eclipse-temurin:21-jre AS app-runtime
ARG INSTALL_PLAYWRIGHT=false
WORKDIR /app

RUN if [ "$INSTALL_PLAYWRIGHT" = "true" ]; then \
        apt-get update && apt-get install -y --no-install-recommends nodejs npm \
        && npm i -g playwright@1.58.0 \
        && npx playwright install chromium --with-deps \
        && apt-get remove -y nodejs npm \
        && apt-get autoremove -y \
        && rm -rf /var/lib/apt/lists/* /root/.npm /tmp/*; \
    fi

COPY --from=build /app/orinuno-app/target/orinuno.jar app.jar

ENV PLAYWRIGHT_BROWSERS_PATH=/root/.cache/ms-playwright

EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "app.jar"]


# ─── orinuno-source-kodik runtime ──────────────────────────────────────────
# Pure REST / decoder service — no browser, no Node. Strictly smaller image
# so the standalone OSS deployable stays lightweight.
FROM eclipse-temurin:21-jre AS source-kodik-runtime
WORKDIR /app

COPY --from=build /app/orinuno-source-kodik/target/orinuno-source-kodik.jar app.jar

EXPOSE 8086 8087
ENTRYPOINT ["java", "-jar", "app.jar"]


# ─── orinuno-source-jutsu runtime ──────────────────────────────────────────
# Pure REST + sync workers + live-fallback. ADR 0019 originally called the
# fallback path "Playwright live-fallback" but the actual JutsuLiveFallbackService
# is pure SDK reactive client — no Chromium runtime needed. Slim JRE.
FROM eclipse-temurin:21-jre AS source-jutsu-runtime
WORKDIR /app

COPY --from=build /app/orinuno-source-jutsu/target/orinuno-source-jutsu.jar app.jar

EXPOSE 8086 8087
ENTRYPOINT ["java", "-jar", "app.jar"]


# ─── meter runtime ─────────────────────────────────────────────────────────
# OSS catalog collector (ADR 0018 Phase 5). Slim JRE — no public REST surface
# yet, only actuator + the upcoming catalog write-path.
FROM eclipse-temurin:21-jre AS meter-runtime
WORKDIR /app

COPY --from=build /app/meter/target/meter.jar app.jar

EXPOSE 8089 8090
ENTRYPOINT ["java", "-jar", "app.jar"]
