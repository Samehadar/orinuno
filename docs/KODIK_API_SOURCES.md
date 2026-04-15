# Kodik API — источники и локальные копии

Официальной HTML-документации на `https://kodik-api.com` в открытом виде нет: без валидного `token` эндпоинты часто отвечают JSON с ошибкой. Ниже — **неофициальные**, но полезные материалы и зеркала в репозитории.

## Локальные копии (vendored)

| Файл | Описание |
|------|----------|
| [vendor/kodik-api-animeparsers.md](vendor/kodik-api-animeparsers.md) | Подробное описание эндпоинтов `/search`, `/list`, `/translations`, параметров и примеров (сообщество; в шапке файла — ссылка на оригинал). |
| [vendor/kodik-api-rust-readme.md](vendor/kodik-api-rust-readme.md) | README async Rust-обёртки над Kodik API (модули search/list и др.). |

Даты снимка копий указаны в HTML-комментариях в начале каждого файла.

## Первичные ссылки (обновлять при необходимости)

| Ресурс | URL |
|--------|-----|
| Базовый хост API | https://kodik-api.com |
| AnimeParsers — KODIK_API.md (GitHub) | https://github.com/YaNesyTortiK/AnimeParsers/blob/main/KODIK_API.md |
| То же (raw) | https://raw.githubusercontent.com/YaNesyTortiK/AnimeParsers/main/KODIK_API.md |
| Rust crate `kodik-api` — репозиторий | https://github.com/negezor/kodik-api-rust |
| README (raw) | https://raw.githubusercontent.com/negezor/kodik-api-rust/master/README.md |
| docs.rs — сгенерированная дока крейта | https://docs.rs/kodik-api/latest/kodik_api/ |
| crates.io | https://crates.io/crates/kodik-api |

## Замечание

Материалы в `vendor/` **не являются** официальной позицией Kodik и могут устареть. Для критичных интеграций сверяйтесь с поведением живого API и при необходимости обновляйте копии.
