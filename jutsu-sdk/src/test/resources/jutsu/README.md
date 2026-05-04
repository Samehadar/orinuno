# jut.su test fixtures

Captured live from `https://jut.su` on **2026-05-04** as part of the
catalog/search/info/episode-meta/notice/drift extension to the SDK.
Each file is the raw HTTP response body, stored in its original
`windows-1251` encoding (matches what the JutsuClient receives at runtime).

## Files

| File | Source request | Size | Purpose |
|------|----------------|------|---------|
| `anime_filter_form.html` | `GET /anime/` (full page) | 132K | Filter-form drift baseline. The `JutsuFilterFormParser` mines this DOM to seed `JutsuGenre/JutsuType/JutsuYear/JutsuSort` enums. |
| `filter_form_manifest.json` | Derived from `anime_filter_form.html` | 3K | Frozen `(category → [{slug, label}])` mapping. The drift test `JutsuFilterEnumDriftTest` re-parses the form and asserts the live mapping ⊇ this manifest, so any new genre/type/year/sort is surfaced as `UNKNOWN_FILTER_SLUG`. |
| `catalog_page2_anime.html` | `POST /anime/` (`ajax_load=yes&start_from_page=2`) | 65K | Pagination partial — the AJAX "page-2" payload. ~16 entries, no chrome. |
| `catalog_filter_comedy_2024.html` | `POST /anime/comedy/2024/` (`ajax_load=yes&start_from_page=1`) | 65K | Filtered partial. Confirms that filtered pages return the same entry shape as the unfiltered partial. |
| `catalog_filter_empty_result.html` | `POST /anime/` with `show_search=zzznotanime_xyz` | 1.6K | Empty-state body — JS prelude only, no `all_anime_global` divs, footer says `var anime_page_next = false;`. |
| `catalog_search_history.html` | `POST /anime/` with `show_search=история` | 61K | Title-search hit. Same partial shape as catalog. |
| `catalog_search_no_match.html` | `POST /anime/` with `show_search=zzznotanime_xyz` | 1.6K | Same empty body as `catalog_filter_empty_result.html`; kept as a separate fixture to mark intent. |
| `anime_info_onepunch.html` | `GET /onepuunchman/` (full anime info page) | 60K | Source for `JutsuAnimeInfoParser`. Holds title, synopsis, episode list (one-season case), image, genres/types/years derived from class names. |
| `episode_premium_gated.html` | `GET /onepuunchman/episode-1.html` (anonymous) | 65K | Episode page when the requester is **not authenticated** — premium series show a paywall placeholder, not the player params. The `JutsuEpisodePageParser` must recognise this branch (and ideally tag it as `PREMIUM_GATED`) before falling back to the regex-based decoder. |
| `homepage_chrome.html` | `GET /` | 63K | Captures the **homepage notice chrome** — the single `<div class="notice_top2 notice_cont">` tile plus the `onclick="show_top_notice( 18729 )"` arrow that exposes the current "previous-cursor" id. Used for discovering the latest notice cursor. |
| `notice_feed_18729.html` | `POST /engine/ajax/site_notice.php` (`action=show&notice_id=18729`) | 22K | 50-entry feed. Pure list of `<div class="notice_cont">` rows, no navigation chrome embedded. |
| `notice_feed_18728_prev_cursor.html` | Same endpoint, `notice_id=18728` | 22K | Sliding-window proof: cursor `N-1` returns 50 entries shifted by one (overlap of 49 with the `N` window). The SDK pages by `cursor -= entries.size()` between calls. |
| `notice_feed_history_bound.html` | Same endpoint, `notice_id=1` | 0B | History-bound terminus — empty body. Used to confirm the `walkFeedsBackwards` `Flux` terminates cleanly when the cursor outruns the archive. |

## Capture script

The fixtures were captured with curl against the live site using a public
browser User-Agent and the same headers an anonymous Chrome 147 session
sends. No `dle_user_id` cookie was attached — every probe runs under the
SDK's real anonymous-traffic shape.

The cadence between captures is ≥1.5 s to stay below jut.su's 1 RPS
budget.

## Refreshing fixtures

Re-run the same probes (see the headers in the original conversation
that captured them) when:

* a strict-parse test starts failing on a captured fixture (the page
  shape changed under us);
* the schema-drift `MultiSourceRanker` integration starts demoting
  jut.su (the live shape diverged from the baseline);
* a new genre/type/year/sort lands on the form and the canary probe
  flags `UNKNOWN_FILTER_SLUG`.

Always commit the new fixture **and** regenerate `filter_form_manifest.json`
so the drift test stays in sync.
