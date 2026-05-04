#!/usr/bin/env python3
"""Build docs/research/2026-05-04-jut-su-tier-A-per-repo-analysis.md from GitHub search export."""

from __future__ import annotations

import json
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "docs/research/2026-05-04-jut-su-tier-A-per-repo-analysis.md"


def classify_tier(it: dict) -> str:
    fn = it["full_name"]
    fn_l = fn.lower()
    name = (it.get("name") or "").lower()
    desc = (it.get("description") or "").lower()
    topics = it.get("topics") or []
    topics_l = " ".join(topics).lower()
    blob = f"{name} {desc} {topics_l} {fn_l}"

    if "line bot" in desc and "jutsu" in desc and "jut.su" not in blob:
        return "C"
    if "suuhijutsu" in desc.replace(" ", "") or "suhi jutsu" in blob:
        return "C"
    if len(fn) > 90 and re.search(r"[0-9]{8,}", fn):
        return "C"
    if any(k in fn_l for k in ("rumah", "subsidi", "perumahan", "kpr-", "cicilan", "artikel-3-rumah")):
        if "jut.su" not in blob and "jut-su" not in blob:
            return "C"
    spam_kw = (
        "rumah subsidi", "perumahan", " kpr ", " cicilan ", "hunian", "dp 5 juta",
        "085197773321", "085624371576", "miliki rumah", "jutonia",
    )
    if any(k in blob for k in spam_kw):
        return "C"
    if "jut.su" not in blob and "jut-su" not in blob and "anime" not in blob:
        if "raw_jute" in fn_l or "jutesurvey" in fn_l or re.search(r"jute[_./]|_jute", fn_l):
            return "C"
        if re.search(r"\bjute\b", blob) and any(
            x in blob for x in ("survey", "fiber", "distribution", "supply", "scraping", "beautiful soup")
        ):
            return "C"
    if "gutenberg" in blob or "gitenberg" in blob:
        return "C"
    if topics == ["config", "github-config"] or (
        set(topics) <= {"config", "github-config"} and "github profile" in desc
    ):
        return "C"
    noise_phrases = ["jutland", "jutlandia", "jutiklis", "viaje vivo", "museos del", "szamolo", "sunkesnis"]
    if any(p in blob for p in noise_phrases):
        return "C"
    if re.search(r"\bjutsu\b", blob) and ("naruto" in blob or "classifier" in blob or "tensorflow" in blob):
        if "jut.su" not in blob and "jut-su" not in blob and "anime" not in desc:
            return "C"
    if "time converter" in desc or "summoning-jutsu" in fn_l:
        return "C"
    if "ehsas lab" in desc or ("lab website" in desc and "sufyan" in fn_l):
        return "C"
    if "sug4r" in fn_l or "sugar rush" in desc or "maksim4l" in fn_l:
        return "C"
    if desc.startswith("&dp") or (len(desc) > 20 and sum(c in "{}[]\\|^`" for c in desc[:80]) > 15):
        return "C"
    if "substring" in fn_l and "jutsu" in fn_l and "fun" in desc:
        return "C"
    if "jutlor" in fn_l:
        return "C"

    if "jut.su" in blob or "jut-su" in blob or "jutsu.works" in blob:
        return "A"
    if "jutsu api" in blob or "with-jutsu-api" in fn_l or "jutsu-api" in fn_l:
        return "A"
    if "jutsu" in name and any(
        x in blob
        for x in (
            "anime", "parser", "scrap", "download", "episode", "video", "apk", "serial",
            "browser", "extension", "chrome", "firefox", "telegram", "plus", "free jut",
        )
    ):
        return "A"
    if any(x in topics for x in ("anime", "scraper", "downloader", "web-scraping")):
        return "A"
    if desc and any(
        x in desc
        for x in (
            "parser for https://jut.su",
            "parser for jut.su",
            "downloader for jut",
            "скачивание",
            "с сайта jut",
            "from jut.su",
            "from service jut.su",
            "site jut.su",
        )
    ):
        return "A"
    if "anime" in blob and any(x in blob for x in ("parser", "scrap", "download", "парсер", "скач", "загруз")):
        return "A"
    if name in {"jut.su", "jut_su", "jutsu"} and not desc:
        return "B"
    if "jutsu" in blob or re.search(r"jut[_-]?su", fn_l):
        return "B"
    return "B"


def category_for(it: dict) -> tuple[str, list[str]]:
    fn_l = it["full_name"].lower()
    name_l = (it.get("name") or "").lower()
    desc = (it.get("description") or "").lower()
    topics = " ".join(it.get("topics") or []).lower()
    lang = (it.get("language") or "") or ""
    blob = f"{fn_l} {name_l} {desc} {topics}"

    tags: list[str] = []
    if (
        "injector" in desc
        or "injector" in fn_l
        or "premium remover" in desc
        or "premium-" in fn_l
        or "free jutsu plus" in blob
        or ("jutsu-plus" in fn_l and ("free" in desc or "remover" in desc))
        or ("премиум" in desc and "сайта" in desc)
        or (name_l == "sova" and "премиум" in desc)
    ):
        tags.append("premium_bypass")
    if (
        "achievement" in blob
        or "achiev" in fn_l
        or "достижен" in desc
        or "timings of achievements" in desc
        or "achiv" in fn_l
    ):
        tags.append("achievements")
    if "telegram" in blob:
        tags.append("telegram")
    if "selenium" in desc:
        tags.append("selenium")
    if ("naruto" in desc and "parser" in fn_l) or name_l == "jut.su-naruto-parser":
        tags.append("title_specific_parser")
    if "kodik" in topics or "kodik" in desc:
        tags.append("mentions_kodik")
    if "cdp" in topics or "yt-dlp" in desc or "hls" in topics:
        tags.append("browser_ffmpeg_stack")
    if lang == "CSS" or ("style" in desc and "extension" in desc):
        tags.append("styling")
    if (
        "webview" in desc
        or ("android" in desc and "app" in desc)
        or (lang == "Kotlin" and "jut" in fn_l)
        or "jutsuapk" in fn_l
        or (fn_l.endswith("/jut.su") and lang == "Kotlin")
    ):
        tags.append("android_webview")
    if "pwa" in desc or "apple" in desc:
        tags.append("pwa")
    if "proxy" in desc and "api" in desc:
        tags.append("go_api_proxy")
    if "clone" in desc or (lang == "Vue" and "jut.su" in desc):
        tags.append("frontend_clone")
    ext_signal = (
        "extension" in topics
        or "chrome extension" in desc
        or "firefox extension" in desc
        or "расширение" in desc
        or fn_l.endswith("extension")
        or "userscript" in desc
        or "browser plugin" in desc
        or "расширение для chrome" in desc
        or "skipper" in fn_l
        or "autoskip" in fn_l
        or "autoplay" in fn_l
        or "auto-play" in fn_l
        or "nonstop" in fn_l
        or "episode-preview" in fn_l
        or "hands-off" in fn_l
        or ("controls" in fn_l and "chrome extension" in desc)
        or ("op" in topics and "skip" in topics)
        or ("auto" in topics and "script" in topics)
        or ("helper" in topics and "skip" in topics)
        or ("skip" in desc and "opening" in desc)
        or ("пропуск" in desc and "опенинг" in desc)
        or ("автоматически" in desc and "серии" in desc)
        or ("automat" in desc and "опенинг" in desc)
    )
    if ext_signal:
        tags.append("browser_extension")
    if (
        "download" in desc
        or "downloader" in topics
        or "loader" in name_l
        or "скачив" in desc
        or "jut-dlp" in desc
        or "video-downloader" in topics
        or "downl" in name_l
        or name_l.endswith("load")
        or "jutsuload" in fn_l
    ):
        tags.append("downloader")
    if "parser" in desc or "parse" in name_l or "парсер" in desc or "pars" in fn_l or "animejson" in fn_l:
        tags.append("parser")
    if "parse" in desc and "anime" in desc:
        tags.append("parser")
    if "api" in name_l and "jutsu" in name_l:
        tags.append("client_api")

    tags = list(dict.fromkeys(tags))

    priority = (
        "premium_bypass",
        "browser_extension",
        "browser_ffmpeg_stack",
        "downloader",
        "parser",
        "selenium",
        "telegram",
        "achievements",
        "android_webview",
        "pwa",
        "go_api_proxy",
        "frontend_clone",
        "styling",
        "client_api",
        "mentions_kodik",
        "title_specific_parser",
    )
    tags_sorted = [t for t in priority if t in tags]
    if not tags_sorted:
        tags_sorted = ["misc"]

    if tags_sorted == ["misc"]:
        if lang == "HTML" and not desc:
            tags_sorted = ["static_or_stub"]
        elif "marketplace" in fn_l:
            tags_sorted = ["uncategorized_marketplace"]
        elif lang == "Python" and ("addition" in fn_l or "_pars" in fn_l):
            tags_sorted = ["parser"]
        elif "onepiece" in fn_l or "ninja" in fn_l:
            tags_sorted = ["browser_extension"]
        elif "podkop" in fn_l:
            tags_sorted = ["network_misc"]
        elif "watch_jut" in fn_l or "fullscreen" in desc:
            tags_sorted = ["browser_automation_light"]

    label_map = {
        "premium_bypass": "Обход Jutsu+ / «фри» премиум",
        "browser_extension": "Браузерное расширение / userscript",
        "downloader": "Загрузчик (CLI/скрипт)",
        "parser": "Парсер метаданных/ссылок",
        "selenium": "Автоматизация браузера (Selenium)",
        "telegram": "Telegram-бот",
        "achievements": "Достижения / мета сайта",
        "android_webview": "Мобильная оболочка (WebView/APK)",
        "pwa": "PWA / обёртка под устройство",
        "go_api_proxy": "Сторонний API/proxy",
        "frontend_clone": "Клон/альтернативный UI",
        "styling": "Темы/CSS",
        "client_api": "Клиент/API-обёртка",
        "browser_ffmpeg_stack": "CDP / yt-dlp / HLS",
        "mentions_kodik": "Упоминает Kodik",
        "title_specific_parser": "Узкосерийный парсер",
        "static_or_stub": "HTML/заготовка без описания",
        "uncategorized_marketplace": "Не классифицировано (marketplace)",
        "network_misc": "Сеть/обход (см. README)",
        "browser_automation_light": "Вспомогательный скрипт просмотра",
        "misc": "Прочее",
    }
    primary = label_map.get(tags_sorted[0], tags_sorted[0])
    return primary, tags_sorted


def vs_orinuno(tags: list[str]) -> str:
    parts = []
    parts.append(
        "**orinuno:** `jutsu-sdk` — decode эпизода в MP4-URL, опционально DLE-логин, лимит исходящих запросов, "
        "`JutsuStreamProxyController` для Yandex CDN; рядом Kodik/Sibnet/Aniboom в multi-source."
    )
    if "premium_bypass" in tags:
        parts.append(
            "**Здесь:** обход оплаты/ограничений — не модель orinuno (у нас учётные данные Jutsu+ при необходимости)."
        )
    elif "browser_extension" in tags or "styling" in tags:
        parts.append("**Здесь:** UX в браузере; нет серверного decode/stream API для интеграторов.")
    elif "downloader" in tags and "browser_ffmpeg_stack" in tags:
        parts.append("**Здесь:** batch + CDP/yt-dlp/HLS; orinuno тянет прямой MP4 из HTML без yt-dlp.")
    elif "downloader" in tags:
        parts.append("**Здесь:** локальные файлы; редко есть сессия/CDN-proxy уровня orinuno.")
    elif "parser" in tags and "browser_extension" not in tags:
        parts.append(
            "**Здесь:** каталог/ссылки; полный каркас `get_anime_info в jutsu-sdk` у orinuno пока не обязателен."
        )
    elif "telegram" in tags:
        parts.append("**Здесь:** сценарий Telegram, не REST `/api/v1/sources/...` как у orinuno.")
    elif "selenium" in tags:
        parts.append("**Здесь:** Selenium; orinuno для JutSu обходится без постоянного headless.")
    elif "go_api_proxy" in tags:
        parts.append("**Здесь:** заявлен свой api/proxy — смотреть README репо для протокола.")
    elif "android_webview" in tags or "pwa" in tags:
        parts.append("**Здесь:** клиент-оболочка под устройство, не Maven `jutsu-sdk`.")
    elif "frontend_clone" in tags:
        parts.append("**Здесь:** фронт/клон; не backend-пайплайн извлечения потока.")
    elif "achievements" in tags:
        parts.append("**Здесь:** мета/ачивки сайта, не выдача MP4 для каталога.")
    elif "static_or_stub" in tags:
        parts.append("**Здесь:** пустой README — вероятно заготовка или статика; нет явного инструмента.")
    elif "network_misc" in tags:
        parts.append("**Здесь:** по имени — тема доступа/«подкопа» к сайту; детали только в README.")
    elif "browser_automation_light" in tags:
        parts.append("**Здесь:** вспомогательный сценарий (полноэкран и т.д.), не извлечение URL для API.")
    elif "uncategorized_marketplace" in tags:
        parts.append("**Здесь:** класс неочевиден без чтения кода (marketplace в названии).")
    elif "client_api" in tags:
        parts.append("**Здесь:** работа с API сайта; orinuno парсит страницу и CDN в рамках SDK.")
    else:
        parts.append("**Здесь:** см. описание — общее пересечение: источник jut.su.")
    if "mentions_kodik" in tags:
        parts.append("Явно фигурирует **Kodik** — тема мультиисточника как в orinuno.")
    return " ".join(parts)


def main() -> int:
    path = Path("/tmp/jut_su_all_items.json")
    if not path.exists():
        path = ROOT / "docs/research/data/jut_su_github_search_items.json"
    if not path.exists():
        print("Missing jut_su_all_items.json (copy to docs/research/data/ or /tmp/).", file=sys.stderr)
        return 1
    items = json.load(path.open())
    tier_a = [it for it in items if classify_tier(it) == "A"]
    tier_a.sort(key=lambda x: (-x.get("stargazers_count", 0), x["full_name"]))

    by_cat: dict[str, list[str]] = defaultdict(list)
    sections: list[str] = []

    for idx, it in enumerate(tier_a, 1):
        fn = it["full_name"]
        primary, tag_order = category_for(it)
        by_cat[primary].append(fn)
        url = it["html_url"]
        stars = it.get("stargazers_count", 0)
        lang = it.get("language") or "—"
        pushed = (it.get("pushed_at") or "")[:10]
        desc = (it.get("description") or "").replace("\r", " ").strip()
        topics = ", ".join(it.get("topics") or []) or "—"

        sections.append(f"## {idx}. `{fn}`\n")
        sections.append(f"- **URL:** {url}\n")
        sections.append(f"- **Звёзды / язык / push:** {stars} · {lang} · {pushed}\n")
        sections.append(f"- **Тип:** {primary}\n")
        sections.append(f"- **Теги:** `{', '.join(tag_order)}`\n")
        sections.append(f"- **Topics:** {topics}\n")
        if desc:
            sections.append(f"- **Описание (GitHub):** {desc}\n")
        sections.append(f"- **Vs orinuno:** {vs_orinuno(tag_order)}\n")
        sections.append("\n")

    header: list[str] = [
        "# Разбор репозиториев Tier A (поиск GitHub `jut.su`)\n",
        "\n",
        "Полный корпус и классы A/B/C: "
        "[2026-05-04-jut-su-github-repository-triage.md](./2026-05-04-jut-su-github-repository-triage.md). "
        "Ниже — **каждый из 95** репозиториев Tier A: метаданные GitHub, эвристический **тип**, **сравнение с orinuno**.\n",
        "\n",
        "Перегенерация: `python3 scripts/research/2026-05-04-jut-su-tier-a-analysis.py` "
        "(входные данные: `docs/research/data/jut_su_github_search_items.json` или `/tmp/jut_su_all_items.json`).\n",
        "\n",
        "---\n",
        "\n",
        "## Сводка по типам\n",
        "\n",
        "| Тип | Кол-во |\n",
        "|-----|-------:|\n",
    ]
    for k in sorted(by_cat.keys(), key=lambda x: (-len(by_cat[x]), x)):
        header.append(f"| {k} | {len(by_cat[k])} |\n")
    header.append("\n---\n\n")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("".join(header + sections), encoding="utf-8")
    print(f"Wrote {OUT} ({len(tier_a)} repos)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
