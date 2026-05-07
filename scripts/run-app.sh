#!/usr/bin/env bash
# Local-dev launcher: load .env, force jut.su live-fallback ON, point at the
# docker-compose MySQL on localhost:3316.
set -euo pipefail
cd "$(dirname "$0")/.."
set -a
. ./.env
set +a
exec java -jar orinuno-app/target/orinuno.jar \
  --spring.datasource.url='jdbc:mysql://localhost:3316/orinuno?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC' \
  --spring.datasource.username=root \
  --spring.datasource.password=root \
  --orinuno.kodik.token="${KODIK_TOKEN}" \
  --orinuno.providers.jutsu.username="${JUTSU_USERNAME}" \
  --orinuno.providers.jutsu.password="${JUTSU_PASSWORD}" \
  --orinuno.jutsu.live-fallback.enabled=true \
  --orinuno.jutsu.live-fallback.rate-limit.requests-per-second=1.0 \
  --orinuno.jutsu.sync.full-crawl-initial-delay-ms=${ORINUNO_JUTSU_FULL_CRAWL_DELAY_MS:-60000}
