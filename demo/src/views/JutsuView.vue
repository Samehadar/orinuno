<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '../api/client'
import {
  downloadEta,
  downloadProgressPct,
  fmtBytes,
  useJutsuVideo,
} from '../composables/useJutsuVideo'
import type {
  JutsuAnimeInfo,
  JutsuCatalogEntry,
  JutsuCatalogPage,
  JutsuDriftSnapshot,
  JutsuNoticeEntry,
  JutsuNoticeFeed,
  JutsuPageMeta,
} from '../api/types'

// ─────────────────────────────────────────────────────────────────────────────
// Tab harness
// ─────────────────────────────────────────────────────────────────────────────

type Tab = 'catalog' | 'search' | 'info' | 'episode' | 'notice' | 'drift'

const tab = ref<Tab>('catalog')
const tabs: { id: Tab; icon: string; label: string }[] = [
  { id: 'catalog', icon: '🗂️', label: 'Catalog' },
  { id: 'search', icon: '🔎', label: 'Search' },
  { id: 'info', icon: '📄', label: 'Anime info' },
  { id: 'episode', icon: '🎬', label: 'Episode meta' },
  { id: 'notice', icon: '📰', label: 'Notice feed' },
  { id: 'drift', icon: '🩺', label: 'Drift' },
]

// ─────────────────────────────────────────────────────────────────────────────
// Filter form (shared by /catalog and /search). Slugs match what the API echoes
// in response shapes, so the round-trip is "click filter → see slug in card →
// re-click → same input". No translation tables are necessary.
// ─────────────────────────────────────────────────────────────────────────────

const GENRES = [
  ['adventure', 'Приключения'],
  ['action', 'Боевик'],
  ['comedy', 'Комедия'],
  ['everyday', 'Повседневность'],
  ['romance', 'Романтика'],
  ['drama', 'Драма'],
  ['fantastic', 'Фантастика'],
  ['fantasy', 'Фэнтези'],
  ['mystic', 'Мистика'],
  ['detective', 'Детектив'],
  ['thriller', 'Триллер'],
  ['psychology', 'Психология'],
] as const

const TYPES = [
  ['fighting', 'Боевые искусства'],
  ['vampire', 'Вампиры'],
  ['military', 'Военное'],
  ['demons', 'Демоны'],
  ['game', 'Игры'],
  ['historical', 'История'],
  ['space', 'Космос'],
  ['magic', 'Магия'],
  ['mecha', 'Меха'],
  ['music', 'Музыка'],
  ['parody', 'Пародия'],
  ['police', 'Полиция'],
  ['samurai', 'Самураи'],
  ['shojo', 'Сёдзё'],
  ['shonen', 'Сёнен'],
  ['sport', 'Спорт'],
  ['superpower', 'Суперсила'],
  ['horror', 'Ужасы'],
  ['school', 'Школа'],
] as const

const YEARS = [
  ['ongoing', 'Онгоинг'],
  ['2026', '2026'],
  ['2025', '2025'],
  ['2024', '2024'],
  ['2015-2023', '2015–2023'],
  ['2008-2014', '2008–2014'],
  ['2000-2007', '2000–2007'],
  ['before2000', 'до 2000'],
] as const

const SORTS = [
  ['', 'По рейтингу (default)'],
  ['order-by-name', 'По алфавиту'],
  ['order-by-count', 'По кол-ву серий'],
  ['order-by-date', 'По году выхода'],
  ['order-by-add', 'По дате добавл.'],
] as const

const selectedGenres = ref<string[]>([])
const selectedTypes = ref<string[]>([])
const selectedYears = ref<string[]>([])
const selectedSort = ref<string>('')
const page = ref(1)

function toggleGenre(slug: string) {
  if (selectedGenres.value.includes(slug)) {
    selectedGenres.value = selectedGenres.value.filter((x) => x !== slug)
  } else {
    selectedGenres.value = [...selectedGenres.value, slug]
  }
}

function toggleType(slug: string) {
  if (selectedTypes.value.includes(slug)) {
    selectedTypes.value = selectedTypes.value.filter((x) => x !== slug)
  } else {
    selectedTypes.value = [...selectedTypes.value, slug]
  }
}

function toggleYear(slug: string) {
  if (selectedYears.value.includes(slug)) {
    selectedYears.value = selectedYears.value.filter((x) => x !== slug)
  } else {
    selectedYears.value = [...selectedYears.value, slug]
  }
}

function clearFilter() {
  selectedGenres.value = []
  selectedTypes.value = []
  selectedYears.value = []
  selectedSort.value = ''
  page.value = 1
}

function buildFilter(extra: Record<string, unknown> = {}) {
  return {
    page: page.value,
    genres: selectedGenres.value,
    types: selectedTypes.value,
    years: selectedYears.value,
    sort: selectedSort.value || undefined,
    ...extra,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Catalog tab
// ─────────────────────────────────────────────────────────────────────────────

const catalogLoading = ref(false)
const catalogError = ref('')
const catalogResult = ref<JutsuCatalogPage | null>(null)

async function loadCatalog() {
  catalogLoading.value = true
  catalogError.value = ''
  try {
    catalogResult.value = await api.jutsuBrowseCatalog(buildFilter())
  } catch (e: any) {
    catalogError.value = e?.message ?? 'Catalog request failed'
  } finally {
    catalogLoading.value = false
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Search tab — uses the same filter form, plus the title input.
// ─────────────────────────────────────────────────────────────────────────────

const searchQuery = ref('')
const searchLoading = ref(false)
const searchError = ref('')
const searchResult = ref<JutsuCatalogPage | null>(null)

async function runSearch() {
  if (!searchQuery.value.trim()) {
    searchError.value = 'Title fragment is required'
    return
  }
  searchLoading.value = true
  searchError.value = ''
  try {
    searchResult.value = await api.jutsuSearch(buildFilter({ q: searchQuery.value.trim() }))
  } catch (e: any) {
    searchError.value = e?.message ?? 'Search request failed'
  } finally {
    searchLoading.value = false
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Anime info tab
// ─────────────────────────────────────────────────────────────────────────────

const infoSlug = ref('onepuunchman')
const infoLoading = ref(false)
const infoError = ref('')
const infoResult = ref<JutsuAnimeInfo | null>(null)

// Slug → Russian label lookup. Kept inline (no Map) — the lists are small
// (≈ 12/19 entries) and the linear scan is invisible in practice.
function genreLabel(slug: string): string {
  return GENRES.find((g) => g[0] === slug)?.[1] ?? slug
}
function typeLabel(slug: string): string {
  return TYPES.find((t) => t[0] === slug)?.[1] ?? slug
}
function yearBucketLabel(slug: string): string {
  return YEARS.find((y) => y[0] === slug)?.[1] ?? slug
}

// Human-readable comma list with Russian " и " ("and") before the last entry,
// matching jut.su's own formatting on the labelled info block. ["a","b","c"] →
// "a, b и c"; ["a","b"] → "a и b"; ["a"] → "a".
function joinHumanRu(items: string[]): string {
  if (items.length === 0) return ''
  if (items.length === 1) return items[0]
  return items.slice(0, -1).join(', ') + ' и ' + items[items.length - 1]
}

// Russian pluralisation for the "N фильм/фильма/фильмов" suffix. The Slavic
// rule splits on the last digit (or last two digits for the teens). This
// mirrors what jut.su prints next to the films heading on its own UI.
function filmsCountSuffix(n: number): string {
  const abs = Math.abs(n)
  const mod10 = abs % 10
  const mod100 = abs % 100
  if (mod100 >= 11 && mod100 <= 14) return 'фильмов'
  if (mod10 === 1) return 'фильм'
  if (mod10 >= 2 && mod10 <= 4) return 'фильма'
  return 'фильмов'
}

const infoGenreLabels = computed<string[]>(
  () => infoResult.value?.genres.map(genreLabel) ?? [],
)
const infoTypeLabels = computed<string[]>(
  () => infoResult.value?.types.map(typeLabel) ?? [],
)
const infoYearsLine = computed<string>(() => {
  const ys = infoResult.value?.years ?? []
  if (ys.length) return ys.join(', ')
  // Fallback to the coarse filter-form bucket when years[] is empty (cache row
  // pre-migration, or older fixture without the labelled block). yearBucketLabel
  // yields the human label ("до 2000") instead of the slug ("before2000").
  const bucket = infoResult.value?.year
  return bucket ? yearBucketLabel(bucket) : ''
})

// Age-rating colour map. 18+ red, 16+ orange, 12+ yellow-ish (we reuse the
// orange neon to keep the palette tight), 6+ and 0+ green. Keep the wire form
// strings here — that's what the API ships.
const ageRatingPillClass = computed<string>(() => {
  switch (infoResult.value?.ageRating) {
    case '18+':
      return 'bg-[var(--color-neon-red)]/15 text-[var(--color-neon-red)] border border-[var(--color-neon-red)]/40'
    case '16+':
      return 'bg-[var(--color-neon-orange)]/15 text-[var(--color-neon-orange)] border border-[var(--color-neon-orange)]/40'
    case '12+':
      return 'bg-[var(--color-neon-orange)]/10 text-[var(--color-neon-orange)] border border-[var(--color-neon-orange)]/30'
    case '6+':
    case '0+':
      return 'bg-[var(--color-neon-green)]/15 text-[var(--color-neon-green)] border border-[var(--color-neon-green)]/40'
    default:
      return 'bg-white/10 text-[var(--color-text-muted)] border border-white/20'
  }
})

async function loadAnimeInfo() {
  if (!infoSlug.value.trim()) {
    infoError.value = 'Slug is required'
    return
  }
  infoLoading.value = true
  infoError.value = ''
  try {
    infoResult.value = await api.jutsuGetAnimeInfo(infoSlug.value.trim())
  } catch (e: any) {
    infoError.value = e?.message ?? 'Info request failed'
  } finally {
    infoLoading.value = false
  }
}

function jumpToInfo(slug: string) {
  infoSlug.value = slug
  tab.value = 'info'
  loadAnimeInfo()
}

// ─────────────────────────────────────────────────────────────────────────────
// Episode meta tab — also hosts the decode/play/download surface for the URL.
// `useJutsuVideo` is the same composable Sources → Provider sandbox uses, so the
// mp4 fetch + stream proxy + chunked download with progress all behave identically.
// ─────────────────────────────────────────────────────────────────────────────

const episodeUrl = ref('https://jut.su/onepuunchman/season-1/episode-1.html')
const episodeLoading = ref(false)
const episodeError = ref('')
const episodeResult = ref<JutsuPageMeta | null>(null)

const jutsuVideo = useJutsuVideo()
const {
  decode: decodeEpisode,
  decodeLoading,
  decodeError,
  decodeResult,
  clearDecode,
  playerUrl,
  playInPlayer: playInPlayerRaw,
  closePlayer,
  downloads,
  startDownload,
  cancelDownload,
  clearDownload,
  isJutsuCdnUrl,
} = jutsuVideo

function playInPlayer(url: string) {
  playInPlayerRaw(url, 'jutsu-episode-inline-player')
}

async function loadEpisodeMeta() {
  if (!episodeUrl.value.trim()) {
    episodeError.value = 'URL is required'
    return
  }
  episodeLoading.value = true
  episodeError.value = ''
  // Stale qualities from a previous URL would be misleading; the inline player keeps
  // playing whatever it's holding (intentional — let the user dismiss it manually).
  clearDecode()
  try {
    episodeResult.value = await api.jutsuGetEpisodeMeta(episodeUrl.value.trim())
  } catch (e: any) {
    episodeError.value = e?.message ?? 'Episode request failed'
  } finally {
    episodeLoading.value = false
  }
}

async function decodeCurrentEpisode() {
  if (!episodeUrl.value.trim()) return
  await decodeEpisode(episodeUrl.value.trim())
}

function jumpToEpisode(url: string) {
  // Episode URL inside the SDK comes back as a relative path on /anime/{slug}/.
  // Absolute URLs come through verbatim (e.g. notice feed). Normalise both.
  episodeUrl.value = url.startsWith('http') ? url : `https://jut.su${url}`
  tab.value = 'episode'
  loadEpisodeMeta()
}

// Discriminated-union helpers for the episode/film result card. Films use
// `filmIndex` instead of season/episode, and a different prev/next cohort
// (sibling films, not sibling episodes), so the badge label and the
// prev/next bindings have to switch on `kind`.
const episodeBadge = computed(() => {
  const r = episodeResult.value
  if (!r) return ''
  return r.kind === 'film' ? `F${r.filmIndex}` : `S${r.season}E${r.episode}`
})

const episodePrevUrl = computed(() => {
  const r = episodeResult.value
  if (!r) return null
  return r.kind === 'film' ? r.prevFilmUrl : r.prevEpisodeUrl
})

const episodeNextUrl = computed(() => {
  const r = episodeResult.value
  if (!r) return null
  return r.kind === 'film' ? r.nextFilmUrl : r.nextEpisodeUrl
})

const episodeKindLabel = computed(() => {
  const r = episodeResult.value
  if (!r) return ''
  return r.kind === 'film' ? 'Полнометражный фильм' : 'Серия'
})

// ─────────────────────────────────────────────────────────────────────────────
// Notice feed tab
// ─────────────────────────────────────────────────────────────────────────────

const noticeCursor = ref<string>('')
const noticeLoading = ref(false)
const noticeError = ref('')
const noticeResult = ref<JutsuNoticeFeed | null>(null)
const noticeStreamEntries = ref<JutsuNoticeEntry[]>([])
const noticeStreaming = ref(false)
const noticeStreamMaxFeeds = ref(5)
let noticeAborter: AbortController | null = null

async function loadNoticeFeed() {
  noticeLoading.value = true
  noticeError.value = ''
  try {
    const cursorNum = noticeCursor.value ? Number(noticeCursor.value) : undefined
    noticeResult.value = await api.jutsuGetNoticeFeed(cursorNum)
  } catch (e: any) {
    noticeError.value = e?.message ?? 'Notice feed request failed'
  } finally {
    noticeLoading.value = false
  }
}

async function startNoticeStream() {
  if (!noticeResult.value) {
    noticeError.value = 'Load the latest feed first to get a starting cursor.'
    return
  }
  noticeStreamEntries.value = []
  noticeStreaming.value = true
  noticeAborter = new AbortController()
  try {
    for await (const e of api.jutsuStreamNoticeEntries(
      noticeResult.value.requestedCursor,
      noticeStreamMaxFeeds.value,
      noticeAborter.signal,
    )) {
      noticeStreamEntries.value = [...noticeStreamEntries.value, e]
    }
  } catch (e: any) {
    if (e?.name !== 'AbortError') {
      noticeError.value = e?.message ?? 'Stream failed'
    }
  } finally {
    noticeStreaming.value = false
    noticeAborter = null
  }
}

function abortNoticeStream() {
  noticeAborter?.abort()
}

// ─────────────────────────────────────────────────────────────────────────────
// Drift tab
// ─────────────────────────────────────────────────────────────────────────────

const driftLoading = ref(false)
const driftError = ref('')
const driftSnapshot = ref<JutsuDriftSnapshot | null>(null)

async function loadDrift() {
  driftLoading.value = true
  driftError.value = ''
  try {
    driftSnapshot.value = await api.jutsuGetDrift()
  } catch (e: any) {
    driftError.value = e?.message ?? 'Drift request failed'
  } finally {
    driftLoading.value = false
  }
}

const driftHealthColor = computed(() => {
  const h = driftSnapshot.value?.health
  if (h === 'HEALTHY') return 'text-[var(--color-neon-green)]'
  if (h === 'DEGRADED') return 'text-[var(--color-neon-orange)]'
  return 'text-[var(--color-neon-red)]'
})

// ─────────────────────────────────────────────────────────────────────────────
// Misc helpers
// ─────────────────────────────────────────────────────────────────────────────

function fmtTimestamp(iso: string | null | undefined): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString()
}

function copy(value: string | null | undefined) {
  if (!value) return
  navigator.clipboard?.writeText(value).catch(() => undefined)
}

const filterBadges = computed(() => {
  const out: string[] = []
  for (const g of selectedGenres.value) out.push(`genre:${g}`)
  for (const t of selectedTypes.value) out.push(`type:${t}`)
  for (const y of selectedYears.value) out.push(`year:${y}`)
  if (selectedSort.value) out.push(`sort:${selectedSort.value}`)
  return out
})

onMounted(() => {
  loadDrift()
})

function entriesGrid(p: JutsuCatalogPage | null): JutsuCatalogEntry[] {
  return p?.entries ?? []
}
</script>

<template>
  <div>
    <!-- Hero -->
    <div class="text-center mb-8">
      <h1 class="text-4xl sm:text-5xl font-extrabold mb-3">
        <span class="gradient-text">jut.su Sandbox</span>
      </h1>
      <p class="text-[var(--color-text-muted)] text-lg">
        Full-browser parity surface from <code class="text-[var(--color-neon-blue)]">jutsu-sdk</code> — catalog, search, info, episode meta, notice feed, and drift snapshot.
        See <a href="/orinuno/api/reference/" target="_blank" class="text-[var(--color-neon-pink)] underline">Swagger</a>
        for the OpenAPI contract; ADR 0015 for the design.
      </p>
    </div>

    <!-- Drift health pill (always visible) -->
    <div class="flex justify-center mb-6">
      <div class="glass-card px-4 py-2 flex items-center gap-2 text-xs">
        <span class="text-[var(--color-text-muted)]">SDK drift:</span>
        <span :class="['font-mono font-semibold', driftHealthColor]">
          {{ driftSnapshot?.health ?? 'loading…' }}
        </span>
        <span v-if="driftSnapshot" class="text-[var(--color-text-muted)]">
          · {{ driftSnapshot.lifetimeEvents }} lifetime events
        </span>
        <button
          class="ml-2 px-2 py-0.5 rounded bg-white/5 hover:bg-white/10 text-[var(--color-text-muted)]"
          @click="loadDrift"
          title="Refresh"
        >
          ⟳
        </button>
      </div>
    </div>

    <!-- Tabs -->
    <div class="flex justify-center mb-6">
      <div class="glass-card flex gap-1 p-1 flex-wrap">
        <button
          v-for="t in tabs"
          :key="t.id"
          class="px-3 py-2 rounded-md text-sm font-medium transition-colors"
          :class="tab === t.id
            ? 'bg-[var(--color-neon-pink)]/20 text-[var(--color-neon-pink)]'
            : 'text-[var(--color-text-muted)] hover:text-white'"
          @click="tab = t.id"
        >
          {{ t.icon }} {{ t.label }}
        </button>
      </div>
    </div>

    <!-- ─────────────────────────── Catalog ─────────────────────────── -->
    <section v-if="tab === 'catalog' || tab === 'search'">
      <div class="glass-card p-4 mb-6 max-w-5xl mx-auto border-[var(--color-neon-blue)]/30">
        <p class="text-xs text-[var(--color-text-muted)]">
          <strong v-if="tab === 'catalog'" class="text-[var(--color-neon-blue)]">GET /api/v1/sources/jutsu/catalog</strong>
          <strong v-else class="text-[var(--color-neon-blue)]">GET /api/v1/sources/jutsu/search?q=…</strong>
          — same paginated POST under the hood. Filter slugs round-trip directly from the response shape, so picking
          “Действие” here sends <code>genres=action</code>, exactly the same string you'll see in
          <code>entries[].genres</code>. <code>BY_RATING</code> sort is the website's default and is intentionally
          elided from the URL.
        </p>
      </div>

      <div class="glass-card p-6 mb-6 max-w-5xl mx-auto space-y-4">
        <!-- Filter chips -->
        <div>
          <div class="text-xs text-[var(--color-text-muted)] mb-1">Genres</div>
          <div class="flex flex-wrap gap-1.5">
            <button
              v-for="[slug, label] in GENRES"
              :key="slug"
              class="px-2 py-0.5 rounded text-xs border transition-colors"
              :class="selectedGenres.includes(slug)
                ? 'bg-[var(--color-neon-pink)]/20 text-[var(--color-neon-pink)] border-[var(--color-neon-pink)]/50'
                : 'bg-white/5 text-[var(--color-text-muted)] border-white/10 hover:bg-white/10'"
              @click="toggleGenre(slug)"
            >
              {{ label }}
              <span class="font-mono text-[10px] opacity-60 ml-1">{{ slug }}</span>
            </button>
          </div>
        </div>

        <div>
          <div class="text-xs text-[var(--color-text-muted)] mb-1">Types</div>
          <div class="flex flex-wrap gap-1.5">
            <button
              v-for="[slug, label] in TYPES"
              :key="slug"
              class="px-2 py-0.5 rounded text-xs border transition-colors"
              :class="selectedTypes.includes(slug)
                ? 'bg-[var(--color-neon-blue)]/20 text-[var(--color-neon-blue)] border-[var(--color-neon-blue)]/50'
                : 'bg-white/5 text-[var(--color-text-muted)] border-white/10 hover:bg-white/10'"
              @click="toggleType(slug)"
            >
              {{ label }}
              <span class="font-mono text-[10px] opacity-60 ml-1">{{ slug }}</span>
            </button>
          </div>
        </div>

        <div>
          <div class="text-xs text-[var(--color-text-muted)] mb-1">Years</div>
          <div class="flex flex-wrap gap-1.5">
            <button
              v-for="[slug, label] in YEARS"
              :key="slug"
              class="px-2 py-0.5 rounded text-xs border transition-colors"
              :class="selectedYears.includes(slug)
                ? 'bg-[var(--color-neon-orange)]/20 text-[var(--color-neon-orange)] border-[var(--color-neon-orange)]/50'
                : 'bg-white/5 text-[var(--color-text-muted)] border-white/10 hover:bg-white/10'"
              @click="toggleYear(slug)"
            >
              {{ label }}
              <span class="font-mono text-[10px] opacity-60 ml-1">{{ slug }}</span>
            </button>
          </div>
        </div>

        <div class="grid grid-cols-1 md:grid-cols-3 gap-3">
          <label class="text-xs text-[var(--color-text-muted)] flex flex-col gap-1 md:col-span-1">
            Sort
            <select v-model="selectedSort" class="neon-input text-sm">
              <option v-for="[slug, label] in SORTS" :key="slug || 'default'" :value="slug">
                {{ label }}
              </option>
            </select>
          </label>
          <label class="text-xs text-[var(--color-text-muted)] flex flex-col gap-1 md:col-span-1">
            Page
            <input v-model.number="page" type="number" min="1" class="neon-input" />
          </label>
          <div v-if="tab === 'search'" class="md:col-span-1">
            <label class="text-xs text-[var(--color-text-muted)] flex flex-col gap-1">
              Title query (q)
              <input
                v-model="searchQuery"
                placeholder="наруто"
                class="neon-input text-sm"
                @keyup.enter="runSearch"
              />
            </label>
          </div>
        </div>

        <div class="flex items-center gap-2 flex-wrap pt-1">
          <button
            v-if="tab === 'catalog'"
            class="neon-btn"
            :disabled="catalogLoading"
            @click="loadCatalog"
          >
            <span v-if="catalogLoading" class="inline-block animate-spin mr-1">⟳</span>
            Browse
          </button>
          <button
            v-else
            class="neon-btn"
            :disabled="searchLoading || !searchQuery.trim()"
            @click="runSearch"
          >
            <span v-if="searchLoading" class="inline-block animate-spin mr-1">⟳</span>
            Search
          </button>
          <button
            class="px-3 py-1.5 rounded-lg text-xs bg-white/5 hover:bg-white/10 text-[var(--color-text-muted)]"
            @click="clearFilter"
          >
            Clear filter
          </button>
          <span v-if="filterBadges.length" class="text-xs text-[var(--color-text-muted)] font-mono ml-2">
            {{ filterBadges.join(' · ') }}
          </span>
        </div>
      </div>

      <div v-if="catalogError && tab === 'catalog'" class="glass-card p-4 mb-4 max-w-5xl mx-auto border-[var(--color-neon-red)]/50">
        <p class="text-sm text-[var(--color-neon-red)]">{{ catalogError }}</p>
      </div>
      <div v-if="searchError && tab === 'search'" class="glass-card p-4 mb-4 max-w-5xl mx-auto border-[var(--color-neon-red)]/50">
        <p class="text-sm text-[var(--color-neon-red)]">{{ searchError }}</p>
      </div>

      <!-- Results grid -->
      <div
        v-if="(tab === 'catalog' && catalogResult) || (tab === 'search' && searchResult)"
        class="max-w-6xl mx-auto"
      >
        <div class="flex items-center justify-between mb-3 px-1 text-xs text-[var(--color-text-muted)]">
          <span>
            page {{ tab === 'catalog' ? catalogResult!.page : searchResult!.page }} ·
            {{ entriesGrid(tab === 'catalog' ? catalogResult : searchResult).length }} entries ·
            <span v-if="(tab === 'catalog' ? catalogResult : searchResult)!.hasMore"
              class="text-[var(--color-neon-green)]">more available</span>
            <span v-else>last page</span>
          </span>
          <div class="flex gap-1">
            <button
              class="px-2 py-0.5 rounded bg-white/5 hover:bg-white/10 text-xs"
              :disabled="page <= 1"
              @click="page = Math.max(1, page - 1); tab === 'catalog' ? loadCatalog() : runSearch()"
            >
              ◀ Prev
            </button>
            <button
              class="px-2 py-0.5 rounded bg-white/5 hover:bg-white/10 text-xs"
              :disabled="!(tab === 'catalog' ? catalogResult : searchResult)!.hasMore"
              @click="page += 1; tab === 'catalog' ? loadCatalog() : runSearch()"
            >
              Next ▶
            </button>
          </div>
        </div>

        <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          <div
            v-for="entry in entriesGrid(tab === 'catalog' ? catalogResult : searchResult)"
            :key="entry.slug"
            class="glass-card p-4 flex gap-3"
          >
            <img
              v-if="entry.thumbnailUrl"
              :src="entry.thumbnailUrl"
              loading="lazy"
              class="w-20 h-28 object-cover rounded bg-black/40 flex-shrink-0"
              alt=""
              referrerpolicy="no-referrer"
              @error="($event.target as HTMLImageElement).style.display = 'none'"
            />
            <div class="flex-1 min-w-0">
              <button
                class="text-left font-semibold hover:text-[var(--color-neon-pink)] line-clamp-2"
                @click="jumpToInfo(entry.slug)"
              >
                {{ entry.title }}
              </button>
              <p v-if="entry.originalTitle" class="text-xs text-[var(--color-text-muted)] line-clamp-1">
                {{ entry.originalTitle }}
              </p>
              <p class="text-[11px] text-[var(--color-text-muted)] mt-1">
                <span v-if="entry.year">{{ entry.year }}</span>
                <span v-if="entry.year && (entry.episodeCount || entry.movieCount)"> · </span>
                <span v-if="entry.episodeCount">{{ entry.episodeCount }} episodes</span>
                <span v-else-if="entry.movieCount">{{ entry.movieCount }} movies</span>
              </p>
              <div class="flex flex-wrap gap-1 mt-2">
                <span v-for="g in entry.genres" :key="'g-' + g"
                  class="badge bg-[var(--color-neon-pink)]/15 text-[var(--color-neon-pink)] text-[10px] font-mono">
                  {{ g }}
                </span>
                <span v-for="t in entry.types" :key="'t-' + t"
                  class="badge bg-[var(--color-neon-blue)]/15 text-[var(--color-neon-blue)] text-[10px] font-mono">
                  {{ t }}
                </span>
              </div>
              <div class="flex items-center gap-2 mt-2 text-[10px]">
                <span class="font-mono text-[var(--color-text-muted)] truncate flex-1 min-w-0">{{ entry.slug }}</span>
                <button
                  class="px-1.5 py-0.5 rounded bg-white/5 hover:bg-white/10"
                  @click="jumpToInfo(entry.slug)"
                  title="Open in Anime info tab"
                >
                  📄
                </button>
                <a
                  :href="entry.detailUrl"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="px-1.5 py-0.5 rounded bg-white/5 hover:bg-white/10"
                  title="Open on jut.su"
                >
                  ↗
                </a>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ─────────────────────────── Anime info ─────────────────────────── -->
    <section v-if="tab === 'info'">
      <div class="glass-card p-4 mb-6 max-w-4xl mx-auto border-[var(--color-neon-blue)]/30">
        <p class="text-xs text-[var(--color-text-muted)]">
          <strong class="text-[var(--color-neon-blue)]">GET /api/v1/sources/jutsu/anime/{slug}</strong>
          — full anime info page with all seasons + every episode anchor (green = available, black = premium-gated).
          Try <code>onepuunchman</code>, <code>naruto</code>, <code>shokugyou-kanteishi</code>.
        </p>
      </div>

      <div class="glass-card p-6 mb-6 max-w-4xl mx-auto">
        <form @submit.prevent="loadAnimeInfo" class="flex gap-2">
          <input
            v-model="infoSlug"
            placeholder="onepuunchman"
            class="neon-input flex-1 font-mono text-sm"
          />
          <button class="neon-btn" :disabled="infoLoading || !infoSlug.trim()">
            <span v-if="infoLoading" class="inline-block animate-spin mr-1">⟳</span>
            Fetch
          </button>
        </form>
      </div>

      <div v-if="infoError" class="glass-card p-4 mb-4 max-w-4xl mx-auto border-[var(--color-neon-red)]/50">
        <p class="text-sm text-[var(--color-neon-red)]">{{ infoError }}</p>
      </div>

      <div v-if="infoResult" class="max-w-4xl mx-auto space-y-4">
        <div class="glass-card p-6 flex gap-4">
          <img
            v-if="infoResult.thumbnailUrl"
            :src="infoResult.thumbnailUrl"
            loading="lazy"
            class="w-32 h-44 object-cover rounded bg-black/40 flex-shrink-0"
            alt=""
            referrerpolicy="no-referrer"
            @error="($event.target as HTMLImageElement).style.display = 'none'"
          />
          <div class="flex-1 min-w-0">
            <h2 class="text-2xl font-bold">{{ infoResult.title }}</h2>

            <!-- Labelled info block — mirrors the chrome jut.su renders under the
                 player on every anime page (Жанры / Темы / Годы выпуска /
                 Оригинальное название / Возрастной рейтинг). Each row is suppressed
                 individually when its source field is empty, so older fixtures /
                 cache rows that pre-date the labelled-block migration still render
                 cleanly with whatever subset they have. -->
            <dl class="mt-2 text-sm space-y-1.5">
              <div v-if="infoGenreLabels.length" class="flex gap-1.5 flex-wrap">
                <dt class="text-[var(--color-text-muted)] flex-shrink-0">Жанры:</dt>
                <dd class="text-[var(--color-text-primary)]">
                  {{ joinHumanRu(infoGenreLabels) }}.
                </dd>
              </div>
              <div v-if="infoTypeLabels.length" class="flex gap-1.5 flex-wrap">
                <dt class="text-[var(--color-text-muted)] flex-shrink-0">Темы:</dt>
                <dd class="text-[var(--color-text-primary)]">
                  {{ joinHumanRu(infoTypeLabels) }}.
                </dd>
              </div>
              <div v-if="infoYearsLine" class="flex gap-1.5 flex-wrap">
                <dt class="text-[var(--color-text-muted)] flex-shrink-0">Годы выпуска:</dt>
                <dd class="text-[var(--color-text-primary)]">{{ infoYearsLine }}.</dd>
              </div>
              <div v-if="infoResult.originalTitle" class="flex gap-1.5 flex-wrap">
                <dt class="text-[var(--color-text-muted)] flex-shrink-0">
                  Оригинальное название:
                </dt>
                <dd class="text-[var(--color-text-primary)] font-semibold">
                  {{ infoResult.originalTitle }}
                </dd>
              </div>
              <div v-if="infoResult.ageRating" class="flex items-center gap-1.5 flex-wrap">
                <dt class="text-[var(--color-text-muted)] flex-shrink-0">Возрастной рейтинг:</dt>
                <dd>
                  <span
                    :class="[
                      'inline-block px-2 py-0.5 rounded text-[11px] font-mono font-semibold',
                      ageRatingPillClass,
                    ]"
                  >
                    {{ infoResult.ageRating }}
                  </span>
                </dd>
              </div>
            </dl>

            <p
              v-if="infoResult.synopsis"
              class="text-sm text-[var(--color-text-muted)] mt-3 line-clamp-4 border-t border-white/10 pt-3"
            >
              {{ infoResult.synopsis }}
            </p>

            <!-- Technical breadcrumb at the bottom of the card. slug + episode anchor
                 count are useful for power users debugging through the API; we keep
                 them out of the labelled block above so it stays visually parallel
                 with what jut.su itself renders. -->
            <p class="text-[10px] text-[var(--color-text-muted)] mt-3 font-mono flex items-center gap-2 flex-wrap">
              <span>slug={{ infoResult.slug }}</span>
              <span>·</span>
              <span>{{ infoResult.totalEpisodeCount }} total episode anchors</span>
              <template v-if="infoResult.totalFilmCount > 0">
                <span>·</span>
                <span>{{ infoResult.totalFilmCount }} {{ filmsCountSuffix(infoResult.totalFilmCount) }}</span>
              </template>
              <span v-if="infoResult.genres.length || infoResult.types.length">·</span>
              <span
                v-for="g in infoResult.genres"
                :key="'g-' + g"
                class="badge bg-[var(--color-neon-pink)]/10 text-[var(--color-neon-pink)] text-[10px] font-mono"
                :title="genreLabel(g)"
              >
                {{ g }}
              </span>
              <span
                v-for="t in infoResult.types"
                :key="'t-' + t"
                class="badge bg-[var(--color-neon-blue)]/10 text-[var(--color-neon-blue)] text-[10px] font-mono"
                :title="typeLabel(t)"
              >
                {{ t }}
              </span>
            </p>
          </div>
        </div>

        <div v-for="season in infoResult.seasons" :key="season.index" class="glass-card p-4">
          <div class="flex items-center justify-between mb-3 flex-wrap gap-2">
            <h3 class="font-semibold">{{ season.name }}</h3>
            <span class="text-[10px] text-[var(--color-text-muted)] font-mono">
              {{ season.episodeCount }} episodes · season index #{{ season.index }}
            </span>
          </div>
          <div class="grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-6 gap-2">
            <button
              v-for="ep in season.episodes"
              :key="`${season.index}-${ep.episode}`"
              class="px-2 py-1.5 rounded text-xs bg-white/5 hover:bg-[var(--color-neon-orange)]/20 hover:text-[var(--color-neon-orange)] transition-colors text-left"
              @click="jumpToEpisode(ep.url)"
            >
              <div class="font-mono">S{{ ep.season }}E{{ ep.episode }}</div>
              <div class="text-[10px] text-[var(--color-text-muted)] truncate">{{ ep.label }}</div>
            </button>
          </div>
        </div>

        <!--
          Films block: rendered under the seasons grid when the API returns any. Mirrors jut.su's
          own "Полнометражные фильмы" heading. Films share the episode-meta endpoint via
          `jumpToEpisode(...)` because the per-film URL (`/{slug}/film-N.html`) is what the
          backend resolves to a player chrome / decode session.
        -->
        <div
          v-if="infoResult.films && infoResult.films.length > 0"
          class="glass-card p-4 border-[var(--color-neon-purple)]/40"
        >
          <div class="flex items-center justify-between mb-3 flex-wrap gap-2">
            <h3 class="font-semibold">Полнометражные фильмы</h3>
            <span class="text-[10px] text-[var(--color-text-muted)] font-mono">
              {{ infoResult.totalFilmCount }} {{ filmsCountSuffix(infoResult.totalFilmCount) }}
            </span>
          </div>
          <div class="grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-6 gap-2">
            <button
              v-for="film in infoResult.films"
              :key="`film-${film.index}`"
              class="px-2 py-1.5 rounded text-xs bg-white/5 hover:bg-[var(--color-neon-purple)]/20 hover:text-[var(--color-neon-purple)] transition-colors text-left"
              @click="jumpToEpisode(film.url)"
            >
              <div class="font-mono">F{{ film.index }}</div>
              <div class="text-[10px] text-[var(--color-text-muted)] truncate">{{ film.label }}</div>
            </button>
          </div>
        </div>
      </div>
    </section>

    <!-- ─────────────────────────── Episode meta ─────────────────────────── -->
    <section v-if="tab === 'episode'">
      <div class="glass-card p-4 mb-6 max-w-3xl mx-auto border-[var(--color-neon-blue)]/30">
        <p class="text-xs text-[var(--color-text-muted)]">
          <strong class="text-[var(--color-neon-blue)]">GET /api/v1/sources/jutsu/episode?url=…</strong>
          for the chrome metadata (title / thumbnail / paywall flag),
          <strong class="text-[var(--color-neon-blue)]">POST /api/v1/sources/jutsu/decode</strong>
          for the actual mp4 URLs.
          jut.su CDN URLs are session-bound (Yandex CDN gives 403 to any other session), so playback and
          download go through the backend's <code>/api/v1/sources/jutsu/stream</code> proxy.
        </p>
      </div>

      <div class="glass-card p-6 mb-6 max-w-3xl mx-auto">
        <form @submit.prevent="loadEpisodeMeta" class="space-y-3">
          <input
            v-model="episodeUrl"
            placeholder="https://jut.su/onepuunchman/season-1/episode-1.html"
            class="neon-input w-full font-mono text-sm"
          />
          <button class="neon-btn w-full" :disabled="episodeLoading || !episodeUrl.trim()">
            <span v-if="episodeLoading" class="inline-block animate-spin mr-1">⟳</span>
            Fetch metadata
          </button>
        </form>
      </div>

      <div v-if="episodeError" class="glass-card p-4 mb-4 max-w-3xl mx-auto border-[var(--color-neon-red)]/50">
        <p class="text-sm text-[var(--color-neon-red)]">{{ episodeError }}</p>
      </div>

      <div v-if="episodeResult" class="glass-card p-6 max-w-3xl mx-auto">
        <div class="flex gap-4">
          <img
            v-if="episodeResult.thumbnailUrl"
            :src="episodeResult.thumbnailUrl"
            loading="lazy"
            class="w-32 h-20 object-cover rounded bg-black/40 flex-shrink-0"
            alt=""
            referrerpolicy="no-referrer"
            @error="($event.target as HTMLImageElement).style.display = 'none'"
          />
          <div class="flex-1 min-w-0">
            <h3 class="font-semibold">{{ episodeResult.displayTitle }}</h3>
            <p class="text-xs text-[var(--color-text-muted)] mt-1 truncate">
              {{ episodeResult.pageTitle }}
            </p>
            <div class="flex flex-wrap items-center gap-2 mt-2">
              <span class="badge bg-white/5 text-[var(--color-neon-blue)] font-mono text-[10px]">
                {{ episodeBadge }}
              </span>
              <span
                v-if="episodeResult.kind === 'film'"
                class="badge bg-[var(--color-neon-purple)]/15 text-[var(--color-neon-purple)] text-[10px]"
              >
                🎬 {{ episodeKindLabel }}
              </span>
              <span
                v-if="episodeResult.premiumGated"
                class="badge bg-[var(--color-neon-orange)]/15 text-[var(--color-neon-orange)] text-[10px]"
              >
                ⛔ Premium gated
              </span>
              <span
                v-else
                class="badge bg-[var(--color-neon-green)]/15 text-[var(--color-neon-green)] text-[10px]"
              >
                ✓ Free
              </span>
            </div>
          </div>
        </div>

        <div class="mt-4 space-y-1.5 text-xs">
          <div class="flex items-center gap-2 flex-wrap">
            <span class="text-[var(--color-text-muted)]">Slug:</span>
            <button
              class="font-mono text-[var(--color-neon-pink)] hover:underline"
              @click="jumpToInfo(episodeResult.slug)"
            >
              {{ episodeResult.slug }}
            </button>
          </div>
          <div class="flex items-center gap-2">
            <span class="text-[var(--color-text-muted)]">Canonical:</span>
            <a :href="episodeResult.canonicalUrl" target="_blank" rel="noopener noreferrer"
              class="font-mono text-[var(--color-neon-blue)] truncate flex-1 hover:underline">
              {{ episodeResult.canonicalUrl }}
            </a>
            <button
              class="px-1.5 py-0.5 rounded bg-white/5 hover:bg-white/10"
              @click="copy(episodeResult.canonicalUrl)"
              title="Copy"
            >
              📋
            </button>
          </div>
          <div v-if="episodePrevUrl" class="flex items-center gap-2">
            <span class="text-[var(--color-text-muted)]">Prev:</span>
            <button
              class="font-mono text-[var(--color-neon-blue)] hover:underline truncate"
              @click="jumpToEpisode(episodePrevUrl!)"
            >
              {{ episodePrevUrl }}
            </button>
          </div>
          <div v-if="episodeNextUrl" class="flex items-center gap-2">
            <span class="text-[var(--color-text-muted)]">Next:</span>
            <button
              class="font-mono text-[var(--color-neon-blue)] hover:underline truncate"
              @click="jumpToEpisode(episodeNextUrl!)"
            >
              {{ episodeNextUrl }}
            </button>
          </div>
          <div v-if="episodeResult.allEpisodesUrl" class="flex items-center gap-2">
            <span class="text-[var(--color-text-muted)]">All eps:</span>
            <a :href="episodeResult.allEpisodesUrl" target="_blank" rel="noopener noreferrer"
              class="font-mono text-[var(--color-neon-blue)] truncate flex-1 hover:underline">
              {{ episodeResult.allEpisodesUrl }}
            </a>
          </div>
        </div>

        <div class="mt-6 pt-4 border-t border-white/10">
          <div class="flex items-center justify-between mb-3 flex-wrap gap-2">
            <h4 class="text-sm font-semibold text-[var(--color-text-primary)]">
              🎞️ Video URL
            </h4>
            <span
              v-if="episodeResult.premiumGated"
              class="text-[10px] text-[var(--color-neon-orange)] font-mono"
            >
              premium-gated — needs JUTSU_USERNAME/JUTSU_PASSWORD configured on the server
            </span>
          </div>
          <button
            class="neon-btn w-full"
            :disabled="decodeLoading || !episodeUrl.trim()"
            @click="decodeCurrentEpisode"
          >
            <span v-if="decodeLoading" class="inline-block animate-spin mr-1">⟳</span>
            {{ decodeLoading ? 'Decoding…' : '⬇ Decode video URL(s)' }}
          </button>
          <p class="text-[10px] text-[var(--color-text-muted)] mt-2">
            Calls the same stateless decoder as the Sources → Provider sandbox tab. No DB write.
            Each call costs one outbound request against the jut.su 1 RPS budget.
          </p>
        </div>
      </div>

      <div v-if="decodeError" class="glass-card p-4 my-4 max-w-3xl mx-auto border-[var(--color-neon-red)]/50">
        <p class="text-sm text-[var(--color-neon-red)]">{{ decodeError }}</p>
      </div>

      <div v-if="decodeResult" class="glass-card p-4 mt-4 max-w-3xl mx-auto">
        <div class="flex items-center justify-between mb-3 flex-wrap gap-2">
          <span class="badge bg-[var(--color-neon-orange)]/15 text-[var(--color-neon-orange)] border border-[var(--color-neon-orange)]/40">
            JUTSU
          </span>
          <span
            v-if="decodeResult.success"
            class="badge bg-[var(--color-neon-green)]/15 text-[var(--color-neon-green)]"
          >
            ✓ Success
          </span>
          <span
            v-else
            class="badge bg-[var(--color-neon-red)]/15 text-[var(--color-neon-red)]"
          >
            ✗ {{ decodeResult.errorCode ?? 'Failure' }}
          </span>
          <span v-if="decodeResult.format" class="text-xs text-[var(--color-text-muted)]">
            format: <span class="font-mono text-[var(--color-neon-blue)]">{{ decodeResult.format }}</span>
          </span>
        </div>

        <div v-if="decodeResult.success" class="space-y-2">
          <div
            v-for="(url, q) in decodeResult.qualities"
            :key="q"
            class="flex flex-col gap-2 p-2 rounded bg-white/5"
          >
            <div class="flex items-center justify-between gap-3 flex-wrap">
              <span class="badge bg-white/5 text-[var(--color-neon-pink)] font-mono">{{ q }}</span>
              <span class="text-[10px] font-mono text-[var(--color-text-muted)] break-all flex-1 min-w-0">
                {{ url }}
              </span>
              <div class="flex items-center gap-1">
                <button
                  v-if="isJutsuCdnUrl(url) && downloads[url]?.status !== 'downloading'"
                  @click="playInPlayer(url)"
                  class="px-2 py-0.5 text-[10px] rounded bg-[var(--color-neon-orange)]/20 hover:bg-[var(--color-neon-orange)]/35 text-[var(--color-neon-orange)] border border-[var(--color-neon-orange)]/40"
                  title="Play through backend proxy (required; raw URL gives 403)"
                >
                  ▶ Play
                </button>
                <button
                  v-if="isJutsuCdnUrl(url) && downloads[url]?.status !== 'downloading'"
                  @click="startDownload(url)"
                  class="px-2 py-0.5 text-[10px] rounded bg-[var(--color-neon-blue)]/20 hover:bg-[var(--color-neon-blue)]/35 text-[var(--color-neon-blue)] border border-[var(--color-neon-blue)]/40"
                  title="Download MP4 through backend proxy with progress"
                >
                  ⬇ Download
                </button>
                <button
                  v-if="downloads[url]?.status === 'downloading'"
                  @click="cancelDownload(url)"
                  class="px-2 py-0.5 text-[10px] rounded bg-[var(--color-neon-red)]/20 hover:bg-[var(--color-neon-red)]/35 text-[var(--color-neon-red)] border border-[var(--color-neon-red)]/40"
                  title="Cancel download"
                >
                  ✕ Cancel
                </button>
                <button
                  @click="copy(url)"
                  class="px-2 py-0.5 text-[10px] rounded bg-white/5 hover:bg-white/10 text-[var(--color-text-muted)]"
                  title="Copy URL"
                >
                  📋
                </button>
              </div>
            </div>

            <div v-if="downloads[url]" class="flex flex-col gap-1">
              <div
                v-if="downloads[url].status === 'downloading'"
                class="flex items-center gap-2 text-[11px] text-[var(--color-text-muted)]"
              >
                <div class="flex-1 h-1.5 rounded bg-white/10 overflow-hidden">
                  <div
                    class="h-full bg-[var(--color-neon-blue)] transition-all"
                    :style="{ width: downloadProgressPct(downloads[url]) + '%' }"
                  />
                </div>
                <span class="font-mono whitespace-nowrap">
                  {{ downloadProgressPct(downloads[url]) }}% ·
                  {{ fmtBytes(downloads[url].received) }}<span v-if="downloads[url].total"> / {{ fmtBytes(downloads[url].total) }}</span>
                </span>
                <span v-if="downloadEta(downloads[url])" class="font-mono whitespace-nowrap text-[var(--color-text-muted)]/70">
                  {{ downloadEta(downloads[url]) }}
                </span>
              </div>
              <div
                v-else-if="downloads[url].status === 'done'"
                class="flex items-center justify-between gap-2 text-[11px]"
              >
                <span class="text-[var(--color-neon-green)] font-mono">
                  ✓ Downloaded {{ fmtBytes(downloads[url].received) }}
                </span>
                <button
                  @click="clearDownload(url)"
                  class="text-[var(--color-text-muted)] hover:text-white text-xs"
                >
                  ×
                </button>
              </div>
              <div
                v-else-if="downloads[url].status === 'error'"
                class="flex items-center justify-between gap-2 text-[11px]"
              >
                <span class="text-[var(--color-neon-red)] font-mono">
                  ✗ {{ downloads[url].errorMessage }}
                </span>
                <button
                  @click="clearDownload(url)"
                  class="text-[var(--color-text-muted)] hover:text-white text-xs"
                >
                  ×
                </button>
              </div>
              <div
                v-else-if="downloads[url].status === 'cancelled'"
                class="flex items-center justify-between gap-2 text-[11px]"
              >
                <span class="text-[var(--color-text-muted)] font-mono">
                  Cancelled at {{ fmtBytes(downloads[url].received) }}
                </span>
                <button
                  @click="clearDownload(url)"
                  class="text-[var(--color-text-muted)] hover:text-white text-xs"
                >
                  ×
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div v-if="playerUrl" id="jutsu-episode-inline-player" class="max-w-3xl mx-auto mt-6">
        <div class="glass-card p-4">
          <div class="flex items-center justify-between mb-2">
            <span class="text-sm text-[var(--color-text-muted)]">
              Inline player — proxied via <span class="font-mono text-[var(--color-neon-blue)]">/api/v1/sources/jutsu/stream</span>
            </span>
            <button
              @click="closePlayer"
              class="px-2 py-0.5 text-xs rounded bg-white/5 hover:bg-white/10 text-[var(--color-text-muted)]"
            >
              ✕ Close
            </button>
          </div>
          <video
            :src="playerUrl"
            controls
            preload="metadata"
            class="w-full max-h-[480px] rounded bg-black"
          />
        </div>
      </div>
    </section>

    <!-- ─────────────────────────── Notice feed ─────────────────────────── -->
    <section v-if="tab === 'notice'">
      <div class="glass-card p-4 mb-6 max-w-4xl mx-auto border-[var(--color-neon-blue)]/30">
        <p class="text-xs text-[var(--color-text-muted)]">
          <strong class="text-[var(--color-neon-blue)]">GET /api/v1/sources/jutsu/notice</strong>
          (single page) and
          <strong class="text-[var(--color-neon-blue)]">GET …/notice/stream</strong>
          (NDJSON walk back). Each page is one ajax POST against jut.su's <code>site_notice.php</code> backed by the
          1 RPS budget — keep <code>maxFeeds</code> small.
        </p>
      </div>

      <div class="glass-card p-6 mb-6 max-w-4xl mx-auto space-y-3">
        <div class="grid grid-cols-1 md:grid-cols-3 gap-3">
          <label class="text-xs text-[var(--color-text-muted)] flex flex-col gap-1 md:col-span-2">
            Cursor (notice_id) — leave empty for latest
            <input
              v-model="noticeCursor"
              placeholder="e.g. 18729"
              class="neon-input font-mono text-sm"
              type="number"
              inputmode="numeric"
            />
          </label>
          <label class="text-xs text-[var(--color-text-muted)] flex flex-col gap-1">
            Stream depth (max feeds)
            <input
              v-model.number="noticeStreamMaxFeeds"
              type="number"
              min="1"
              max="20"
              class="neon-input"
            />
          </label>
        </div>
        <div class="flex items-center gap-2 flex-wrap">
          <button class="neon-btn" :disabled="noticeLoading" @click="loadNoticeFeed">
            <span v-if="noticeLoading" class="inline-block animate-spin mr-1">⟳</span>
            {{ noticeCursor ? 'Fetch page' : 'Fetch latest' }}
          </button>
          <button
            v-if="!noticeStreaming"
            class="px-3 py-1.5 rounded-lg text-sm bg-[var(--color-neon-blue)]/15 hover:bg-[var(--color-neon-blue)]/25 text-[var(--color-neon-blue)] border border-[var(--color-neon-blue)]/30"
            :disabled="!noticeResult"
            @click="startNoticeStream"
          >
            ⤓ Stream {{ noticeStreamMaxFeeds }} pages backwards
          </button>
          <button
            v-else
            class="px-3 py-1.5 rounded-lg text-sm bg-[var(--color-neon-red)]/15 hover:bg-[var(--color-neon-red)]/25 text-[var(--color-neon-red)] border border-[var(--color-neon-red)]/30"
            @click="abortNoticeStream"
          >
            ✕ Abort stream
          </button>
        </div>
      </div>

      <div v-if="noticeError" class="glass-card p-4 mb-4 max-w-4xl mx-auto border-[var(--color-neon-red)]/50">
        <p class="text-sm text-[var(--color-neon-red)]">{{ noticeError }}</p>
      </div>

      <div v-if="noticeResult" class="max-w-4xl mx-auto space-y-3">
        <div class="text-xs text-[var(--color-text-muted)] flex items-center justify-between px-1">
          <span>
            cursor={{ noticeResult.requestedCursor }} ·
            {{ noticeResult.entries.length }} entries ·
            <span v-if="noticeResult.nextCursor != null">next={{ noticeResult.nextCursor }}</span>
            <span v-else class="text-[var(--color-neon-orange)]">history bound</span>
          </span>
          <button
            v-if="noticeResult.nextCursor != null"
            class="px-2 py-0.5 rounded bg-white/5 hover:bg-white/10 text-xs"
            @click="noticeCursor = String(noticeResult!.nextCursor); loadNoticeFeed()"
          >
            Walk back ▶
          </button>
        </div>
        <div class="space-y-2">
          <div
            v-for="entry in noticeResult.entries"
            :key="entry.episodeUrl"
            class="glass-card p-3 flex gap-3"
          >
            <img
              v-if="entry.thumbnailUrl"
              :src="entry.thumbnailUrl"
              loading="lazy"
              class="w-16 h-16 object-cover rounded bg-black/40 flex-shrink-0"
              alt=""
              referrerpolicy="no-referrer"
              @error="($event.target as HTMLImageElement).style.display = 'none'"
            />
            <div class="flex-1 min-w-0">
              <button
                class="text-left font-medium hover:text-[var(--color-neon-pink)] line-clamp-1"
                @click="jumpToEpisode(entry.episodeUrl)"
              >
                {{ entry.title }}
              </button>
              <p class="text-[11px] text-[var(--color-text-muted)] mt-0.5">
                <span class="font-mono">S{{ entry.season }}E{{ entry.episode }}</span> ·
                slug=<button
                  class="font-mono text-[var(--color-neon-pink)] hover:underline"
                  @click="jumpToInfo(entry.slug)"
                >{{ entry.slug }}</button>
              </p>
              <p class="text-[10px] text-[var(--color-text-muted)] mt-0.5">
                {{ entry.relativeDate }}
              </p>
            </div>
          </div>
        </div>
      </div>

      <div v-if="noticeStreamEntries.length" class="max-w-4xl mx-auto mt-6">
        <div class="text-xs text-[var(--color-text-muted)] mb-2 flex items-center gap-2 px-1">
          <span class="text-[var(--color-neon-blue)]">⤓ NDJSON stream</span>
          <span>·</span>
          <span>{{ noticeStreamEntries.length }} entries collected</span>
          <span v-if="noticeStreaming" class="inline-block animate-pulse text-[var(--color-neon-blue)]">streaming…</span>
        </div>
        <div class="glass-card p-3 max-h-96 overflow-y-auto space-y-1.5 text-[11px]">
          <div
            v-for="(entry, idx) in noticeStreamEntries"
            :key="idx + '-' + entry.episodeUrl"
            class="flex items-center gap-2 p-1.5 rounded hover:bg-white/5"
          >
            <span class="font-mono text-[var(--color-text-muted)]">#{{ idx + 1 }}</span>
            <button
              class="font-mono text-[var(--color-neon-pink)] hover:underline"
              @click="jumpToInfo(entry.slug)"
            >{{ entry.slug }}</button>
            <span class="font-mono">S{{ entry.season }}E{{ entry.episode }}</span>
            <span class="text-[var(--color-text-muted)] truncate flex-1">{{ entry.title }}</span>
            <span class="text-[var(--color-text-muted)] whitespace-nowrap">{{ entry.relativeDate }}</span>
          </div>
        </div>
      </div>
    </section>

    <!-- ─────────────────────────── Drift snapshot ─────────────────────────── -->
    <section v-if="tab === 'drift'">
      <div class="glass-card p-4 mb-6 max-w-4xl mx-auto border-[var(--color-neon-blue)]/30">
        <p class="text-xs text-[var(--color-text-muted)]">
          <strong class="text-[var(--color-neon-blue)]">GET /api/v1/sources/jutsu/drift</strong>
          — current SDK schema-drift snapshot. Populated by every parser at request time and by the canary
          <code>JutsuDriftScheduledProbe</code>. <code>MultiSourceController</code> auto-demотирует jut.su
          в ranker'е, когда health ≠ HEALTHY (см. ADR 0015).
        </p>
      </div>

      <div v-if="driftError" class="glass-card p-4 mb-4 max-w-4xl mx-auto border-[var(--color-neon-red)]/50">
        <p class="text-sm text-[var(--color-neon-red)]">{{ driftError }}</p>
      </div>

      <div v-if="driftSnapshot" class="max-w-4xl mx-auto space-y-4">
        <div class="grid grid-cols-1 md:grid-cols-3 gap-4">
          <div class="glass-card p-4">
            <p class="text-[10px] text-[var(--color-text-muted)] uppercase mb-1">Health</p>
            <p :class="['text-3xl font-bold font-mono', driftHealthColor]">
              {{ driftSnapshot.health }}
            </p>
            <p class="text-[10px] text-[var(--color-text-muted)] mt-2">
              Captured: {{ fmtTimestamp(driftSnapshot.capturedAt) }}
            </p>
          </div>
          <div class="glass-card p-4">
            <p class="text-[10px] text-[var(--color-text-muted)] uppercase mb-1">Window</p>
            <p class="text-3xl font-bold font-mono text-[var(--color-neon-blue)]">
              {{ driftSnapshot.eventsInWindow }}/{{ driftSnapshot.windowSize }}
            </p>
            <p class="text-[10px] text-[var(--color-text-muted)] mt-2">events in recent window</p>
          </div>
          <div class="glass-card p-4">
            <p class="text-[10px] text-[var(--color-text-muted)] uppercase mb-1">Lifetime</p>
            <p class="text-3xl font-bold font-mono text-[var(--color-text-primary)]">
              {{ driftSnapshot.lifetimeEvents }}
            </p>
            <p class="text-[10px] text-[var(--color-text-muted)] mt-2">total events ever observed</p>
          </div>
        </div>

        <div v-if="Object.keys(driftSnapshot.bySignal).length" class="glass-card p-4">
          <p class="text-xs text-[var(--color-text-muted)] mb-3">By signal type (current window):</p>
          <div class="flex flex-wrap gap-2">
            <span
              v-for="(count, signal) in driftSnapshot.bySignal"
              :key="signal"
              class="badge bg-[var(--color-neon-orange)]/15 text-[var(--color-neon-orange)] font-mono text-[10px]"
            >
              {{ signal }} × {{ count }}
            </span>
          </div>
        </div>

        <div v-if="driftSnapshot.recentEvents.length" class="glass-card p-4">
          <p class="text-xs text-[var(--color-text-muted)] mb-3">
            Recent events (oldest first):
          </p>
          <div class="space-y-2">
            <div
              v-for="(event, idx) in driftSnapshot.recentEvents"
              :key="idx + '-' + event.timestamp"
              class="p-2 rounded bg-white/5 text-[11px]"
            >
              <div class="flex items-center gap-2 flex-wrap">
                <span class="badge bg-[var(--color-neon-orange)]/15 text-[var(--color-neon-orange)] font-mono text-[10px]">
                  {{ event.signal }}
                </span>
                <span class="font-mono text-[var(--color-text-muted)]">{{ event.source }}</span>
                <span class="text-[var(--color-text-muted)]">{{ fmtTimestamp(event.timestamp) }}</span>
              </div>
              <p class="mt-1 text-[var(--color-text-primary)]">{{ event.detail }}</p>
              <p v-if="event.selector" class="mt-1 font-mono text-[10px] text-[var(--color-text-muted)]">
                selector: {{ event.selector }}
              </p>
              <p v-if="event.fixtureRef" class="font-mono text-[10px] text-[var(--color-text-muted)]">
                fixture: {{ event.fixtureRef }}
              </p>
            </div>
          </div>
        </div>

        <div v-else class="glass-card p-6 text-center text-[var(--color-text-muted)]">
          <span class="text-3xl block mb-2">✨</span>
          <p>No drift events recorded — all SDK parsers are matching their fixtures cleanly.</p>
        </div>
      </div>
    </section>
  </div>
</template>
