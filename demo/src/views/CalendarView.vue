<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '../api/client'
import type { CalendarFilter, CalendarResponse, EnrichedCalendarEntry } from '../api/types'

const SHIKIMORI_BASE = 'https://shikimori.one'

const STATUS_OPTIONS = [
  { value: '', label: 'all' },
  { value: 'ongoing', label: 'ongoing' },
  { value: 'anons', label: 'anons' },
  { value: 'released', label: 'released' },
  { value: 'latest', label: 'latest' },
]

const KIND_OPTIONS = [
  { value: '', label: 'all' },
  { value: 'tv', label: 'tv' },
  { value: 'movie', label: 'movie' },
  { value: 'ova', label: 'ova' },
  { value: 'ona', label: 'ona' },
  { value: 'special', label: 'special' },
]

const status = ref('ongoing')
const kind = ref('')
const minScore = ref<number | null>(null)
const limit = ref<number | null>(null)
const enrich = ref(true)
const loading = ref(false)
const error = ref('')
const data = ref<CalendarResponse | null>(null)
const lastLoaded = ref('')

const buckets = computed(() => {
  const list = data.value?.entries ?? []
  const groups = new Map<string, EnrichedCalendarEntry[]>()
  for (const item of list) {
    const date = bucketDateOf(item)
    const arr = groups.get(date) ?? []
    arr.push(item)
    groups.set(date, arr)
  }
  return Array.from(groups.entries())
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    .map(([date, entries]) => ({ date, entries }))
})

function bucketDateOf(item: EnrichedCalendarEntry): string {
  const ts = item.entry.nextEpisodeAt
  if (!ts) return '—'
  return ts.slice(0, 10)
}

function formatDate(date: string): string {
  if (date === '—') return 'No schedule'
  const d = new Date(date + 'T00:00:00Z')
  return d.toLocaleDateString(undefined, {
    weekday: 'long',
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  })
}

function formatTime(iso: string | null): string {
  if (!iso) return '—'
  const d = new Date(iso)
  return d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
}

function startOfLocalDay(d: Date): number {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
}

function formatRelativeDay(iso: string | null): string {
  if (!iso) return ''
  const target = new Date(iso)
  const now = new Date()
  const diffDays = Math.round((startOfLocalDay(target) - startOfLocalDay(now)) / 86400000)
  if (diffDays === 0) return 'today'
  if (diffDays === 1) return 'tomorrow'
  if (diffDays === -1) return 'yesterday'
  if (diffDays > 1 && diffDays <= 6) {
    return target.toLocaleDateString(undefined, { weekday: 'short' })
  }
  return target.toLocaleDateString(undefined, { day: 'numeric', month: 'short' })
}

function formatCountdown(iso: string | null): string {
  if (!iso) return ''
  const diffMs = new Date(iso).getTime() - Date.now()
  if (diffMs <= 0) return 'aired'
  const minutes = Math.floor(diffMs / 60000)
  if (minutes < 60) return `in ${minutes}m`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) {
    const rem = minutes % 60
    return rem ? `in ${hours}h ${rem}m` : `in ${hours}h`
  }
  const days = Math.floor(hours / 24)
  return days < 14 ? `in ${days}d` : ''
}

function formatShortDate(value: string | null): string {
  if (!value) return ''
  const d = new Date(value.length === 10 ? value + 'T00:00:00Z' : value)
  if (Number.isNaN(d.getTime())) return value
  return d.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })
}

function progressLabel(item: EnrichedCalendarEntry): string {
  const aired = item.entry.episodesAired
  const total = item.entry.episodes
  if (aired == null && (total == null || total === 0)) return ''
  if (total && total > 0) return `${aired ?? 0} / ${total} ep`
  return aired != null ? `${aired} ep aired` : ''
}

function posterUrl(entry: EnrichedCalendarEntry): string | null {
  const path = entry.entry.anime.image?.preview ?? entry.entry.anime.image?.original
  if (!path) return null
  if (path.startsWith('http')) return path
  return SHIKIMORI_BASE + path
}

function buildFilter(): CalendarFilter {
  return {
    status: status.value || undefined,
    kind: kind.value || undefined,
    minScore: minScore.value ?? undefined,
    limit: limit.value ?? undefined,
    enrich: enrich.value,
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await api.getCalendar(buildFilter())
    lastLoaded.value = new Date().toLocaleTimeString()
  } catch (e: any) {
    error.value = e.message ?? String(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-6 flex-wrap gap-3">
      <h1 class="text-3xl font-bold">
        <span class="gradient-text">Premiere Calendar</span>
      </h1>
      <div class="flex items-center gap-3 text-xs text-[var(--color-text-muted)]">
        <span v-if="data">
          fetched: <span class="text-white">{{ new Date(data.fetchedAt).toLocaleString() }}</span>
        </span>
        <span v-if="data?.etag">
          etag: <span class="font-mono text-[var(--color-neon-blue)]">{{ data.etag }}</span>
        </span>
        <button class="neon-btn text-sm" :disabled="loading" @click="load">
          {{ loading ? 'Loading…' : 'Reload' }}
        </button>
      </div>
    </div>

    <p class="text-sm text-[var(--color-text-muted)] mb-4">
      On-demand fetch of <code class="font-mono">https://dumps.kodikres.com/calendar.json</code>,
      cached server-side for 5 minutes (conditional GET via ETag). Filters apply after the cache
      hit, so no upstream re-fetch is needed.
    </p>

    <div class="glass-card p-4 mb-6">
      <div class="flex flex-wrap items-end gap-4">
        <div>
          <label class="block text-xs text-[var(--color-text-muted)] mb-1">Status</label>
          <div class="flex gap-1 flex-wrap">
            <button
              v-for="opt in STATUS_OPTIONS"
              :key="opt.value"
              class="px-3 py-1 rounded-md text-xs border"
              :class="status === opt.value
                ? 'border-[var(--color-neon-pink)] text-[var(--color-neon-pink)]'
                : 'border-white/10 text-[var(--color-text-muted)] hover:text-white'"
              @click="status = opt.value"
            >
              {{ opt.label }}
            </button>
          </div>
        </div>
        <div>
          <label class="block text-xs text-[var(--color-text-muted)] mb-1">Kind</label>
          <div class="flex gap-1 flex-wrap">
            <button
              v-for="opt in KIND_OPTIONS"
              :key="opt.value"
              class="px-3 py-1 rounded-md text-xs border"
              :class="kind === opt.value
                ? 'border-[var(--color-neon-pink)] text-[var(--color-neon-pink)]'
                : 'border-white/10 text-[var(--color-text-muted)] hover:text-white'"
              @click="kind = opt.value"
            >
              {{ opt.label }}
            </button>
          </div>
        </div>
        <div>
          <label class="block text-xs text-[var(--color-text-muted)] mb-1">Min score</label>
          <input
            v-model.number="minScore"
            type="number"
            min="0"
            max="10"
            step="0.1"
            class="w-24 px-2 py-1 rounded-md bg-white/5 border border-white/10 text-sm outline-none focus:border-[var(--color-neon-pink)]/60"
          />
        </div>
        <div>
          <label class="block text-xs text-[var(--color-text-muted)] mb-1">Limit</label>
          <input
            v-model.number="limit"
            type="number"
            min="1"
            class="w-24 px-2 py-1 rounded-md bg-white/5 border border-white/10 text-sm outline-none focus:border-[var(--color-neon-pink)]/60"
          />
        </div>
        <label class="flex items-center gap-2 text-sm text-[var(--color-text-muted)]">
          <input
            type="checkbox"
            v-model="enrich"
            class="accent-[var(--color-neon-pink)]"
          />
          Enrich with library
        </label>
        <button class="neon-btn text-sm ml-auto" :disabled="loading" @click="load">Apply</button>
      </div>
    </div>

    <div v-if="error" class="glass-card p-4 mb-4 border border-[var(--color-neon-red)]/40">
      <p class="text-sm text-[var(--color-neon-red)]">{{ error }}</p>
    </div>

    <div v-if="loading && !data" class="grid grid-cols-1 md:grid-cols-2 gap-4">
      <div v-for="i in 6" :key="i" class="skeleton h-40 w-full" />
    </div>

    <div v-else-if="data && data.entries.length === 0" class="glass-card p-6 text-center">
      <p class="text-sm text-[var(--color-text-muted)]">
        No entries match the current filters. Loosen the filters or reload.
      </p>
    </div>

    <div v-else-if="data" class="space-y-8">
      <div v-for="bucket in buckets" :key="bucket.date">
        <h2 class="text-sm uppercase tracking-wider text-[var(--color-text-muted)] mb-3">
          {{ formatDate(bucket.date) }}
          <span class="ml-2 text-xs">({{ bucket.entries.length }})</span>
        </h2>
        <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          <article
            v-for="item in bucket.entries"
            :key="item.entry.anime.id + ':' + item.entry.nextEpisodeAt"
            class="glass-card p-4 flex gap-4 items-start"
          >
            <img
              v-if="posterUrl(item)"
              :src="posterUrl(item)!"
              :alt="item.entry.anime.russian || item.entry.anime.name"
              class="w-16 h-24 object-cover rounded-md border border-white/10 shrink-0"
              loading="lazy"
            />
            <div v-else class="w-16 h-24 rounded-md bg-white/5 border border-white/10 shrink-0" />
            <div class="min-w-0 flex-1">
              <h3 class="text-base font-semibold truncate" :title="item.entry.anime.russian || item.entry.anime.name">
                {{ item.entry.anime.russian || item.entry.anime.name }}
              </h3>
              <p class="text-xs text-[var(--color-text-muted)] truncate" :title="item.entry.anime.name">
                {{ item.entry.anime.name }}
              </p>
              <div class="flex flex-wrap gap-2 mt-2 text-xs">
                <span class="px-2 py-0.5 rounded bg-white/5 border border-white/10">
                  {{ item.entry.kind || '?' }}
                </span>
                <span
                  class="px-2 py-0.5 rounded border"
                  :class="item.entry.status === 'ongoing'
                    ? 'border-[var(--color-neon-pink)]/40 text-[var(--color-neon-pink)]'
                    : item.entry.status === 'anons'
                      ? 'border-[var(--color-neon-blue)]/40 text-[var(--color-neon-blue)]'
                      : 'border-white/10 text-[var(--color-text-muted)]'"
                >
                  {{ item.entry.status || '—' }}
                </span>
                <span v-if="item.entry.score != null && item.entry.score > 0"
                      class="px-2 py-0.5 rounded bg-white/5 border border-white/10">
                  ★ {{ item.entry.score.toFixed(2) }}
                </span>
              </div>

              <div
                v-if="item.entry.nextEpisode != null || item.entry.nextEpisodeAt"
                class="mt-3 flex flex-wrap items-baseline gap-x-2 gap-y-1 text-sm"
              >
                <span v-if="item.entry.nextEpisode != null"
                      class="font-semibold text-[var(--color-neon-pink)]">
                  ep {{ item.entry.nextEpisode }}
                </span>
                <span v-if="item.entry.nextEpisodeAt" class="text-white">
                  {{ formatRelativeDay(item.entry.nextEpisodeAt) }} · {{ formatTime(item.entry.nextEpisodeAt) }}
                </span>
                <span v-if="formatCountdown(item.entry.nextEpisodeAt)"
                      class="text-xs text-[var(--color-text-muted)]">
                  ({{ formatCountdown(item.entry.nextEpisodeAt) }})
                </span>
              </div>

              <div class="mt-2 text-xs text-[var(--color-text-muted)] flex flex-wrap gap-x-3 gap-y-1">
                <span v-if="progressLabel(item)">{{ progressLabel(item) }}</span>
                <span v-if="item.entry.duration != null && item.entry.duration > 0">
                  ~{{ item.entry.duration }} min
                </span>
                <span v-if="item.entry.airedOn">
                  aired&nbsp;{{ formatShortDate(item.entry.airedOn) }}
                </span>
                <span v-if="item.entry.releasedOn">
                  released&nbsp;{{ formatShortDate(item.entry.releasedOn) }}
                </span>
              </div>

              <div class="mt-2 text-xs flex flex-wrap gap-x-3">
                <a
                  :href="`https://shikimori.one/animes/${item.entry.anime.id}`"
                  target="_blank"
                  rel="noopener"
                  class="text-[var(--color-neon-blue)] hover:underline"
                >
                  shiki
                </a>
                <router-link
                  v-if="item.orinunoContentId"
                  :to="`/content/${item.orinunoContentId}`"
                  class="text-[var(--color-neon-pink)] hover:underline"
                >
                  open in library
                </router-link>
              </div>
            </div>
          </article>
        </div>
      </div>
      <p v-if="lastLoaded" class="text-xs text-[var(--color-text-muted)] text-right">
        last reload: {{ lastLoaded }} · {{ data.total }} entries
      </p>
    </div>
  </div>
</template>
