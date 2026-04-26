# orinuno — Бэклог, идеи и технический долг

> Дата создания: 2026-04-16
> Контекст: по результатам сравнительного анализа с проектами **kodik-api** (Rust), **kodikwrapper** (TypeScript), **AnimeParsers** (Python) и аудита текущего состояния.

---

## Оглавление

- [1. Технический долг (существующий)](#1-технический-долг-существующий)
- [2. Отставания от конкурирующих проектов](#2-отставания-от-конкурирующих-проектов)
- [3. Идеи для реализации](#3-идеи-для-реализации)
- [4. Schema Drift — обнаруженные расхождения](#4-schema-drift--обнаруженные-расхождения)
- [5. Защитные механизмы Kodik (знание для будущей работы)](#5-защитные-механизмы-kodik-знание-для-будущей-работы)
- [6. Конкурентный анализ — сводная таблица](#6-конкурентный-анализ--сводная-таблица)

---

## 1. Технический долг (существующий)

### TD-1: Async Jobs для долгих операций декодирования — **DONE (Phase 2, 2026-04-26)**

**Статус:** Реализовано в Phase 2.
**Файлы:** `ParseRequestController.java`, `ParseRequestService.java`, `ParseRequestQueueService.java`, `RequestWorker.java`, `RequestHashService.java`, `ThrottledProgressReporter.java`, миграция `20260426010000_create_orinuno_parse_request.sql`.

Что сделано:
- `POST /api/v1/parse/requests` возвращает 201 Created (или 200 OK при идемпотентном повторе) c `id` сразу после insert.
- `GET /api/v1/parse/requests/{id}` отдаёт `ParseRequestDtoView` со `status`, гранулярным `phase` (`QUEUED → SEARCHING → DECODING → DONE/FAILED`) и счётчиками `progress_decoded/total`. Список + `X-Total-Count` для backpressure (`?status=PENDING&limit=0`).
- Идемпотентность по SHA-256 над canonical-JSON — повторный сабмит того же payload в активной фазе возвращает существующую строку.
- `RequestWorker` — `@Scheduled(2s)`, `SELECT … FOR UPDATE SKIP LOCKED` через отдельный `ParseRequestQueueService` (чтобы Spring `@Transactional` отрабатывал, а не self-invocation бипасил прокси).
- `recoverStale` (`@Scheduled(60s)`) переводит залипшие RUNNING → PENDING (или FAILED при `retry_count >= max-retries`) на основе `last_heartbeat_at`.
- Полные правила и SLA: [`docs-site/architecture/parse-requests`](docs-site/src/content/docs/architecture/parse-requests.md), `ARCHITECTURE.md` §7.

Текущая модель — один worker × один поток × один инстанс. Когда / если упрёмся в throughput, см. **TECH_DEBT.md → TD-PR-1** (worker pool, fully reactive boundary).

### TD-1a: openapi.json snapshot — пересобрать после Phase 2 — **DONE** (2026-04-26)

**Статус:** Реализовано. `docs-site/openapi.json` пересобран живым curl'ом с
`http://localhost:8085/v3/api-docs` после Phase 2. Snapshot теперь содержит
`/api/v1/parse/requests`, `/api/v1/parse/requests/{id}`,
`/api/v1/parse/decode/variant/{variantId}`, `/api/v1/kodik/list` плюс
обновлённые схемы для `/api/v1/export/*` (поля `ongoing`, `lastSeason`,
`lastEpisode`, `episodesCount`, `*Status`).

### TD-2: ParseRequestDto валидация

**Приоритет:** Низкий
**Статус:** Не реализовано
**Файлы:** `ParseRequestDto.java`, `ParseController.java`

`ParseRequestDto` принимает `@Valid`, но не имеет Bean Validation constraints. Пустой запрос вызовет API Kodik без критериев поиска.

**Решение:** Добавить `@AssertTrue` или кастомный валидатор, требующий хотя бы одно поле поиска.

### TD-3: Schema Drift Detection — расширение до Level 2

**Приоритет:** Средний
**Статус:** Level 1 реализован (in-memory хранение + REST endpoint + UI)
**Файлы:** `KodikResponseMapper.java`, `HealthController.java`, `HealthView.vue`

Текущая реализация хранит дрифты in-memory — при перезапуске данные теряются.

**Решение (Level 2):**
- Персистентное хранение дрифтов в БД (`kodik_schema_drift` таблица)
- Webhook/Telegram уведомления при обнаружении нового дрифта
- Автоматическое создание GitHub Issue через API

**Решение (Level 3):**
- Сравнение snapshots схемы между деплоями
- Автогенерация diff-отчётов

### TD-3a: Schema Drift coverage — Commits A–D (DONE 2026-04-25)

**Статус:** Реализовано.
**Файлы:** `com.orinuno.drift.*`, `KodikResponseMapper`, `KodikApiClient`, `KodikTokenValidator`, `KodikMaterialDataDto`.

**Что сделано:**
- Вынесен generic пакет `com.orinuno.drift` (`DriftDetector`, `DriftRecord`, `DtoFieldExtractor`, `DriftSamplingProperties`, `ItemSamplingMode`). `KodikResponseMapper` теперь — тонкий фасад поверх него; весь state дрифтов живёт в одном месте.
- Конфигурируемый sampling первых элементов массива: `orinuno.drift.enabled`, `orinuno.drift.item-sampling.mode` (`NONE` / `FIRST_N` / `ALL`), `orinuno.drift.item-sampling.limit`. Дефолт — `FIRST_N=10`.
- `KodikApiClient.listRaw` подаёт каждую страницу (включая `next_page` в `listAll`) в дрифт-детектор.
- `KodikTokenValidator.probe()` сэмплирует успешные probe-ответы (`/search` по title/id, `/list`). Intentional coupling с runtime-трафиком задокументирован в коде — вынесение в отдельный детектор отложено до TrafficAnalyzer (см. IDEA-DRIFT-3).
- `KodikMaterialDataDto` стал single source of truth для полей `material_data`; `KodikResponseMapper` сэмплирует `material_data` в первых N результатах под лейблом `MaterialData[<content-type>]`. `KodikApiStabilityTest` переключён с inline `Set<String>` на `DtoFieldExtractor.knownJsonFields(KodikMaterialDataDto.class)`.
- Каждый API-hit увеличивает `hitCount` на 1 (не per-item), чтобы мониторинг показывал «сколько HTTP-ответов дрейфовало», а не «сколько элементов в ответе».
- Покрытие тестами: `DriftDetectorTest`, `DtoFieldExtractorTest`, `KodikMaterialDataDtoTest`, `KodikApiClientListRawDriftTest`, `KodikTokenValidatorDriftSamplingTest`, расширенный `KodikResponseMapperTest`. `mvn verify` — 176 тестов, 0 failures.

### IDEA-DRIFT-1: `missingFields` detection

**Приоритет:** Низкий
**Сложность:** Средняя
**Зависимости:** TrafficAnalyzer (IDEA-DRIFT-3).

Kodik легитимно опускает пустые поля — наивная проверка «DTO знает поле X, ответ его не прислал» даст шум. Нужен статистический подход: если поле X стабильно приходило N последних дней и пропало для контент-типа T, поднять сигнал. Без сэмплинга по времени это нельзя сделать осмысленно, поэтому сначала — TrafficAnalyzer.

### IDEA-DRIFT-2: Persistent drift history

**Приоритет:** Низкий
**Сложность:** Низкая

Сейчас `DriftDetector` держит state в `ConcurrentHashMap` — при рестарте теряется. Дублирует TD-3 Level 2, но в более узкой формулировке: перед тем как ставить алертинг, нужен хотя бы журнал последних N дрифтов на диске (append-only JSONL) или в `orinuno_schema_drift` таблице. Это базис для сравнения «что было до деплоя» vs «что после».

### IDEA-DRIFT-3: TrafficAnalyzer service

**Приоритет:** Средний (когда появится второй парсер)
**Сложность:** Высокая

Текущий `DriftDetector` — специфическая реализация под одну цель (обнаружить unknown keys в JSON). Из ProblemFrames-анализа видно, что это частный случай более общей задачи: «анализировать исходящий HTTP-трафик, извлекать сигналы о здоровье интеграции». Следующие темы должны войти в один сервис, а не плодить дубли:
- Schema drift (unknown / missing fields).
- Baseline skew для статуса (например, Kodik начал отдавать 502 чаще).
- Latency histograms по эндпоинту.
- Разделение probe / production трафика (сейчас склеено через shared `DriftDetector`).
- Snapshot schema между деплоями (TD-3 Level 3).

Триггер — когда появится Aniboom/JutSu/Sibnet парсер, дублировать «drift пакет для каждого источника» хуже, чем сразу выделить сервис и прокинуть в него все WebClient’ы через фильтр.

### TD-4: Reference endpoints возвращают только raw Map — **DONE** (2026-04-24)

**Статус:** Реализовано вместе с IDEA-1/2/3.

**Что сделано:**
- `KodikReferenceResponse<T>` + пять record-DTO (`KodikTranslationDto`, `KodikGenreDto`, `KodikCountryDto`, `KodikYearDto`, `KodikQualityDto`).
- Перегрузка `KodikResponseMapper.mapAndDetectChanges(Map, TypeReference<T>, Class<?> itemType)` с двухуровневой проверкой дрифта (envelope + items).
- Пять типизированных методов в `KodikApiClient` (`translations()`, `genres()`, `countries()`, `years()`, `qualities()`), вызываемых `ReferenceService` и `ReferenceController`.

### TD-5: PlaywrightVideoFetcher тесты нестабильны

**Приоритет:** Средний
**Статус:** Тесты падают
**Файлы:** `PlaywrightVideoFetcherTest.java`, `VideoDownloadLiveIntegrationTest.java`

`defaultPropertiesShouldBeSensible` и `downloadVideo` тесты падают. Необходимо исправить конфигурацию тестов Playwright.

---

## 2. Отставания от конкурирующих проектов

### 2.1 Относительно kodik-api (Rust crate)

| Возможность | kodik-api (Rust) | orinuno | Статус |
|---|---|---|---|
| Полное покрытие API параметров | 55+ параметров поиска | 55+ параметров | **Реализовано** |
| Строгая типизация ответов | Все поля типизированы через serde | `material_data` как `Map<String, Object>` | **Компромисс** — осознанный выбор для гибкости |
| Пагинация через `next_page` | Встроенная поддержка | `KodikListRequest.nextPageUrl` | **Реализовано** |
| Enum-ы для типов контента | `ContentType::Anime`, `AnimeSerial` и т.д. | Строки в `KodikSearchRequest.types` | **Gap** |
| Типизированные фильтры рейтинга | `KinopoiskRating("7-10")` | Строки | **Gap** |

**Что портировать:**

#### IDEA-RUST-1: Enum-ы для типов контента

**Приоритет:** Низкий
**Файлы:** Новый `KodikContentType.java` enum, `KodikSearchRequest.java`

Создать Java enum `KodikContentType` с вариантами: `ANIME`, `ANIME_SERIAL`, `FOREIGN_MOVIE`, `FOREIGN_SERIAL`, `RUSSIAN_MOVIE`, `RUSSIAN_SERIAL`, `CARTOON`, `CARTOON_SERIAL`, `DOCUMENTARY`, `DOCUMENTARY_SERIAL`. Маппить в строки `anime`, `anime-serial` и т.д. через `@JsonValue`. Позволит IDE autocomplete и compile-time проверку.

#### IDEA-RUST-2: Типизированные фильтры рейтинга

**Приоритет:** Низкий

Вместо `String kinopoiskRating` в `KodikSearchRequest` — объект `RatingFilter` с полями `min`, `max`, который сериализуется в строку `"7-10"`.

### 2.2 Относительно kodikwrapper (TypeScript)

| Возможность | kodikwrapper | orinuno | Статус |
|---|---|---|---|
| API client для всех endpoints | Полное покрытие | Полное покрытие | **Реализовано** |
| Декодирование видео-ссылок | Есть | Есть (8-step decode) | **Реализовано** |
| Dynamic video-info endpoint discovery | Автоматический парсинг JS для POST URL | Есть (`cachedVideoInfoPath`) | **Реализовано** |
| Brute-force ROT cipher | Пробует все 26 сдвигов | Есть (`bruteForceAllShifts`) | **Реализовано** |
| TypeScript строгая типизация | Полная | Java DTO + raw Map | **Реализовано** |
| Token auto-discovery | Извлекает токен из публичных страниц | Нет | **Gap** |

**Что портировать:**

#### IDEA-KW-1: Token auto-discovery — **DONE** (2026-04-24)

**Статус:** Реализовано в `com.orinuno.token.KodikTokenAutoDiscovery` + полная переработка управления токенами (см. ниже IDEA-KH-1 follow-up).

**Что сделано:**
- Скрейпинг `https://kodik-add.com/add-players.min.js?v=2` при первом запуске, если `KODIK_TOKEN` пуст и `orinuno.kodik.auto-discovery-enabled=true`.
- Найденный токен попадает в `legacy` тир (узкий scope), никогда не используется как primary.
- 4-тир registry (`stable` / `unstable` / `legacy` / `dead`) в `data/kodik_tokens.json` — копия поведения AnimeParsers.
- Per-function availability matrix + автодемоция при ошибке `{"error":"Отсутствует или неверный токен"}` с retry на следующий токен.
- Startup + scheduled валидация (3 probe'а на токен с 2-секундным spacing).
- `/api/v1/health/tokens` (маскированный) + Prometheus gauge `kodik_tokens_count{tier=...}`.
- Документация: `data/TOKENS.md`, `docs-site/src/content/docs/operations/kodik-tokens.md`.

#### IDEA-KH-1: Token harvester — follow-up

**Приоритет:** Низкий
**Сложность:** Средняя

AnimeParsers hardcoded набор токенов в репозитории. orinuno таких токенов не публикует, но можно добавить периодический «охотник», который:
- Переобходит публичные сайты с Kodik-плеером (shikimori, animego, и т.п.) и извлекает токены из iframe URL.
- Добавляет найденные значения в `unstable` тир после валидации.
- Автоматически наполняет пул, снижая зависимость от ручного ввода `KODIK_TOKEN`.

**Риск:** Юридически серый, структура внешних сайтов нестабильна. Поэтому отдельной задачей.

### 2.3 Относительно AnimeParsers (Python)

| Возможность | AnimeParsers | orinuno | Статус |
|---|---|---|---|
| Kodik API client (`/search`, `/list`, `/translations`) | ✅ Полный (`KodikSearch`, `KodikList`, `get_translations()`) | ✅ Полный | **Паритет** |
| Kodik video decode | Есть (базовый) | Полный (8-step + brute-force) | **Наше преимущество** |
| Aniboom parser | Есть | Нет | **Gap** |
| JutSu parser | Есть | Нет | **Gap** |
| Shikimori parser | Есть | Нет | **Gap** |
| HLS манифест | Абсолютизация URL | `HlsManifestService` | **Реализовано** |
| Token brute-force | Есть | Нет (есть brute-force для ROT shift) | **Не нужно** (у нас есть токен) |
| Async support | Есть (aiohttp) | Есть (WebFlux reactive) | **Реализовано** |
| Персистентное хранение | Нет (stateless library) | MySQL + Liquibase | **Наше преимущество** |
| REST API для потребителей | Нет | Полный (`/parse`, `/content`, `/export`, `/health`) | **Наше преимущество** |
| Schema drift detection | Нет | `KodikResponseMapper` + UI | **Наше преимущество** |
| Video download | Нет | Playwright + HLS + ffmpeg remux | **Наше преимущество** |

**Что портировать:**

#### IDEA-AP-1: Aniboom парсер

**Приоритет:** Средний
**Сложность:** Высокая
**Референс:** `AnimeParsers/src/anime_parsers_ru/parser_aniboom_async.py`

Aniboom (aniboom.one) — видеохостинг, альтернативный Kodik, часто используется на anime-сайтах. AnimeParsers парсит его через:
1. Fetch страницы серии
2. Regex для извлечения mpd/m3u8 URL
3. Работа с DASH/HLS манифестами

**Реализация:**
- Новый `AniboomService` в `com.orinuno.service`
- Новый `AniboomController` (`/api/v1/aniboom/...`)
- Возможно общий интерфейс `VideoHostParser` для абстракции
- Сохранение в ту же `kodik_episode_variant` таблицу (с `source` колонкой) или в отдельную таблицу

**Важно:** Aniboom меняет домены (aniboom.one → animejoy.ru и т.д.), нужна конфигурируемая base URL.

#### IDEA-AP-2: JutSu парсер

**Приоритет:** Средний
**Сложность:** Средняя
**Референс:** `AnimeParsers/src/anime_parsers_ru/parser_jutsu.py`

JutSu (jut.su) — популярный anime-хостинг с прямыми mp4 ссылками. Проще Kodik, т.к. не использует шифрование.

**Алгоритм из AnimeParsers:**
1. GET запрос к странице серии
2. Regex для извлечения прямых mp4 URL из `<source src="...">` тегов
3. URL доступны в нескольких качествах (360p, 480p, 720p, 1080p)

**Реализация:** Новый `JutsuService`, `JutsuController`.

#### IDEA-AP-3: Shikimori интеграция

**Приоритет:** Высокий (полезно для backend-master)
**Сложность:** Средняя
**Референс:** `AnimeParsers/src/anime_parsers_ru/parser_shikimori_async.py`

Shikimori (shikimori.one) — основная anime-база данных. AnimeParsers использует Shikimori для:
1. Поиск аниме по названию → получение shikimori_id
2. Получение списка плееров (Kodik, Aniboom, etc.) для конкретного аниме
3. Извлечение iframe URL плееров

Для orinuno это интересно как **источник обнаружения контента** — вместо ручного поиска по title через Kodik API, можно автоматически находить весь аниме-каталог Shikimori и для каждого тайтла находить плееры.

**Реализация:**
- `ShikimoriClient` для GraphQL/REST API Shikimori
- `ShikimoriDiscoveryService` — автообнаружение новых тайтлов
- Scheduled task для периодического сканирования

#### IDEA-AP-4: Sibnet парсер (video.sibnet.ru)

**Приоритет:** Высокий
**Сложность:** Средняя
**URL:** `https://video.sibnet.ru/rub/anime/`

Sibnet — крупнейший сибирский видеохостинг с **~4.9 миллионов роликов**, из которых аниме-раздел содержит **5000+ видео** (100+ страниц × 50 видео). Потенциально одна из крупнейших баз аниме с русской озвучкой, возможно превосходящая Kodik по объёму.

**Ключевые особенности Sibnet:**
- **Прямой видеохостинг** — видео загружены напрямую, не через iframe-плеер (как Kodik). Это упрощает парсинг — не нужна 8-шаговая декодировка
- **Альбомы** — контент организован в альбомы по сериалам (напр. `alb710331` = "Ты и я — полные противоположности"), что упрощает группировку эпизодов
- **Множество озвучек** — загрузчики: AniDub, AniBaza, StudioMir, FRT Sora, MedusaSub, Amazing Dubbing и другие фансаб-студии
- **Пагинация** — `&page=N`, сортировка по дате/рейтингу/просмотрам/комментариям
- **Метаданные** — название (RU + оригинал), автор (загрузчик), альбом, длительность, количество просмотров
- **URL-структура**: `video.sibnet.ru/rub/anime/video{id}-{slug}/` — числовой ID + slug

**Структура данных на странице (из анализа HTML):**
```
Видео: "Золотое божество (5 сезон): Финал / Golden Kamuy - Saishuushou [04]"
  ID:         6112832
  Длительность: 23:40
  Автор:      AniBaza
  Альбом:     "Золотое божество (5 сезон): Финал" (alb710333)
  Просмотры:  9
```

**Что нужно исследовать перед реализацией:**
1. **Есть ли API?** — Sibnet может иметь внутренний API (RSS, JSON). Нужно проверить network traffic при загрузке страниц
2. **Формат видео** — прямой MP4 или HLS? Есть ли CDN protection аналогичная Kodik?
3. **Rate limiting** — какие ограничения на scraping
4. **Правовой аспект** — условия использования Sibnet для автоматического парсинга
5. **Сопоставление контента** — как матчить sibnet-видео с kodik-контентом (по названию? по shikimori_id?)

**Предварительная архитектура:**
- `SibnetScraperService` — HTML-парсинг каталога и страниц видео (Jsoup или Playwright)
- `SibnetVideoExtractor` — извлечение прямых ссылок на видео
- `SibnetAlbumMapper` — маппинг альбомов Sibnet → сериалы в нашей БД
- `SibnetController` (`/api/v1/sibnet/...`)
- Новая таблица `sibnet_video` или расширение `kodik_episode_variant` колонкой `source`
- Scheduled task для периодического обновления каталога

**Преимущества Sibnet перед Kodik:**
- Контент загружен пользователями напрямую → потенциально проще получить прямые ссылки
- Больше уникального контента от фансаб-студий
- Нет сложной обфускации видео-URL

**Риски:**
- Нестабильная структура HTML (нет официального API)
- Контент может удаляться по DMCA
- Sibnet — региональная платформа, может ограничивать доступ по GeoIP

---

## 3. Идеи для реализации

### PHASE-2: Async parse-requests + Kodik /list proxy + ContentExportDto v2 — **DONE** (2026-04-26)

**Статус:** Реализовано полным ходом, см. TD-1 выше и `ARCHITECTURE.md` §7.

Что вошло в Phase 2:

1. **Async request log** — таблица `orinuno_parse_request`, контроллер `/api/v1/parse/requests`, `RequestWorker`, идемпотентность по SHA-256 канонического JSON, `phase` enum (QUEUED → SEARCHING → DECODING → DONE/FAILED), throttled progress + `recoverStale`.
2. **Kodik /list proxy** — `GET /api/v1/kodik/list` (минимальный `KodikListItemView`, `next_page` в `KodikListPageView.nextPage`). Schema-drift в этом эндпоинте бьёт `Warning: 199 …` хедером, чтобы parser-kodik увидел drift, не получая полный сырой респонс.
3. **API-key auth** — `ApiKeyAuthFilter` теперь покрывает `/api/v1/parse/requests` и `/api/v1/kodik` префиксы.
4. **ContentExportDto v2** — добавлены `lastSeason`, `lastEpisode`, `episodesCount`, `animeStatus`, `dramaStatus`, `allStatus` и derived `ongoing`. Используются parser-kodik’ом для приоритизации онгоингов и выбора между gap-fill / fresh re-parse.

Связанные пункты бэклога, которые **остаются** PENDING после Phase 2:

- **PHASE-2-A: gap-fill из meter-api catalog** — заблокирован на стороне `backend-master/meter-api`: нужен эндпоинт `/catalog/missing-by-source?source=KODIK` для выдачи списка `kinopoiskId/shikimoriId/imdbId`, которых ещё нет в каталоге, отсортированных по приоритету. Без него parser-kodik discovery работает только в режиме «full Kodik /list pagination → submit на всё». Передать запрос в backend-master.
- **PHASE-2-B: операторский UI приоритетного добавления** — отложено до того, как операторский UI orinuno вырастет до уровня, на котором имеет смысл вводить ручные «срочные» сабмиты (`POST /api/v1/parse/requests` с приоритетом). Сейчас demo-сайт прямо ходит через `/parse/search`, для приоритетной очереди нужен отдельный экран.

### IDEA-1: REST endpoint для reference данных — **DONE** (2026-04-24)

**Статус:** Реализовано. `ReferenceController` публикует пять эндпоинтов: `GET /api/v1/reference/translations|genres|countries|years|qualities`, каждый с параметром `?fresh=true` для обхода кэша. Demo UI (`ReferenceView.vue`) предоставляет интерфейс для всех пяти словарей с фильтрацией и переключателем cache/fresh.

### IDEA-2: Кэширование reference данных — **DONE** (2026-04-24)

**Статус:** Реализовано через Caffeine. Переключатель `orinuno.cache.reference.enabled` (default `true`) меняет `CacheManager` на `NoOpCacheManager` при отключении. TTL настраивается через `orinuno.cache.reference.ttl-seconds` (default 6h). `fresh=true` на контроллере обходит кэш per-request. Тесты: `ReferenceCacheIntegrationTest` (enabled/disabled), `ReferenceControllerTest` (fresh-флаг).

### IDEA-3: Типизированные DTO для reference endpoints — **DONE** (2026-04-24)

**Статус:** Реализовано. Пять record-DTO в пакете `com.orinuno.client.dto.reference` + generic `KodikReferenceResponse<T>`. Маппятся через `KodikResponseMapper.mapAndDetectChanges(Map, TypeReference<T>, Class<?>)` с двухуровневой проверкой дрифта. Покрыто юнит- и live stability тестами.

### IDEA-4: Полный аудит `material_data` → сохранение в БД — **DONE** (2026-04-24)

**Статус:** Реализовано в коммите `179cc8d`. `material_data` сериализуется в JSON-колонку `kodik_content.material_data` через Liquibase migration `20260424-material-data.xml`; mapping — в `KodikContentMapper`. Доступно на export для backend-master.

### IDEA-5: Полная автопагинация `/list` — **DONE** (2026-04-24)

**Статус:** Реализовано. `KodikApiClient.listAll(KodikListRequest)` возвращает `Flux<Map<String, Object>>`, обходя `next_page` через `Flux.expand` и плоско эмитируя items. Поведение покрыто `KodikApiClientListAllTest` (три страницы, одна страница, пустой первый ответ, пустой next_page, ошибка на второй странице).

### IDEA-DOWNLOAD-FASTPATH: Fast-path в `VideoDownloadService` — **DONE** (2026-04-25)

**Статус:** Реализовано. `VideoDownloadService.downloadWithStrategy` проверяет `variant.mp4Link` и, если он уже раскодирован и начинается с `http`, идёт сразу на CDN через `downloadFromCdn(...)` — без запуска Playwright. При 403/404 от CDN (истёкшая ссылка) происходит прозрачный fallback на `downloadViaWebClient`. Типичное время до первого байта сократилось с ~37 секунд (при таймауте Playwright) до ~2 секунд.

### IDEA-DOWNLOAD-PROGRESS: Byte-level progress для WebClient fallback — **DONE** (2026-04-25)

**Статус:** Реализовано. `DownloadState` обогащён полем `expectedTotalBytes`, `DownloadProgress` отслеживает `expectedTotalBytes` (из заголовка `Content-Length` финального 2xx-ответа) и инкрементирует `totalBytes` на каждый `DataBuffer` при записи. Demo UI (`ContentDetailView.vue`) показывает три режима прогресса (`segments`, `bytes`, `indeterminate`) плюс `phaseHint` (`Browser handshake`, `Direct MP4 (CDN)`, `Playwright timed out — falling back to direct MP4`) и `elapsed` таймер во всех режимах.

### IDEA-DOWNLOAD-STEALTH: Playwright stealth shim — **DONE** (2026-04-25)

**Статус:** Реализовано в `PlaywrightVideoFetcher.newStealthContext()`: `BrowserContext` создаётся с реалистичным Chrome/135 UA, viewport 1280×720, locale `en-US`, timezone `Europe/London` и init-скриптом, который патчит `navigator.webdriver`, `navigator.languages`, `navigator.plugins`, `window.chrome` и `Notification.permission`. Покрывает базовые проверки anti-bot скриптов и стабилизирует поведение headless-Chromium.

Важно: stealth-shim **не решает** IP-based geo-blocking (проверено на KZ VPN — клики в плеере проходят, но XHR на видео не уходит). См. `IDEA-DOWNLOAD-PROXY` ниже.

### IDEA-DOWNLOAD-DECODEVARIANT: Single-variant decode endpoint — **DONE** (2026-04-25)

**Статус:** Реализовано. Добавлены `ParserService.decodeForVariant(Long variantId)` и `POST /api/v1/parse/decode/variant/{variantId}` (ответ: `{"variantId": Long, "decoded": Boolean}`). Demo UI переключён на новый эндпоинт для кнопки "Decode" рядом с одним variant — раньше он вызывал content-wide декод, который на крупных аниме-сериалах висит минутами.

### IDEA-DOWNLOAD-MP4LINK-SENTINEL: Фикс `mp4Link='true'` от geo-block — **DONE** (2026-04-25)

**Статус:** Реализовано. `KodikVideoDecoderService.parseVideoResponse` раньше клал синтетический ключ `_geo_blocked: "true"` в мапу качеств для geo-блокированного контента; `ParserService.selectBestQuality` радостно выбирал строку `"true"` как "лучшее качество", и в экспорте у backend-master появлялся `mp4Link: "true"`. Теперь декодер для geo-block возвращает пустую мапу (без sentinel'ов), а `selectBestQuality`, `VideoDownloadService.pickBestQualityUrl` и `StreamController.pickBestQuality` защитно пропускают ключи начинающиеся с `_` и значения, которые не начинаются с `http`. Liquibase-миграция `20260425010000_cleanup_invalid_mp4_link.sql` обнуляет пре-существующие битые записи на первом старте.

### IDEA-DOWNLOAD-PROXY: Playwright через прокси-пул

**Приоритет:** Средний
**Сложность:** Средняя
**Файлы:** `PlaywrightVideoFetcher.java`, `ProxyProviderService.java`, `kodik_proxy` таблица.

Kodik блокирует плеер по IP в части регионов (подтверждено для Казахстана). Stealth shim (`IDEA-DOWNLOAD-STEALTH`) с этим не справляется — достаточно заменить egress-адрес. В проекте уже есть `kodik_proxy` таблица и `ProxyProviderService`, которыми пользуется декодер. Нужно передать выбранный прокси в `browser.newContext(new Browser.NewContextOptions().setProxy(new Proxy("http://host:port")))`, чтобы все запросы внутри `BrowserContext` (iframes, /ftor, CDN) шли через тот же выход.

**Предусловия:**
- Добавить колонку `region` (ISO-код) в `kodik_proxy` и тэгировать существующие записи, иначе ротация случайно перекидывает на такие же заблокированные прокси.
- Дать `ProxyProviderService.pickForRegion(String exclude)` API — чтобы `PlaywrightVideoFetcher` мог попросить "что-то не из RU/KZ".
- Инвалидация прокси: если `BrowserContext` свежесозданный через прокси всё равно таймаутит, помечать прокси как `dead` с причиной `PLAYWRIGHT_TIMEOUT`.

**Что не делаем:** резидентные прокси, ротацию per-request. Достаточно per-context (один прокси на один download).

### E2E-PHASE0-FINDINGS: Реальные проблемы интеграции с parser-kodik

**Контекст:** 2026-04-25, поднимали parser-kodik из `backend-master/docker/docker-compose.yml` локально. Поллинг `/api/v1/export/ready` завёлся (`Fetched 3 items from sourcekodik`), DTO`KodikContentExportDto` мапятся в `ExportSerialRequest` корректно, mp4-ссылки из `materialData` доходят до `Episode.filepath`/`EpisodeVariant.filepath`. Но дальше пайплайн ломается на нескольких узких местах. Это **issues backend-master**, но они блокируют Phase 0 интеграции, поэтому фиксируем здесь, а параллельно создаём задачи в `backend-master/parser-kodik`.

#### E2E-1: parser-kodik не читает свой `application.yml` для storage/meter-api

**Где:** `parser-kodik/src/main/resources/application.yml` против `storage-spring-boot-starter` и `meter-api-spring-boot-starter`.

**Что нашли:**
- `application.yml` декларирует `storage.minio.{endpoint,access-key,secret-key,bucket}`, но `MinioStorageProperty` имеет `@ConfigurationProperties(prefix = "minio")` и требует обязательные `minio.url`, `minio.default-region`, `minio.default-bucket-name`, `minio.default-base-folder`, `minio.access-key`, `minio.access-secret`. Старт без них валится с `must not be blank`.
- `application.yml` использует `meter-api.url`, а `MeterApiProperty` ждёт `meter.base-url`. Тоже падает.

**Что значит:** конфигурация в `application.yml` **не используется**, parser-kodik фактически собирает настройки только из env с правильными именами (`MINIO_URL`, `METER_BASE_URL`, …). В prod-окружении это, видимо, маскируется централизованным ConfigMap, но при первом локальном подъёме сервис не стартует.

**Что делаем:**
- Создать issue в backend-master: переписать `parser-kodik/application.yml` на актуальные ключи стартеров (`minio.*`, `meter.base-url`).
- В `docs-site` orinuno добавить раздел "Локальный e2e с parser-kodik" с явным списком env (см. ниже в комментарии).

#### E2E-2: parser-kodik не выполняет миграции при старте

**Где:** `parser-kodik/pom.xml`, `parser-kodik/src/main/resources/application.yml`.

**Что нашли:** `pom.xml` содержит `liquibase-maven-plugin`, но **нет** runtime-зависимости `liquibase-core`. Spring Boot autoconfig для liquibase не активируется → `application.yml`-секция `spring.liquibase.change-log` бесполезна. На старте parser-kodik валится при первом INSERT/SELECT с `Table 'parser_kodik.kodik_export_state' doesn't exist`.

**Решение (для backend-master):** добавить `org.liquibase:liquibase-core` в `parser-kodik/pom.xml`. Тогда стартер автоматически отработает changelog при старте (как уже сделано в orinuno).

**Workaround:** перед запуском парсера руками гонять `./mvnw -pl parser-kodik liquibase:update`.

#### E2E-3: parser-kodik теряет state неудачных попыток экспорта

**Где:** `parser-kodik/.../KodikExportScheduler.java::processExportItem`.

**Что нашли:** при ошибке `meterApiService.exportContent(...).block()` (например, meter-api недоступен) парсер делает `exportStateRepository.updateStatus(kodikId, FAILED)` — `UPDATE kodik_export_state … WHERE kodik_content_id = ?`. Но если запись ещё не создана (а она создаётся только при успешном `upsert(...)` в happy-path), `UPDATE` ничего не меняет. В итоге:
- В таблице **нет ни одной записи** про content_id, который попадал в polling.
- Каждый цикл (раз в `KODIK_POLL_INTERVAL_MS`) пытается заново всё то же самое.
- Невозможно увидеть из БД, кого пытались экспортировать и сколько раз.

**Решение (для backend-master):**
- В `processExportItem` сначала `upsert(state with PENDING)` → потом `downloadPoster` → `meterApi.exportContent` → `upsert(state with EXPORTED)`. В catch — `upsert(state with FAILED, error_message)`.
- Завести retry-counter и `next_retry_at` для backoff (после 3 неудач — отложить на час).

#### E2E-4: poster берётся из `screenshots[0]`, что семантически неверно

**Где:** `parser-kodik/.../KodikExportScheduler.java::downloadPosterIfAvailable`.

**Что нашли:** `dto.screenshots()` в orinuno-DTO — это **превью кадров серии** (`https://i.kodikres.com/screenshots/seria/.../1.jpg`), а не **постер фильма** (kinopoisk URL). Использовать первый кадр первой серии как poster содержимого — плохо для UX.

Кроме того, на тестовом прогоне ни одного лога "Stored poster" / "Failed to download poster" не было — то есть downloader даже не отрабатывал, хотя в DTO от orinuno `screenshots` непустой. Возможная причина: `getReadyForExport` вызывается без `with_material_data=true` в parser-kodik (нужно проверить `SourceKodikClient`), и orinuno возвращает урезанный DTO. Это уже **issue orinuno** — наш `/export/ready` должен явно возвращать список постеров (kinopoisk thumbnails) отдельным полем.

**Решение (orinuno-side):**
- В `ContentExportDto` добавить поле `posterUrl` (берём из `materialData.poster_url`), а `screenshots` оставить как кадры серии.
- В `docs-site/openapi.json` отразить поле.

**Решение (backend-master-side):** менять `downloadPosterIfAvailable` так, чтобы качал `dto.posterUrl()` (новое поле) вместо `screenshots[0]`.

#### E2E-5: parser-kodik отдаёт временные mp4 ссылки в meter-api

**Где:** `parser-kodik/.../KodikMeterMapper.java`.

**Что нашли:** в `ExportSerialRequest` поля `Episode.filepath` и `EpisodeVariant.filepath` заполняются прямой mp4-ссылкой из orinuno, например:

```
https://cloud.solodcdn.com/useruploads/.../182560b4eb150ea6418ecd7790375f91:2026042514/720.mp4
```

В URL зашит timestamp expires (`:2026042514` ≈ через 24-48 часов). Если meter-api сохранит это значение в БД как-есть и отдаст пользователям через сутки — все ссылки протухнут. Для долговременного хранения нужно либо:
1. parser-kodik скачивает mp4 сам и кладёт в minio (как с poster), а в meter-api отдаёт уже минио-путь;
2. или meter-api сам проксирует mp4 (single-flight cache);
3. или контракт меняется так, что meter-api всегда дёргает orinuno re-decode перед раздачей.

**Действие:** обсудить с владельцем meter-api, до тех пор `Episode.filepath` оставить пустым (mp4 не материализован в meter), а отдавать только `episodeVariants[].filepath` и помечать их как `expires_at`. По сути нужен новый контракт между сервисами.

#### Итог Phase 0

- ✅ Цикл orinuno → parser-kodik работает (поллинг, маппинг, фильтрация по `lastPollTimestamp`).
- ❌ parser-kodik в нынешнем виде не запускается из коробки (E2E-1, E2E-2).
- ❌ State management хрупкий (E2E-3).
- ❌ Контракт по постерам и mp4-ссылкам нуждается в доработке (E2E-4, E2E-5).
- ⏭️ Полноценный e2e с meter-api не делали — поднимать всю обвязку (Postgres, Kafka, ES) ради проверки одного UNAUTHORIZED-вызова дороже, чем ценность.

### E2E-PHASE0-LIVE-RUN-FINDINGS: Реальный прогон (2026-04-26)

**Контекст:** прошли полный цикл `orinuno → parser-kodik → meter-stub → MinIO`
с реальной инфраструктурой backend-master compose (`storage`, `percona`),
Liquibase-миграциями применёнными вручную, и HTTP-стабом meter на `:8082`,
который умеет переключаться между `ok` и `fail` режимами. Все 7 ready-items
прошли путь `PENDING → EXPORTED` в успешном сценарии, и `PENDING → FAILED →
back-off → retry → EXPORTED` в негативном. Ниже — что **нашлось живого**, что
unit-тесты не поймали.

#### LIVE-1 [PUNTED → backend-master team]: meter-api контракт `success` обязателен (`MeterApiService` NPE на `null`)

**Статус:** _передано команде backend-master_. Записано в
`backend-master/parser-kodik/BACKLOG.md` для коллег. Сами **не делаем**:
`MeterApiService` — shared library, и любая правка в нём затрагивает все
парсеры (`parser-alloha`, `parser-seasonvars`, …), а не только parser-kodik.
Эта правка должна идти через owner shared starter.

**Где:** `backend-master/meter-api-spring-boot-starter/.../MeterApiService.java:34`.

**Что нашли:** `exportContent` делает `if (!response.success()) {...}` без
null-check. Если meter возвращает `{}` (например, ошибка сериализации,
старая версия meter, тестовый стаб) — летит `NullPointerException: Cannot
invoke "java.lang.Boolean.booleanValue()" because the return value of
"ContentExportResponse.success()" is null`. Эта ошибка маскирует реальную
причину сбоя в `error_message` parser-kodik.

**Решение (для команды backend-master):** проверять
`Boolean.TRUE.equals(response.success())`, а на null трактовать как
"контракт нарушен" и падать с понятной диагностикой
(`UnableToExportContentException("meter returned response without 'success' field")`).
До тех пор parser-kodik продолжит ловить такие ответы как обычные
исключения — back-off retry-канал уже закрывает worst case.

#### LIVE-2 [DONE]: `st.kp.yandex.net` отдаёт `403 Forbidden` без User-Agent / Referer

**Где:** `backend-master/parser-kodik/.../KodikMediaDownloader.java`.

**Что нашли:** 5 из 7 элементов имели `posterUrl=https://st.kp.yandex.net/...`
(KinoPoisk thumbnails). Yandex анти-хотлинк блокирует пустой User-Agent →
WebClient получает 403 Forbidden → `posterFilepath=null` → meter получает
запись без постера. Только записи с `https://shikimori.io/uploads/poster/...`
скачались успешно (24-40 KB JPEG, попали в `local/movies/kodik/posters/`).

**Решение (реализовано в backend-master/parser-kodik):**
- WebClient теперь штампит `User-Agent: Mozilla/5.0 (...) Chrome/135.0.0.0`
  на каждый GET постера.
- `Referer` подбирается по домену: `kinopoisk.ru/` для `*.yandex.net` /
  `kinopoisk.*`, `shikimori.one/` для `shikimori.*`, `kodik.info/` для
  `*.kodik*`. Без referer-а Yandex/Shikimori всё равно отдают 403.
- Перегрузка `downloadAndStorePoster(primary, fallback, id)`: если primary
  отдаёт любую ошибку (включая 403) — автоматически переходим на
  `screenshots[0]`. Scheduler передаёт `dto.posterUrl()` как primary и
  `dto.screenshots().get(0)` как fallback в одном вызове.
- Покрытие: `KodikMediaDownloaderTest` (5 кейсов) + обновлённые
  `KodikExportSchedulerTest` (11 кейсов) — все зелёные.

#### LIVE-3 [DONE]: scheduler не возвращался к FAILED-записям (retry pipeline missing)

**Где:** `parser-kodik/.../KodikExportScheduler.java`.

**Что нашли:** когда meter был недоступен, parser-kodik ставил
`status=FAILED, next_retry_at=now+backoff`. После восстановления meter
запись **никогда не получала retry**, потому что `lastPollTimestamp` уже
сдвинут в "сейчас", и orinuno возвращает `No new content`. То есть
`next_retry_at` существует в схеме, но никто его не использует. FAILED
оседали в БД навсегда.

**Решение (реализовано в parser-kodik живого прогона):**
- Repo: `findRetryReady(now, limit)` → `SELECT kodik_content_id FROM ... WHERE
  status='FAILED' AND next_retry_at <= ? ORDER BY next_retry_at ASC LIMIT ?`.
- Scheduler: после основного poll-цикла безусловно вызывать `retryFailed()`,
  который для каждого id дёргает `sourceKodikClient.getExportData(id)` и
  гонит DTO через тот же `processExportItem(...)`. Идёмпотентность гарантирует
  state-machine.
- Bugfix: ранний `return` при пустом ответе orinuno **пропускал** retry pass
  — переписать на `else`, чтобы `retryFailed()` всегда выполнялся.

#### LIVE-4 [DONE]: MyBatis NPE на `markPending` (`No setter found for keyProperty 'id'`)

**Где:** `parser-kodik/.../KodikExportStateMapper.xml::markPending`.

**Что нашли:** `<insert useGeneratedKeys="true" keyProperty="id">` с парам-
типом `Long kodikContentId` (примитив-обёртка). MyBatis пытается записать
generated key обратно в `Long` и валится с `No setter found for the keyProperty
'id' in 'java.lang.Long'`. На каждой первой попытке экспорта парсер падал
до того, как успевал хоть что-то полезное сделать.

**Решение (реализовано):** убрать `useGeneratedKeys="true" keyProperty="id"`
из `<insert id="markPending">`. Generated id никем не используется.

#### LIVE-5 [DONE]: дефолты `application.yml` parser-kodik не совпадают с
`docker/docker-compose.yml`

**Где:** `parser-kodik/src/main/resources/application.yml`.

**Что нашли:** дефолты были `MINIO_KEY=minioadmin`, `MINIO_BUCKET=kinodostup`,
`DB_PASSWORD=root`. Compose-стек поднимает MinIO с `secret1234/secret1234`,
bucket `movies`, MySQL `root/123`. Без явных env-overrides сервис не
авторизовался ни в MinIO, ни в БД.

**Решение (реализовано):** дефолты в `application.yml` приведены к значениям
из `docker/docker-compose.yml`. Теперь parser-kodik стартует **без
ENV-overrides** на нативном backend-master compose.

#### Что E2E-3 теперь покрывает

Полный цикл проверен живым прогоном:

1. `meter` режим **fail** + рестарт parser-kodik → 7 записей `PENDING → FAILED`,
   `retry_count=1`, `next_retry_at=now+30s`.
2. Внутри back-off-окна: следующий poll-цикл выбирает FAILED-id из БД,
   `findRetryReady` возвращает 0 (next_retry_at в будущем) → ничего не делается.
3. После истечения back-off + переключения `meter` в **ok**: `findRetryReady`
   возвращает 7 id, scheduler рефетчит каждый через `getExportData(id)`,
   процессит → 7 записей `FAILED → EXPORTED`, `retry_count=0`,
   `last_exported_at` заполнено, постеры от shikimori лежат в MinIO.

#### Что E2E-4 теперь покрывает

`posterUrl` приходит из orinuno (см. orinuno-side fix `ContentExportDto.posterUrl`
извлекается из `material_data.poster_url_original`/`poster_url`). parser-kodik
читает `dto.posterUrl()` первым приоритетом, на null-валидном — fallback на
`screenshots[0]`. На реальном датасете 7 ready-items: 7/7 имеют `posterUrl`,
5 от kinopoisk → 403 (LIVE-2), 2 от shikimori → попадают в MinIO с
`posterFilepath=kodik/posters/kodik_{id}.jpeg` и передаются в meter request.

### IDEA-6: Webhook/Event уведомления

**Приоритет:** Низкий
**Сложность:** Средняя

Отправлять webhook при событиях:
- Новый контент добавлен в БД
- Декодирование завершено (mp4_link получен)
- Schema drift обнаружен
- Decoder health degradation

Формат: POST JSON на конфигурируемый URL (`orinuno.webhooks.url`).

### IDEA-7: Geo-block detection для backend-master

**Приоритет:** Средний
**Сложность:** Реализована частично
**Файлы:** `GeoBlockDetector.java`

Текущий `GeoBlockDetector` анализирует `blocked_countries` и `blocked_seasons` из Kodik API. Расширить для:
- Автоматической фильтрации контента по стране пользователя
- Включения geo-block информации в export DTO для backend-master
- Статистики: "сколько контента заблокировано в RU"

### IDEA-8: Prometheus метрики

**Приоритет:** Средний
**Сложность:** Низкая

Добавить Micrometer метрики:
- `orinuno_kodik_api_requests_total` (counter, по эндпоинтам)
- `orinuno_kodik_api_latency_seconds` (histogram)
- `orinuno_decode_success_total` / `orinuno_decode_failure_total`
- `orinuno_schema_drift_total`
- `orinuno_content_total` (gauge)
- `orinuno_expired_links_total` (gauge)

Spring Actuator + Prometheus endpoint уже на порту 8081.

### IDEA-9: Docker multi-stage build оптимизация

**Приоритет:** Низкий
**Сложность:** Низкая

Текущий `Dockerfile` можно улучшить:
- Multi-stage build (Maven build → slim JRE runtime)
- Playwright browsers pre-installed в Docker image
- ffmpeg включён в image
- Health check в `docker-compose.yml`

### IDEA-10: Rate limiter для внешних потребителей API

**Приоритет:** Средний
**Сложность:** Средняя

Сейчас `KodikApiRateLimiter` ограничивает наши вызовы к Kodik API. Нужен отдельный rate limiter для входящих запросов от потребителей нашего REST API:
- Token bucket per API key
- Configurable limits (`orinuno.rate-limit.requests-per-minute`)
- HTTP 429 Too Many Requests при превышении
- Заголовки `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`

### IDEA-11: Anime4K / Magpie — апскейлинг видео

**Приоритет:** Низкий (R&D)
**Сложность:** Высокая
**Источник:** [Reddit: Recently discovered Magpie](https://www.reddit.com/r/visualnovels/comments/1ajl4ev/recently_discovered_magpie_and_i_cant_believe_ive/)
**Референсные проекты:**
- [Magpie](https://github.com/Blinue/Magpie) — real-time window upscaling (Windows, HLSL шейдеры)
- [Anime4K](https://github.com/bloc97/Anime4K) — open-source high-quality anime upscaling алгоритмы (GLSL)
- [Anime4K-Encoder](https://github.com/FarisR99/Anime4K-Encoder) — batch encoding wrapper
- [PyAnime4K-GUI](https://github.com/7gxycn08/PyAnime4K-GUI) — GUI для ffmpeg + Anime4K

**Контекст:** Magpie — инструмент real-time апскейлинга через GPU-шейдеры (CuNNy, Anime4K, FSR, ACNet). Основное применение — визуальные новеллы и аниме-игры, но алгоритмы применимы и к видео. Многие видео с Kodik CDN идут в 480p/720p — апскейлинг до 1080p/4K значительно улучшит качество.

**Варианты применения в orinuno:**

#### Вариант A: Серверный апскейлинг при скачивании (post-processing)

После скачивания видео через Playwright (`VideoDownloadService`), перед сохранением на диск — пропустить через ffmpeg + libplacebo с Anime4K шейдерами.

```bash
ffmpeg -init_hw_device vulkan \
  -i input_720p.mp4 \
  -vf "format=yuv420p10,hwupload,libplacebo=w=1920:h=1080:upscaler=ewa_lanczos:custom_shader_path=Anime4K_ModeA.glsl,hwdownload,format=yuv420p10" \
  -c:v hevc_nvenc -preset p4 -cq 22 \
  output_1080p.mp4
```

**Плюсы:** Один раз обработал — все потребители получают HD.
**Минусы:** Требует GPU на сервере (Vulkan), увеличивает время обработки и размер файла.

#### Вариант B: On-demand транскодирование при стриминге

`StreamController` при запросе видео в определённом качестве запускает ffmpeg pipeline на лету:

```
GET /api/v1/stream/{id}?quality=1080p_upscaled
```

**Плюсы:** Не нужно хранить апскейленные копии.
**Минусы:** Высокая нагрузка на GPU, латентность.

#### Вариант C: Клиентские подсказки (самый простой)

Не апскейлить на сервере, а предоставлять клиентам информацию для self-upscaling:
- В export DTO добавить поле `upscalable: true` и `nativeResolution: "720p"`
- В демо UI добавить кнопку "Открыть в Magpie" (deep link)
- Документация по настройке Anime4K шейдеров в mpv/VLC для потребителей

**Файлы для реализации (вариант A):**
- `VideoUpscaleService.java` — обёртка над ffmpeg + Anime4K
- Расширение `VideoDownloadService` — optional post-processing step
- Конфигурация: `orinuno.upscale.enabled`, `orinuno.upscale.target-resolution`, `orinuno.upscale.shader`
- Зависимости: ffmpeg compiled with `--enable-libplacebo --enable-vulkan`, Anime4K GLSL шейдеры

**Что нужно исследовать:**
1. Качество апскейлинга Anime4K vs bicubic на типичном Kodik-контенте (тест на 5 видео)
2. Производительность: сколько времени занимает апскейл 24-мин эпизода 720p→1080p
3. Требования к GPU: работает ли на CPU-only сервере через software Vulkan (lavapipe)
4. Размер файлов: ratio 720p vs upscaled 1080p
5. Сравнение алгоритмов: Anime4K_ModeA vs CuNNy vs FSR для аниме-контента

---

## 4. Schema Drift — обнаруженные расхождения

### Аудит от 2026-04-16

Проведён полный аудит всех 7 эндпоинтов Kodik API.

#### Чистые эндпоинты (дрифтов нет):

| Эндпоинт | Структура ответа | Поля item |
|---|---|---|
| `/search` | `{time, total, results, ?prev_page, ?next_page}` | 23 поля — все покрыты `KodikSearchResponse.Result` |
| `/list` | Аналогично `/search` | Та же структура |
| `/translations/v2` | `{time, total, results}` | `id, title, count` |
| `/genres` | `{time, total, results}` | `title, count` |
| `/countries` | `{time, total, results}` | `title, count` |
| `/years` | `{time, total, results}` | `year, count` |
| `/qualities/v2` | `{time, total, results}` | `title, count` |

#### Дрифты в `material_data` (исправлены в тестах, данные не теряются):

| Поле | Тип | Контент-тип | Описание |
|---|---|---|---|
| `anime_poster_url` | `String` (URL) | Аниме | Постер аниме с shikimori/MAL |
| `drama_poster_url` | `String` (URL) | Дорамы | Постер дорамы с MyDramaList |
| `drama_genres` | `List<String>` | Дорамы | Жанры дорам |
| `drama_status` | `String` | Дорамы | Статус дорамы (released, ongoing) |
| `mydramalist_tags` | `List<String>` | Дорамы | Теги MyDramaList |
| `tagline` | `String` | Все | Слоган / тэглайн |

**Статус:** Поля добавлены в `MATERIAL_DATA_KNOWN_FIELDS` в тестах. `material_data` хранится как `Map<String, Object>` — данные не теряются.

#### Особенности reference endpoints:

| Эндпоинт | Особенность |
|---|---|
| `/translations` (v1) | Возвращает **flat list** (не `{results:[...]}` wrapper). Есть поле `type`. Мы используем `/translations/v2` — проблемы нет |
| `/qualities` (v1) | Возвращает **flat list**, items только `{title}` без `count`. Мы используем `/qualities/v2` — проблемы нет |
| `/genres` | Items: `{title, count}` — **нет `id`** |
| `/countries` | Items: `{title, count}` — **нет `id`** |
| `/years` | Items: `{year, count}` — поле `year`, не `title` |

#### Ранее обнаруженные дрифты (исправлены):

| Дата | Поле | Контекст | Действие |
|---|---|---|---|
| 2026-04-16 | `blocked_seasons` | `KodikSearchResponse.Result` | Добавлено как `Map<String, Object>` |

---

## 5. Защитные механизмы Kodik (знание для будущей работы)

Kodik применяет многоуровневую защиту. Это знание необходимо при работе с декодером и при реализации аналогичных механизмов.

### Уровень 1: Обфускация видео-ссылок

| Механизм | Описание | Обход в orinuno |
|---|---|---|
| ROT13 шифрование (shift +18) | Все видео-URL шифруются ROT-подобным шифром | `KodikVideoDecoderService.rot13()` + brute-force 26 сдвигов |
| URL-safe Base64 | После ROT13 — Base64 с `-` → `+`, `_` → `/` | `Base64.getUrlDecoder()` |
| Динамический сдвиг ROT | Сдвиг может меняться со временем | `bruteForceAllShifts()` пробует все 26, кэширует рабочий |

### Уровень 2: Динамические endpoint-ы

| Механизм | Описание | Обход в orinuno |
|---|---|---|
| POST URL в JS через `atob()` | POST endpoint закодирован в Base64 внутри player JS | `extractPostUrl()` парсит JS, декодирует `atob()` |
| Версионирование JS файлов | `app.player_{hash}.js` — хеш меняется | `PLAYER_JS_PATTERN` regex + кэширование |
| Fallback endpoints | `/ftor`, `/gvi`, `/gvi2` и другие | `cachedVideoInfoPath` + fallback chain |

### Уровень 3: CDN защита

| Механизм | Описание | Обход в orinuno |
|---|---|---|
| Browser fingerprint check | CDN `solodcdn.com` отдаёт 0 bytes для non-browser запросов | Playwright headless Chromium |
| Cookie validation | CDN требует валидные cookies из browser session | `BrowserContext.cookies()` → Java HttpClient |
| CORS blocking | `fetch()` из браузера заблокирован для cross-origin CDN | Playwright `APIRequestContext` (серверный запрос) |
| TTL ссылки | mp4 URL истекают через N часов | `refreshExpiredLinks()` scheduled task |

### Уровень 4: Anti-bot

| Механизм | Описание | Обход в orinuno |
|---|---|---|
| VAST реклама | Player загружает рекламу перед видео | Playwright имитирует клик Play, ожидает загрузку видео |
| Rate limiting | Ограничение частоты запросов к API | `KodikApiRateLimiter` (Semaphore-based token bucket) |
| Token-based access | API требует токен | Конфигурируемый `orinuno.kodik.token` |

---

## 6. Конкурентный анализ — сводная таблица

### Сравнение возможностей

| Возможность | orinuno | KodikDownloader (Android) | kodik-api (Rust) | kodikwrapper (TS) | AnimeParsers (Python) |
|---|:---:|:---:|:---:|:---:|:---:|
| **Платформа** | Java 21, Spring Boot (server) | Java 17, Android (mobile app) | Rust (library) | TypeScript/Node.js (library) | Python (library) |
| **Kodik API client** | | | | | |
| /search | ✅ | ✅ (только search) | ✅ | ✅ | ✅ (`KodikSearch`) |
| /list | ✅ | ❌ | ✅ | ✅ | ✅ (`KodikList`) |
| /translations | ✅ (v2) | ❌ | ✅ | ❌ | ✅ (`get_translations()`) |
| /genres, /countries, /years | ✅ | ❌ | ✅ | ❌ | ❌ |
| /qualities | ✅ (v2) | ❌ | ✅ | ❌ | ❌ |
| Raw response first | ✅ | ❌ | ❌ | ❌ | ❌ |
| Schema drift detection | ✅ | ❌ | ❌ | ❌ | ❌ |
| Rate limiter | ✅ | ⚠️ (`Thread.sleep(100)`) | ❌ | ❌ | ❌ |
| **Декодирование видео** | | | | | |
| ROT13 decode | ✅ | ✅ (фиксированный сдвиг +18) | ❌ | ✅ | ✅ |
| Brute-force all shifts | ✅ | ❌ (только +18) | ❌ | ✅ | ✅ |
| Dynamic endpoint discovery | ✅ | ✅ (парсинг player JS) | ❌ | ✅ | ❌ |
| HLS manifest handling | ✅ | ⚠️ (только URL-нормализация) | ❌ | ❌ | ✅ |
| Video download (Playwright) | ✅ | ❌ | ❌ | ❌ | ❌ |
| ffmpeg remux (ts→mp4) | ✅ | ❌ | ❌ | ❌ | ❌ |
| Делегирование загрузки (ADM/IDM) | ❌ | ✅ (batch через Intent) | ❌ | ❌ | ❌ |
| **Инфраструктура** | | | | | |
| REST API для потребителей | ✅ | ❌ (mobile app) | ❌ (library) | ❌ (library) | ❌ (library) |
| Персистентное хранение (MySQL) | ✅ | ❌ (SharedPreferences) | ❌ | ❌ | ❌ |
| Database migrations (Liquibase) | ✅ | ❌ | ❌ | ❌ | ❌ |
| TTL refresh (scheduled) | ✅ | ❌ | ❌ | ❌ | ❌ |
| Retry failed decodes | ✅ | ❌ | ❌ | ❌ | ❌ |
| Export API для интеграции | ✅ | ❌ | ❌ | ❌ | ❌ |
| Proxy pool management | ✅ | ❌ | ❌ | ❌ | ❌ |
| Health monitoring dashboard | ✅ | ❌ | ❌ | ❌ | ❌ |
| UI | ✅ (Vue.js demo) | ✅ (Android native, Material 3) | ❌ | ❌ | ❌ |
| Docker deployment | ✅ | ❌ | ❌ | ❌ | ❌ |
| API key authentication | ✅ | ❌ (hardcoded token) | ❌ | ❌ | ❌ |
| Swagger/OpenAPI docs | ✅ | ❌ | ❌ | ✅ (types) | ❌ |
| **Мультисорсинг** | | | | | |
| Kodik parser | ✅ | ✅ (search + decode) | ✅ (API only) | ✅ | ✅ |
| Aniboom parser | ❌ | ❌ | ❌ | ❌ | ✅ |
| JutSu parser | ❌ | ❌ | ❌ | ❌ | ✅ |
| Shikimori integration | ❌ | ⚠️ (только search by ID) | ❌ | ❌ | ✅ |
| Sibnet parser | ❌ | ❌ | ❌ | ❌ | ❌ |
| Token auto-discovery | ❌ | ❌ (hardcoded) | ❌ | ✅ | ✅ (`get_token()`) |
| **Стабильность тестов** | | | | | |
| API stability tests | ✅ (67 тестов) | ❌ | ❌ | ❌ | ❌ |
| Deserialization tests | ✅ (7 тестов) | ❌ | ❌ | ❌ | ❌ |
| Schema drift in tests | ✅ | ❌ | ❌ | ❌ | ❌ |

### Наши сильные стороны

1. **Единственный проект с полной инфраструктурой** — БД, REST API, scheduled tasks, health monitoring
2. **Schema drift detection** — ни один конкурент этого не имеет
3. **Video download через Playwright** — обход CDN protection, уникальная реализация
4. **67 live API stability тестов** — покрытие стабильности внешнего API
5. **Export API** — готов для интеграции с backend-master

### Наши слабые стороны

1. **Single-source** — только Kodik, конкуренты (AnimeParsers) поддерживают 3+ источника
2. **Self-maintained** — AnimeParsers поддерживается сообществом на GitHub
3. **Java ecosystem** — тяжелее деплоить, чем Python/Node.js скрипты
4. **Нет async jobs** — длительные операции блокируют HTTP connection

### Стратегическое решение

Принято решение **не использовать AnimeParsers как базу**, а развивать orinuno самостоятельно, потому что:
- AnimeParsers — stateless Python library, orinuno — полноценный сервис
- Разный стек (Python vs Java/Spring Boot)
- orinuno имеет инфраструктуру, которую невозможно получить из AnimeParsers
- Логику парсинга Aniboom/JutSu можно портировать по необходимости

---

## Приоритизация

| # | Задача | Приоритет | Сложность | Зависимости |
|---|---|---|---|---|
| 1 | IDEA-4: material_data → БД | ✅ Done (2026-04-24) | Средняя | Liquibase migration |
| 2 | TD-1: Async Jobs | Высокий | Высокая | Новая таблица jobs |
| 3 | IDEA-AP-3: Shikimori интеграция | Высокий | Средняя | ShikimoriClient |
| 4 | IDEA-AP-4: Sibnet парсер | Высокий | Средняя | Research API/HTML, VideoHostParser абстракция |
| 5 | IDEA-1: REST reference endpoints | ✅ Done (2026-04-24) | Низкая | — |
| 6 | IDEA-2: Кэширование reference | ✅ Done (2026-04-24) | Низкая | IDEA-1 |
| 7 | IDEA-8: Prometheus метрики | Средний | Низкая | — |
| 8 | TD-3: Schema drift Level 2 | Средний | Средняя | Liquibase migration |
| 8a | TD-3a: Drift coverage (Commits A–D) | ✅ Done (2026-04-25) | Средняя | — |
| 8b | IDEA-DRIFT-1: missingFields detection | Низкий | Средняя | IDEA-DRIFT-3 |
| 8c | IDEA-DRIFT-2: Persistent drift history | Низкий | Низкая | Liquibase migration либо JSONL на диске |
| 8d | IDEA-DRIFT-3: TrafficAnalyzer service | Средний | Высокая | Ждёт второго парсера |
| 9 | IDEA-10: Rate limiter для потребителей | Средний | Средняя | — |
| 10 | IDEA-AP-1: Aniboom парсер | Средний | Высокая | Абстракция VideoHostParser |
| 11 | IDEA-AP-2: JutSu парсер | Средний | Средняя | Абстракция VideoHostParser |
| 12 | IDEA-5: Автопагинация /list | ✅ Done (2026-04-24) | Низкая | — |
| 13 | IDEA-7: Geo-block detection расширение | Средний | Низкая | — |
| 14 | TD-5: Playwright тесты | Средний | Средняя | — |
| 15 | IDEA-RUST-1: Enum-ы типов контента | Низкий | Низкая | — |
| 16 | IDEA-RUST-2: Типизированные фильтры | Низкий | Низкая | — |
| 17 | TD-2: ParseRequestDto валидация | Низкий (wontfix) | Низкая | Kodik API сам принимает такой запрос — паритет поведения |
| 18 | TD-4: DTO для reference endpoints | ✅ Done (2026-04-24) | Низкая | IDEA-1 |
| 19 | IDEA-KW-1: Token auto-discovery | ✅ Done (2026-04-24) | Средняя | — |
| 19b | IDEA-KH-1: Token harvester | Низкий | Средняя | — |
| 20 | IDEA-6: Webhook уведомления | Низкий | Средняя | — |
| 21 | IDEA-9: Docker оптимизация | Низкий | Низкая | — |
| 22 | IDEA-3: Типизированные DTO reference | ✅ Done (2026-04-24) | Низкая | IDEA-1 |
| 23 | IDEA-11: Anime4K видео-апскейлинг (R&D) | Низкий | Высокая | ffmpeg + libplacebo + Vulkan GPU |
