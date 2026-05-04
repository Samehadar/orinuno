# Разбор репозиториев Tier A (поиск GitHub `jut.su`)

Полный корпус и классы A/B/C: [2026-05-04-jut-su-github-repository-triage.md](./2026-05-04-jut-su-github-repository-triage.md). Ниже — **каждый из 95** репозиториев Tier A: метаданные GitHub, эвристический **тип**, **сравнение с orinuno**.

Перегенерация: `python3 scripts/research/2026-05-04-jut-su-tier-a-analysis.py` (входные данные: `docs/research/data/jut_su_github_search_items.json` или `/tmp/jut_su_all_items.json`).

---

## Сводка по типам

| Тип | Кол-во |
|-----|-------:|
| Браузерное расширение / userscript | 26 |
| Парсер метаданных/ссылок | 19 |
| Загрузчик (CLI/скрипт) | 18 |
| HTML/заготовка без описания | 5 |
| Прочее | 5 |
| Достижения / мета сайта | 3 |
| Мобильная оболочка (WebView/APK) | 3 |
| Обход Jutsu+ / «фри» премиум | 3 |
| CDP / yt-dlp / HLS | 2 |
| Клиент/API-обёртка | 2 |
| Клон/альтернативный UI | 2 |
| PWA / обёртка под устройство | 1 |
| Telegram-бот | 1 |
| Вспомогательный скрипт просмотра | 1 |
| Не классифицировано (marketplace) | 1 |
| Сеть/обход (см. README) | 1 |
| Сторонний API/proxy | 1 |
| Темы/CSS | 1 |

---

## 1. `gXLg/jutsu-api`
- **URL:** https://github.com/gXLg/jutsu-api
- **Звёзды / язык / push:** 15 · Python · 2025-02-13
- **Тип:** Клиент/API-обёртка
- **Теги:** `client_api`
- **Topics:** —
- **Описание (GitHub):** Simple and flexible API for jut.su
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** работа с API сайта; orinuno парсит страницу и CDN в рамках SDK.

## 2. `cryptosvinarnik/jut.su-parser`
- **URL:** https://github.com/cryptosvinarnik/jut.su-parser
- **Звёзды / язык / push:** 10 · Python · 2024-04-18
- **Тип:** Парсер метаданных/ссылок
- **Теги:** `parser`
- **Topics:** —
- **Описание (GitHub):** Anime parser for https://jut.su/
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** каталог/ссылки; полный каркас `get_anime_info в jutsu-sdk` у orinuno пока не обязателен.

## 3. `sagynbek/jutsu-extension`
- **URL:** https://github.com/sagynbek/jutsu-extension
- **Звёзды / язык / push:** 6 · JavaScript · 2024-06-09
- **Тип:** Браузерное расширение / userscript
- **Теги:** `browser_extension`
- **Topics:** —
- **Описание (GitHub):** Browser extension for site jut.su
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** UX в браузере; нет серверного decode/stream API для интеграторов.

## 4. `emp0ry/Jut.su-PWA-like-app-for-Apple`
- **URL:** https://github.com/emp0ry/Jut.su-PWA-like-app-for-Apple
- **Звёзды / язык / push:** 4 · JavaScript · 2025-07-28
- **Тип:** PWA / обёртка под устройство
- **Теги:** `pwa`
- **Topics:** —
- **Описание (GitHub):** Make Jut.su an app for Apple devices
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** клиент-оболочка под устройство, не Maven `jutsu-sdk`.

## 5. `MONZikWasTaken/Jutsu-Premium-Injector`
- **URL:** https://github.com/MONZikWasTaken/Jutsu-Premium-Injector
- **Звёзды / язык / push:** 3 · Python · 2025-09-04
- **Тип:** Обход Jutsu+ / «фри» премиум
- **Теги:** `premium_bypass`
- **Topics:** —
- **Описание (GitHub):** ServerSided JUTSU+ remover for jut.su (Extension + Server) Allows u to watch everything without JUTSU+
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** обход оплаты/ограничений — не модель orinuno (у нас учётные данные Jutsu+ при необходимости).

## 6. `Mexano222/jut.su-Autoplay`
- **URL:** https://github.com/Mexano222/jut.su-Autoplay
- **Звёзды / язык / push:** 3 · JavaScript · 2021-11-17
- **Тип:** Браузерное расширение / userscript
- **Теги:** `browser_extension`
- **Topics:** —
- **Описание (GitHub):** auto play for jut.su
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** UX в браузере; нет серверного decode/stream API для интеграторов.

## 7. `RainNeko-static/JUT.SU-auto-play-skips-opening`
- **URL:** https://github.com/RainNeko-static/JUT.SU-auto-play-skips-opening
- **Звёзды / язык / push:** 3 · JavaScript · 2019-12-14
- **Тип:** Браузерное расширение / userscript
- **Теги:** `browser_extension`
- **Topics:** —
- **Описание (GitHub):** JUT.SU auto play,skips opening
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** UX в браузере; нет серверного decode/stream API для интеграторов.

## 8. `XetPy1030/JutSu-Downloader`
- **URL:** https://github.com/XetPy1030/JutSu-Downloader
- **Звёзды / язык / push:** 3 · Python · 2025-01-18
- **Тип:** Загрузчик (CLI/скрипт)
- **Теги:** `downloader`
- **Topics:** —
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** локальные файлы; редко есть сессия/CDN-proxy уровня orinuno.

## 9. `Buffersolve/JutLoader`
- **URL:** https://github.com/Buffersolve/JutLoader
- **Звёзды / язык / push:** 2 · Kotlin · 2023-04-08
- **Тип:** Загрузчик (CLI/скрипт)
- **Теги:** `downloader, android_webview`
- **Topics:** —
- **Описание (GitHub):** Downloader from Jut.su
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** локальные файлы; редко есть сессия/CDN-proxy уровня orinuno.

## 10. `LemiProject/JutSuParserCS`
- **URL:** https://github.com/LemiProject/JutSuParserCS
- **Звёзды / язык / push:** 2 · — · 2022-10-05
- **Тип:** Парсер метаданных/ссылок
- **Теги:** `parser`
- **Topics:** —
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** каталог/ссылки; полный каркас `get_anime_info в jutsu-sdk` у orinuno пока не обязателен.

## 11. `Neito3350/jut.suParser`
- **URL:** https://github.com/Neito3350/jut.suParser
- **Звёзды / язык / push:** 2 · Python · 2024-05-31
- **Тип:** Парсер метаданных/ссылок
- **Теги:** `parser`
- **Topics:** —
- **Описание (GitHub):** Парсер аниме с сайта jut.su
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** каталог/ссылки; полный каркас `get_anime_info в jutsu-sdk` у orinuno пока не обязателен.

## 12. `cardisnotvalid/jut-su`
- **URL:** https://github.com/cardisnotvalid/jut-su
- **Звёзды / язык / push:** 2 · Python · 2024-06-03
- **Тип:** Загрузчик (CLI/скрипт)
- **Теги:** `downloader`
- **Topics:** cli, cli-app, command-line-interface, jutsu, video-downloader, web-scraper
- **Описание (GitHub):** Загрузчик видео https://jut.su
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** локальные файлы; редко есть сессия/CDN-proxy уровня orinuno.

## 13. `efamir/selenium-anime-watching`
- **URL:** https://github.com/efamir/selenium-anime-watching
- **Звёзды / язык / push:** 2 · Python · 2022-05-11
- **Тип:** Браузерное расширение / userscript
- **Теги:** `browser_extension, selenium`
- **Topics:** —
- **Описание (GitHub):** A simple python selenium program based on firefox browser that automatically skips openings/endings and plays the next episodes on jut.su anime site.
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** UX в браузере; нет серверного decode/stream API для интеграторов.

## 14. `lexa946/async_anime_parser`
- **URL:** https://github.com/lexa946/async_anime_parser
- **Звёзды / язык / push:** 2 · Python · 2024-10-10
- **Тип:** Парсер метаданных/ссылок
- **Теги:** `parser`
- **Topics:** —
- **Описание (GitHub):** Асинхронный парсер сайта https://jut.su
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** каталог/ссылки; полный каркас `get_anime_info в jutsu-sdk` у orinuno пока не обязателен.

## 15. `BimbaXdeV/jutlib`
- **URL:** https://github.com/BimbaXdeV/jutlib
- **Звёзды / язык / push:** 1 · Python · 2024-11-28
- **Тип:** Загрузчик (CLI/скрипт)
- **Теги:** `downloader`
- **Topics:** —
- **Описание (GitHub):** Easy Python library for downloading anime episodes to user devices from service jut.su.
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** локальные файлы; редко есть сессия/CDN-proxy уровня orinuno.

## 16. `DenisGas/jut.su_next-series`
- **URL:** https://github.com/DenisGas/jut.su_next-series
- **Звёзды / язык / push:** 1 · JavaScript · 2026-01-06
- **Тип:** Браузерное расширение / userscript
- **Теги:** `browser_extension`
- **Topics:** autostart, chrome-extension, js, jutsu-next-series
- **Описание (GitHub):** Chrome Extension for jut.su that allows videos to autoplay, automatically skip anime intros, and automatically play the next episode.
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** UX в браузере; нет серверного decode/stream API для интеграторов.

## 17. `DenisGas/watch_jut.su`
- **URL:** https://github.com/DenisGas/watch_jut.su
- **Звёзды / язык / push:** 1 · Python · 2023-09-17
- **Тип:** Вспомогательный скрипт просмотра
- **Теги:** `browser_automation_light`
- **Topics:** batch-script, python3, selenium-python
- **Описание (GitHub):** Opens jut.su video in fullscreen
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** вспомогательный сценарий (полноэкран и т.д.), не извлечение URL для API.

## 18. `DiMiTrII-hash/Jutsu-Downloader-Pro`
- **URL:** https://github.com/DiMiTrII-hash/Jutsu-Downloader-Pro
- **Звёзды / язык / push:** 1 · JavaScript · 2025-08-17
- **Тип:** Загрузчик (CLI/скрипт)
- **Теги:** `downloader`
- **Topics:** —
- **Описание (GitHub):** outdated Скачивайте аниме с `jut.su` в 1080p с расширенным функционалом и современным интерфейсом
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** локальные файлы; редко есть сессия/CDN-proxy уровня orinuno.

## 19. `Luhgeekin/Jut.su-AchivUnlocker`
- **URL:** https://github.com/Luhgeekin/Jut.su-AchivUnlocker
- **Звёзды / язык / push:** 1 · C# · 2024-10-30
- **Тип:** Достижения / мета сайта
- **Теги:** `achievements`
- **Topics:** —
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** мета/ачивки сайта, не выдача MP4 для каталога.

## 20. `Neito3350/animeJSON`
- **URL:** https://github.com/Neito3350/animeJSON
- **Звёзды / язык / push:** 1 · — · 2024-03-24
- **Тип:** Парсер метаданных/ссылок
- **Теги:** `parser`
- **Topics:** —
- **Описание (GitHub):** Список всех аниме с сайта jut.su
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** каталог/ссылки; полный каркас `get_anime_info в jutsu-sdk` у orinuno пока не обязателен.

## 21. `Sanceilaks/jutparser`
- **URL:** https://github.com/Sanceilaks/jutparser
- **Звёзды / язык / push:** 1 · Python · 2022-10-03
- **Тип:** Парсер метаданных/ссылок
- **Теги:** `parser`
- **Topics:** —
- **Описание (GitHub):** Functions for parsing https://jut.su
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** каталог/ссылки; полный каркас `get_anime_info в jutsu-sdk` у orinuno пока не обязателен.

## 22. `Xeltone/Jut.su-Episode-preview`
- **URL:** https://github.com/Xeltone/Jut.su-Episode-preview
- **Звёзды / язык / push:** 1 · JavaScript · 2024-08-20
- **Тип:** Браузерное расширение / userscript
- **Теги:** `browser_extension`
- **Topics:** —
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** UX в браузере; нет серверного decode/stream API для интеграторов.

## 23. `Xeltone/Jut.su-Helper`
- **URL:** https://github.com/Xeltone/Jut.su-Helper
- **Звёзды / язык / push:** 1 · JavaScript · 2024-08-20
- **Тип:** Браузерное расширение / userscript
- **Теги:** `browser_extension`
- **Topics:** anime, auto, helper, jutsu, script, skip
- **Описание (GitHub):** Jut.su Helper - auto play, skip opening
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** UX в браузере; нет серверного decode/stream API для интеграторов.

## 24. `attikusfinch/JUTsu`
- **URL:** https://github.com/attikusfinch/JUTsu
- **Звёзды / язык / push:** 1 · Java · 2022-01-17
- **Тип:** Мобильная оболочка (WebView/APK)
- **Теги:** `android_webview`
- **Topics:** —
- **Описание (GitHub):** Simple webview app for jut.su on android.
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** клиент-оболочка под устройство, не Maven `jutsu-sdk`.

## 25. `flamesv/jut.su-autoskip`
- **URL:** https://github.com/flamesv/jut.su-autoskip
- **Звёзды / язык / push:** 1 · JavaScript · 2025-03-29
- **Тип:** Браузерное расширение / userscript
- **Теги:** `browser_extension`
- **Topics:** —
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** UX в браузере; нет серверного decode/stream API для интеграторов.

## 26. `quthery/jut.su`
- **URL:** https://github.com/quthery/jut.su
- **Звёзды / язык / push:** 1 · Python · 2024-02-08
- **Тип:** Парсер метаданных/ссылок
- **Теги:** `parser`
- **Topics:** —
- **Описание (GitHub):** jut.su parse anime
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** каталог/ссылки; полный каркас `get_anime_info в jutsu-sdk` у orinuno пока не обязателен.

## 27. `wooslow/jut-su.py`
- **URL:** https://github.com/wooslow/jut-su.py
- **Звёзды / язык / push:** 1 · Python · 2025-11-10
- **Тип:** Загрузчик (CLI/скрипт)
- **Теги:** `downloader`
- **Topics:** anime, jut-su, parser
- **Описание (GitHub):** A Python library for fetching and downloading anime information from jut.su.
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** локальные файлы; редко есть сессия/CDN-proxy уровня orinuno.

## 28. `AB-Gale/JUT-SU-marketplace`
- **URL:** https://github.com/AB-Gale/JUT-SU-marketplace
- **Звёзды / язык / push:** 0 · JavaScript · 2022-07-29
- **Тип:** Не классифицировано (marketplace)
- **Теги:** `uncategorized_marketplace`
- **Topics:** —
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** класс неочевиден без чтения кода (marketplace в названии).

## 29. `Addefan/jutsu-naruto-colorizer`
- **URL:** https://github.com/Addefan/jutsu-naruto-colorizer
- **Звёзды / язык / push:** 0 · JavaScript · 2024-09-30
- **Тип:** Браузерное расширение / userscript
- **Теги:** `browser_extension`
- **Topics:** —
- **Описание (GitHub):** A userscript that colors the Naruto series on Jut.su according to their importance
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** UX в браузере; нет серверного decode/stream API для интеграторов.

## 30. `Akim4XXX/JutSuPlus`
- **URL:** https://github.com/Akim4XXX/JutSuPlus
- **Звёзды / язык / push:** 0 · JavaScript · 2023-07-24
- **Тип:** Обход Jutsu+ / «фри» премиум
- **Теги:** `premium_bypass`
- **Topics:** —
- **Описание (GitHub):** Free JutSu Plus
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** обход оплаты/ограничений — не модель orinuno (у нас учётные данные Jutsu+ при необходимости).

## 31. `AsanbekPerizat/Jut.su`
- **URL:** https://github.com/AsanbekPerizat/Jut.su
- **Звёзды / язык / push:** 0 · HTML · 2022-05-23
- **Тип:** HTML/заготовка без описания
- **Теги:** `static_or_stub`
- **Topics:** —
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** пустой README — вероятно заготовка или статика; нет явного инструмента.

## 32. `Chermi6267/JutSuAnimeDownlader`
- **URL:** https://github.com/Chermi6267/JutSuAnimeDownlader
- **Звёзды / язык / push:** 0 · Python · 2024-04-08
- **Тип:** Загрузчик (CLI/скрипт)
- **Теги:** `downloader`
- **Topics:** —
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** локальные файлы; редко есть сессия/CDN-proxy уровня orinuno.

## 33. `D3F4U1T-ccs/Repo`
- **URL:** https://github.com/D3F4U1T-ccs/Repo
- **Звёзды / язык / push:** 0 · TypeScript · 2025-09-22
- **Тип:** Клон/альтернативный UI
- **Теги:** `frontend_clone`
- **Topics:** —
- **Описание (GitHub):** My Clone Of site jut.su
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** фронт/клон; не backend-пайплайн извлечения потока.

## 34. `D4SuCE/JutSuNewBot`
- **URL:** https://github.com/D4SuCE/JutSuNewBot
- **Звёзды / язык / push:** 0 · C++ · 2023-04-15
- **Тип:** Telegram-бот
- **Теги:** `telegram`
- **Topics:** anime, cpp, parsing, telegram-bot
- **Описание (GitHub):** Telegram bot for site: jut.su
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** сценарий Telegram, не REST `/api/v1/sources/...` как у orinuno.

## 35. `Danillchen/sova`
- **URL:** https://github.com/Danillchen/sova
- **Звёзды / язык / push:** 0 · JavaScript · 2025-02-20
- **Тип:** Обход Jutsu+ / «фри» премиум
- **Теги:** `premium_bypass`
- **Topics:** —
- **Описание (GitHub):** Премиум функции сайта jut.su
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** обход оплаты/ограничений — не модель orinuno (у нас учётные данные Jutsu+ при необходимости).

## 36. `Developer-inf/download-from-jut.su`
- **URL:** https://github.com/Developer-inf/download-from-jut.su
- **Звёзды / язык / push:** 0 · Python · 2022-10-04
- **Тип:** Загрузчик (CLI/скрипт)
- **Теги:** `downloader`
- **Topics:** —
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** локальные файлы; редко есть сессия/CDN-proxy уровня orinuno.

## 37. `DmEye/ending-skipper`
- **URL:** https://github.com/DmEye/ending-skipper
- **Звёзды / язык / push:** 0 · JavaScript · 2024-11-19
- **Тип:** Браузерное расширение / userscript
- **Теги:** `browser_extension`
- **Topics:** —
- **Описание (GitHub):** This is a Firefox extension to skip the credits(ending) of an anime on the "jut.su" site.
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** UX в браузере; нет серверного decode/stream API для интеграторов.

## 38. `Fo4Ik-git/Jut.su-Achievement`
- **URL:** https://github.com/Fo4Ik-git/Jut.su-Achievement
- **Звёзды / язык / push:** 0 · — · 2025-01-13
- **Тип:** Достижения / мета сайта
- **Теги:** `achievements`
- **Topics:** —
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** мета/ачивки сайта, не выдача MP4 для каталога.

## 39. `Galiks/JutSuChromeExtension`
- **URL:** https://github.com/Galiks/JutSuChromeExtension
- **Звёзды / язык / push:** 0 · JavaScript · 2020-08-18
- **Тип:** Браузерное расширение / userscript
- **Теги:** `browser_extension`
- **Topics:** —
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** UX в браузере; нет серверного decode/stream API для интеграторов.

## 40. `Gertholl/jut-su-autoskip`
- **URL:** https://github.com/Gertholl/jut-su-autoskip
- **Звёзды / язык / push:** 0 · JavaScript · 2025-05-02
- **Тип:** Браузерное расширение / userscript
- **Теги:** `browser_extension`
- **Topics:** —
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** UX в браузере; нет серверного decode/stream API для интеграторов.

## 41. `Info-anime/Jut.su`
- **URL:** https://github.com/Info-anime/Jut.su
- **Звёзды / язык / push:** 0 · HTML · 2026-03-02
- **Тип:** HTML/заготовка без описания
- **Теги:** `static_or_stub`
- **Topics:** —
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** пустой README — вероятно заготовка или статика; нет явного инструмента.

## 42. `Jeno7u/down-jutsu`
- **URL:** https://github.com/Jeno7u/down-jutsu
- **Звёзды / язык / push:** 0 · Python · 2025-08-03
- **Тип:** Загрузчик (CLI/скрипт)
- **Теги:** `downloader`
- **Topics:** —
- **Описание (GitHub):** Scraper app that downloads anime from https://jut.su.
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** локальные файлы; редко есть сессия/CDN-proxy уровня orinuno.

## 43. `JoYeRsOl/jut.su-Controls`
- **URL:** https://github.com/JoYeRsOl/jut.su-Controls
- **Звёзды / язык / push:** 0 · JavaScript · 2021-03-09
- **Тип:** Браузерное расширение / userscript
- **Теги:** `browser_extension`
- **Topics:** —
- **Описание (GitHub):** Chrome extension - Adds controls to videoplayer
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** UX в браузере; нет серверного decode/stream API для интеграторов.

## 44. `KirMozor/Jut-dl`
- **URL:** https://github.com/KirMozor/Jut-dl
- **Звёзды / язык / push:** 0 · Python · 2022-07-08
- **Тип:** Загрузчик (CLI/скрипт)
- **Теги:** `downloader`
- **Topics:** —
- **Описание (GitHub):** A simple anime downloader from Jut.su
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** локальные файлы; редко есть сессия/CDN-proxy уровня orinuno.

## 45. `Kosty374/jut.su`
- **URL:** https://github.com/Kosty374/jut.su
- **Звёзды / язык / push:** 0 · HTML · 2025-03-19
- **Тип:** HTML/заготовка без описания
- **Теги:** `static_or_stub`
- **Topics:** —
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** пустой README — вероятно заготовка или статика; нет явного инструмента.

## 46. `Kosty374/jut.suu`
- **URL:** https://github.com/Kosty374/jut.suu
- **Звёзды / язык / push:** 0 · JavaScript · 2025-03-20
- **Тип:** Прочее
- **Теги:** `misc`
- **Topics:** —
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** см. описание — общее пересечение: источник jut.su.

## 47. `Lovzu/Jut.su-Downloader`
- **URL:** https://github.com/Lovzu/Jut.su-Downloader
- **Звёзды / язык / push:** 0 · Python · 2025-08-09
- **Тип:** Загрузчик (CLI/скрипт)
- **Теги:** `downloader`
- **Topics:** —
- **Описание (GitHub):** Anime downloader
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** локальные файлы; редко есть сессия/CDN-proxy уровня orinuno.

## 48. `Lowara1243/jutsu-anime-downloader`
- **URL:** https://github.com/Lowara1243/jutsu-anime-downloader
- **Звёзды / язык / push:** 0 · Python · 2025-08-02
- **Тип:** Загрузчик (CLI/скрипт)
- **Теги:** `downloader`
- **Topics:** bs4, cookies, loguru, parser, requests, tqdm, video
- **Описание (GitHub):** A script to download anime from jut.su, with support for quality selection, season/episode selection, and Cloudflare bypass.
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** локальные файлы; редко есть сессия/CDN-proxy уровня orinuno.

## 49. `MeeJelly/jut.su`
- **URL:** https://github.com/MeeJelly/jut.su
- **Звёзды / язык / push:** 0 · — · 2020-11-21
- **Тип:** Прочее
- **Теги:** `misc`
- **Topics:** —
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** см. описание — общее пересечение: источник jut.su.

## 50. `MrArni/Jut.su-Skipper`
- **URL:** https://github.com/MrArni/Jut.su-Skipper
- **Звёзды / язык / push:** 0 · — · 2025-02-23
- **Тип:** Браузерное расширение / userscript
- **Теги:** `browser_extension`
- **Topics:** —
- **Описание (GitHub):** Автоматический пропуск опенинга и управление видео на jut.su
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** UX в браузере; нет серверного decode/stream API для интеграторов.

## 51. `NadarKhilchuk/Jut.sun`
- **URL:** https://github.com/NadarKhilchuk/Jut.sun
- **Звёзды / язык / push:** 0 · HTML · 2025-08-21
- **Тип:** HTML/заготовка без описания
- **Теги:** `static_or_stub`
- **Topics:** —
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** пустой README — вероятно заготовка или статика; нет явного инструмента.

## 52. `NadarKhilchuk/jut.su`
- **URL:** https://github.com/NadarKhilchuk/jut.su
- **Звёзды / язык / push:** 0 · HTML · 2025-08-21
- **Тип:** HTML/заготовка без описания
- **Теги:** `static_or_stub`
- **Topics:** —
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** пустой README — вероятно заготовка или статика; нет явного инструмента.

## 53. `NotAloneBoy/jutsu-plus`
- **URL:** https://github.com/NotAloneBoy/jutsu-plus
- **Звёзды / язык / push:** 0 · JavaScript · 2025-03-11
- **Тип:** Браузерное расширение / userscript
- **Теги:** `browser_extension`
- **Topics:** —
- **Описание (GitHub):** Enhanced viewing experience on Jut.Su with opening auto-skip and other features
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** UX в браузере; нет серверного decode/stream API для интеграторов.

## 54. `OwlUniversal/jut.su`
- **URL:** https://github.com/OwlUniversal/jut.su
- **Звёзды / язык / push:** 0 · Kotlin · 2025-11-02
- **Тип:** Мобильная оболочка (WebView/APK)
- **Теги:** `android_webview`
- **Topics:** —
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** клиент-оболочка под устройство, не Maven `jutsu-sdk`.

## 55. `QMasterkazna/ParserJutSu`
- **URL:** https://github.com/QMasterkazna/ParserJutSu
- **Звёзды / язык / push:** 0 · Python · 2024-06-24
- **Тип:** Парсер метаданных/ссылок
- **Теги:** `parser`
- **Topics:** —
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** каталог/ссылки; полный каркас `get_anime_info в jutsu-sdk` у orinuno пока не обязателен.

## 56. `Raywazz/jut.su-Podkop`
- **URL:** https://github.com/Raywazz/jut.su-Podkop
- **Звёзды / язык / push:** 0 · — · 2026-04-21
- **Тип:** Сеть/обход (см. README)
- **Теги:** `network_misc`
- **Topics:** —
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** по имени — тема доступа/«подкопа» к сайту; детали только в README.

## 57. `Sakuemy/jut.su_pars`
- **URL:** https://github.com/Sakuemy/jut.su_pars
- **Звёзды / язык / push:** 0 · Python · 2023-01-20
- **Тип:** Парсер метаданных/ссылок
- **Теги:** `parser`
- **Topics:** —
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** каталог/ссылки; полный каркас `get_anime_info в jutsu-sdk` у orinuno пока не обязателен.

## 58. `SergoZar/jutsu-plus`
- **URL:** https://github.com/SergoZar/jutsu-plus
- **Звёзды / язык / push:** 0 · — · 2023-05-25
- **Тип:** Браузерное расширение / userscript
- **Теги:** `browser_extension`
- **Topics:** —
- **Описание (GitHub):** Браузерное расширение с дополнительным функционалом для популярного сайта просмотра аниме - jut.su
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** UX в браузере; нет серверного decode/stream API для интеграторов.

## 59. `ShayanAB3/jut-dlp`
- **URL:** https://github.com/ShayanAB3/jut-dlp
- **Звёзды / язык / push:** 0 · HTML · 2025-08-14
- **Тип:** CDP / yt-dlp / HLS
- **Теги:** `browser_ffmpeg_stack, downloader`
- **Topics:** —
- **Описание (GitHub):** jut-dlp — это утилита командной строки для загрузки видео с сайта jut.su. Проект вдохновлён yt-dlp, но адаптирован специально для работы с jut.su и предлагает простой способ скачивания серий аниме. Проект предназначен для персонального использования и позволяет быстро загружать серии аниме по указанному URL.
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** batch + CDP/yt-dlp/HLS; orinuno тянет прямой MP4 из HTML без yt-dlp.

## 60. `Splend1ed/jut-su-addition`
- **URL:** https://github.com/Splend1ed/jut-su-addition
- **Звёзды / язык / push:** 0 · Python · 2022-09-22
- **Тип:** Парсер метаданных/ссылок
- **Теги:** `parser`
- **Topics:** —
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** каталог/ссылки; полный каркас `get_anime_info в jutsu-sdk` у orinuno пока не обязателен.

## 61. `StrangeTeaCreature/jutsu-grab`
- **URL:** https://github.com/StrangeTeaCreature/jutsu-grab
- **Звёзды / язык / push:** 0 · — · 2026-03-12
- **Тип:** CDP / yt-dlp / HLS
- **Теги:** `browser_ffmpeg_stack, downloader, mentions_kodik`
- **Topics:** anime, anime-downloader, batch-download, cdp, downloader, hls, jut-su, jutsu, kodik, python, scraper, video-downloader, yt-dlp
- **Описание (GitHub):** Automated anime downloader for jut.su — batch download, episode ranges, CDP + yt-dlp
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** batch + CDP/yt-dlp/HLS; orinuno тянет прямой MP4 из HTML без yt-dlp. Явно фигурирует **Kodik** — тема мультиисточника как в orinuno.

## 62. `Teruoru/jut.su-nonstop`
- **URL:** https://github.com/Teruoru/jut.su-nonstop
- **Звёзды / язык / push:** 0 · JavaScript · 2025-06-17
- **Тип:** Браузерное расширение / userscript
- **Теги:** `browser_extension`
- **Topics:** —
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** UX в браузере; нет серверного decode/stream API для интеграторов.

## 63. `Tomz41/Skipper-jut.su`
- **URL:** https://github.com/Tomz41/Skipper-jut.su
- **Звёзды / язык / push:** 0 · TypeScript · 2023-10-08
- **Тип:** Браузерное расширение / userscript
- **Теги:** `browser_extension`
- **Topics:** —
- **Описание (GitHub):** Скипер Оппенингов, эндингов,переключение серии и автозапуск, настройка не предусмотренна.
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** UX в браузере; нет серверного decode/stream API для интеграторов.

## 64. `ValeriiaVoitovych/Parsing_jut.su`
- **URL:** https://github.com/ValeriiaVoitovych/Parsing_jut.su
- **Звёзды / язык / push:** 0 · Python · 2023-07-25
- **Тип:** Парсер метаданных/ссылок
- **Теги:** `parser`
- **Topics:** —
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** каталог/ссылки; полный каркас `get_anime_info в jutsu-sdk` у orinuno пока не обязателен.

## 65. `Viper4109/Anime-parser-ENG`
- **URL:** https://github.com/Viper4109/Anime-parser-ENG
- **Звёзды / язык / push:** 0 · Python · 2024-07-07
- **Тип:** Парсер метаданных/ссылок
- **Теги:** `parser`
- **Topics:** —
- **Описание (GitHub):** A parser that collects information from the site jut.su (in English)
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** каталог/ссылки; полный каркас `get_anime_info в jutsu-sdk` у orinuno пока не обязателен.

## 66. `Viper4109/Anime-parser-RUS-`
- **URL:** https://github.com/Viper4109/Anime-parser-RUS-
- **Звёзды / язык / push:** 0 · Python · 2024-07-07
- **Тип:** Парсер метаданных/ссылок
- **Теги:** `parser`
- **Topics:** —
- **Описание (GitHub):** Парсер собирающий информацию с сайта jut.su (на русском языке)
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** каталог/ссылки; полный каркас `get_anime_info в jutsu-sdk` у orinuno пока не обязателен.

## 67. `WhiteUkrainan/jut.su-style`
- **URL:** https://github.com/WhiteUkrainan/jut.su-style
- **Звёзды / язык / push:** 0 · CSS · 2023-04-27
- **Тип:** Темы/CSS
- **Теги:** `styling`
- **Topics:** —
- **Описание (GitHub):** Google Extension for Jut.su that updating styles.
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** UX в браузере; нет серверного decode/stream API для интеграторов.

## 68. `Zephir0g/Jut.suParser`
- **URL:** https://github.com/Zephir0g/Jut.suParser
- **Звёзды / язык / push:** 0 · HTML · 2023-07-02
- **Тип:** Парсер метаданных/ссылок
- **Теги:** `parser, achievements`
- **Topics:** —
- **Описание (GitHub):** short algorithm to parse timings of achievements on jut.su
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** каталог/ссылки; полный каркас `get_anime_info в jutsu-sdk` у orinuno пока не обязателен.

## 69. `Zizazar/Jut.su-Downloader-Bot`
- **URL:** https://github.com/Zizazar/Jut.su-Downloader-Bot
- **Звёзды / язык / push:** 0 · Python · 2023-09-14
- **Тип:** Загрузчик (CLI/скрипт)
- **Теги:** `downloader`
- **Topics:** —
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** локальные файлы; редко есть сессия/CDN-proxy уровня orinuno.

## 70. `c1zzoid/Jut.su-downloader`
- **URL:** https://github.com/c1zzoid/Jut.su-downloader
- **Звёзды / язык / push:** 0 · Python · 2024-07-01
- **Тип:** Загрузчик (CLI/скрипт)
- **Теги:** `downloader`
- **Topics:** —
- **Описание (GitHub):** You can download videos directly from Jut.su !
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** локальные файлы; редко есть сессия/CDN-proxy уровня orinuno.

## 71. `david-d25/jutsu-extension`
- **URL:** https://github.com/david-d25/jutsu-extension
- **Звёзды / язык / push:** 0 · JavaScript · 2025-01-17
- **Тип:** Браузерное расширение / userscript
- **Теги:** `browser_extension`
- **Topics:** —
- **Описание (GitHub):** Extension for jut.su that allows to set playback speed, autoskip intro, and autoplay next episode
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** UX в браузере; нет серверного decode/stream API для интеграторов.

## 72. `dbconfig/jutsu_downloader`
- **URL:** https://github.com/dbconfig/jutsu_downloader
- **Звёзды / язык / push:** 0 · Python · 2023-11-01
- **Тип:** Загрузчик (CLI/скрипт)
- **Теги:** `downloader`
- **Topics:** —
- **Описание (GitHub):** Скрипт для скачивания аниме с jut.su
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** локальные файлы; редко есть сессия/CDN-proxy уровня orinuno.

## 73. `den-bulaev/jut-su-browser-extension`
- **URL:** https://github.com/den-bulaev/jut-su-browser-extension
- **Звёзды / язык / push:** 0 · JavaScript · 2025-02-14
- **Тип:** Браузерное расширение / userscript
- **Теги:** `browser_extension`
- **Topics:** —
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** UX в браузере; нет серверного decode/stream API для интеграторов.

## 74. `dnebik/jutsu-autoskip`
- **URL:** https://github.com/dnebik/jutsu-autoskip
- **Звёзды / язык / push:** 0 · JavaScript · 2022-10-01
- **Тип:** Браузерное расширение / userscript
- **Теги:** `browser_extension`
- **Topics:** browser-extension
- **Описание (GitHub):** Расширение для браузеров на сайт Jut.su для автоматического перехода на следующую серию
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** UX в браузере; нет серверного decode/stream API для интеграторов.

## 75. `emptybutton/Parser-Anime`
- **URL:** https://github.com/emptybutton/Parser-Anime
- **Звёзды / язык / push:** 0 · Python · 2021-08-14
- **Тип:** Парсер метаданных/ссылок
- **Теги:** `parser`
- **Topics:** anime, parse, parser
- **Описание (GitHub):** Can parse clean links of anime series and movies in Russian from Jut.su.
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** каталог/ссылки; полный каркас `get_anime_info в jutsu-sdk` у orinuno пока не обязателен.

## 76. `faizullo2007/jut.su`
- **URL:** https://github.com/faizullo2007/jut.su
- **Звёзды / язык / push:** 0 · — · 2026-01-04
- **Тип:** Прочее
- **Теги:** `misc`
- **Topics:** —
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** см. описание — общее пересечение: источник jut.su.

## 77. `gabarovveniamin/jutsuapk`
- **URL:** https://github.com/gabarovveniamin/jutsuapk
- **Звёзды / язык / push:** 0 · Kotlin · 2026-04-19
- **Тип:** Мобильная оболочка (WebView/APK)
- **Теги:** `android_webview`
- **Topics:** —
- **Описание (GitHub):** Сборщик апк для сайта jut.su, подробности в README.md
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** клиент-оболочка под устройство, не Maven `jutsu-sdk`.

## 78. `gaforrja/jutsu-autoplayer-extension`
- **URL:** https://github.com/gaforrja/jutsu-autoplayer-extension
- **Звёзды / язык / push:** 0 · JavaScript · 2025-06-14
- **Тип:** Браузерное расширение / userscript
- **Теги:** `browser_extension`
- **Topics:** —
- **Описание (GitHub):** Автоматически включает видео, пропускает заставку и переходит к следующей серии на jut.su
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** UX в браузере; нет серверного decode/stream API для интеграторов.

## 79. `grantfanian/jutsuload`
- **URL:** https://github.com/grantfanian/jutsuload
- **Звёзды / язык / push:** 0 · Python · 2022-09-12
- **Тип:** Загрузчик (CLI/скрипт)
- **Теги:** `downloader`
- **Topics:** —
- **Описание (GitHub):** video tool for jut.su
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** локальные файлы; редко есть сессия/CDN-proxy уровня orinuno.

## 80. `hovertank3d/monke`
- **URL:** https://github.com/hovertank3d/monke
- **Звёзды / язык / push:** 0 · Go · 2025-11-14
- **Тип:** Сторонний API/proxy
- **Теги:** `go_api_proxy`
- **Topics:** —
- **Описание (GitHub):** jut.su api and proxy
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** заявлен свой api/proxy — смотреть README репо для протокола.

## 81. `jintaxi/Parser-jut.su`
- **URL:** https://github.com/jintaxi/Parser-jut.su
- **Звёзды / язык / push:** 0 · Python · 2021-08-10
- **Тип:** Парсер метаданных/ссылок
- **Теги:** `parser`
- **Topics:** —
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** каталог/ссылки; полный каркас `get_anime_info в jutsu-sdk` у orinuno пока не обязателен.

## 82. `jjjuk/jutsu-hands-off`
- **URL:** https://github.com/jjjuk/jutsu-hands-off
- **Звёзды / язык / push:** 0 · JavaScript · 2022-02-20
- **Тип:** Браузерное расширение / userscript
- **Теги:** `browser_extension`
- **Topics:** —
- **Описание (GitHub):** Расширение для Chrome для просмотра аниме на jut.su с авто-плеем
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** UX в браузере; нет серверного decode/stream API для интеграторов.

## 83. `jutsuper/jutsuper`
- **URL:** https://github.com/jutsuper/jutsuper
- **Звёзды / язык / push:** 0 · JavaScript · 2024-12-12
- **Тип:** Браузерное расширение / userscript
- **Теги:** `browser_extension`
- **Topics:** —
- **Описание (GitHub):** Autoskip browser plugin for jut.su
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** UX в браузере; нет серверного decode/stream API для интеграторов.

## 84. `kosches/-jut.su`
- **URL:** https://github.com/kosches/-jut.su
- **Звёзды / язык / push:** 0 · — · 2025-07-08
- **Тип:** Прочее
- **Теги:** `misc`
- **Topics:** —
- **Описание (GitHub):** здравствуйте, долгое время борюсь с проблемой - не работает сайт jut.su. я знаю, что есть официальный сайт для пользователей из России (jutsu.ru), но не смотря на это, на сайте есть аниме, которые "недоступны" пользователям из России. У кого есть решение, как можно "вернуть к жизне" jut.su? впн не работает (у кого есть рабочий?)
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** см. описание — общее пересечение: источник jut.su.

## 85. `menarik-dev/Jut_su`
- **URL:** https://github.com/menarik-dev/Jut_su
- **Звёзды / язык / push:** 0 · Python · 2026-01-13
- **Тип:** Прочее
- **Теги:** `misc`
- **Topics:** —
- **Описание (GitHub):** Pet-project for testing site Jut.su
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** см. описание — общее пересечение: источник jut.su.

## 86. `mrnektom/jutsu`
- **URL:** https://github.com/mrnektom/jutsu
- **Звёзды / язык / push:** 0 · Vue · 2023-04-03
- **Тип:** Клон/альтернативный UI
- **Теги:** `frontend_clone`
- **Topics:** —
- **Описание (GitHub):** Vue-версия аниме-сервиса jut.su с модернизированным UI
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** фронт/клон; не backend-пайплайн извлечения потока.

## 87. `n0bl3z/onepiecejutsu`
- **URL:** https://github.com/n0bl3z/onepiecejutsu
- **Звёзды / язык / push:** 0 · JavaScript · 2025-03-16
- **Тип:** Браузерное расширение / userscript
- **Теги:** `browser_extension`
- **Topics:** —
- **Описание (GitHub):** Улучшенный скрипт для просмотра аниме One Piece на jut.su
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** UX в браузере; нет серверного decode/stream API для интеграторов.

## 88. `nebccc/jut.su-parser`
- **URL:** https://github.com/nebccc/jut.su-parser
- **Звёзды / язык / push:** 0 · Python · 2024-03-23
- **Тип:** Парсер метаданных/ссылок
- **Теги:** `parser`
- **Topics:** —
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** каталог/ссылки; полный каркас `get_anime_info в jutsu-sdk` у orinuno пока не обязателен.

## 89. `newskinner/jutsu-parser`
- **URL:** https://github.com/newskinner/jutsu-parser
- **Звёзды / язык / push:** 0 · Python · 2025-01-30
- **Тип:** Парсер метаданных/ссылок
- **Теги:** `parser`
- **Topics:** jutsu, parser, python
- **Описание (GitHub):** Work with jut.su’s code easier with Jut.su Parser.
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** каталог/ссылки; полный каркас `get_anime_info в jutsu-sdk` у orinuno пока не обязателен.

## 90. `qskateboard/jutsu-achievements`
- **URL:** https://github.com/qskateboard/jutsu-achievements
- **Звёзды / язык / push:** 0 · Python · 2022-02-26
- **Тип:** Достижения / мета сайта
- **Теги:** `achievements`
- **Topics:** —
- **Описание (GitHub):** Накрутка достижений на jut.su
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** мета/ачивки сайта, не выдача MP4 для каталога.

## 91. `quidixanime/jutsu-downloader`
- **URL:** https://github.com/quidixanime/jutsu-downloader
- **Звёзды / язык / push:** 0 · Python · 2025-02-12
- **Тип:** Загрузчик (CLI/скрипт)
- **Теги:** `downloader`
- **Topics:** —
- **Описание (GitHub):** Скачивание рускоязычного аниме с сайта jut.su
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** локальные файлы; редко есть сессия/CDN-proxy уровня orinuno.

## 92. `rodewitsch/jut.su-naruto-parser`
- **URL:** https://github.com/rodewitsch/jut.su-naruto-parser
- **Звёзды / язык / push:** 0 · JavaScript · 2024-05-03
- **Тип:** Парсер метаданных/ссылок
- **Теги:** `parser, title_specific_parser`
- **Topics:** parser
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** каталог/ссылки; полный каркас `get_anime_info в jutsu-sdk` у orinuno пока не обязателен.

## 93. `sher200408/ninjachronicles_jut.su`
- **URL:** https://github.com/sher200408/ninjachronicles_jut.su
- **Звёзды / язык / push:** 0 · HTML · 2025-07-16
- **Тип:** Браузерное расширение / userscript
- **Теги:** `browser_extension`
- **Topics:** —
- **Описание (GitHub):** Naruto
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** UX в браузере; нет серверного decode/stream API для интеграторов.

## 94. `shipchik/jutsu_downloader`
- **URL:** https://github.com/shipchik/jutsu_downloader
- **Звёзды / язык / push:** 0 · Python · 2022-11-23
- **Тип:** Загрузчик (CLI/скрипт)
- **Теги:** `downloader`
- **Topics:** —
- **Описание (GitHub):** script for download anime from jut.su
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** локальные файлы; редко есть сессия/CDN-proxy уровня orinuno.

## 95. `y9NBA/Work-with-JutSu-Api`
- **URL:** https://github.com/y9NBA/Work-with-JutSu-Api
- **Звёзды / язык / push:** 0 · Python · 2024-04-04
- **Тип:** Клиент/API-обёртка
- **Теги:** `client_api`
- **Topics:** —
- **Vs orinuno:** **orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, `JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source. **Здесь:** работа с API сайта; orinuno парсит страницу и CDN в рамках SDK.

