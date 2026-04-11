<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api/client'
import type { ContentDto, DownloadState, EpisodeVariantDto } from '../api/types'

const route = useRoute()
const router = useRouter()
const id = Number(route.params.id)
const content = ref<ContentDto | null>(null)
const variants = ref<EpisodeVariantDto[]>([])
const loading = ref(true)
const decoding = ref(false)
const decodingVariantId = ref<number | null>(null)
const downloadingAll = ref(false)
const error = ref('')
const selectedVariant = ref<EpisodeVariantDto | null>(null)
const autoPlayAfterDownload = ref<number | null>(null)
const videoError = ref('')
const videoRef = ref<HTMLVideoElement | null>(null)
const groupBy = ref<'episode' | 'studio'>('episode')
const expandedGroups = ref<Set<string>>(new Set())

const hlsLoading = ref<number | null>(null)
const hlsUrl = ref<string | null>(null)

const downloadStates = reactive<Map<number, DownloadState>>(new Map())
const pollingTimers = new Map<number, ReturnType<typeof setInterval>>()

function stopPolling(variantId: number) {
  const timer = pollingTimers.get(variantId)
  if (timer) {
    clearInterval(timer)
    pollingTimers.delete(variantId)
  }
}

function stopAllPolling() {
  for (const [vid] of pollingTimers) stopPolling(vid)
}

function startPolling(variantId: number) {
  if (pollingTimers.has(variantId)) return
  const timer = setInterval(async () => {
    try {
      const state = await api.getDownloadStatus(variantId)
      downloadStates.set(variantId, state)

      if (state.status === 'COMPLETED' || state.status === 'FAILED') {
        stopPolling(variantId)
        variants.value = await api.getVariants(id)
        if (autoPlayAfterDownload.value === variantId) {
          if (state.status === 'COMPLETED') {
            const v = variants.value.find(vv => vv.id === variantId)
            if (v) selectedVariant.value = v
          }
          autoPlayAfterDownload.value = null
        }
      }
    } catch {
      stopPolling(variantId)
    }
  }, 2000)
  pollingTimers.set(variantId, timer)
}

function formatBytes(bytes: number | null | undefined): string {
  if (!bytes) return '0 B'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [c, v] = await Promise.all([api.getContent(id), api.getVariants(id)])
    content.value = c
    variants.value = v
  } catch (e: any) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function decodeAll(force = false) {
  decoding.value = true
  try {
    await api.decodeContent(id, force)
    await new Promise(r => setTimeout(r, 2000))
    variants.value = await api.getVariants(id)
  } catch (e: any) {
    error.value = e.message
  } finally {
    decoding.value = false
  }
}

async function decodeSingleVariant(variant: EpisodeVariantDto) {
  decodingVariantId.value = variant.id
  try {
    await api.decodeContent(id, false)
    await new Promise(r => setTimeout(r, 3000))
    variants.value = await api.getVariants(id)
  } catch (e: any) {
    error.value = e.message
  } finally {
    decodingVariantId.value = null
  }
}

async function downloadSingleVariant(variant: EpisodeVariantDto, thenPlay = false) {
  if (thenPlay) autoPlayAfterDownload.value = variant.id
  try {
    const state = await api.downloadVariant(variant.id)
    downloadStates.set(variant.id, state)
    if (state.status === 'COMPLETED') {
      variants.value = await api.getVariants(id)
      if (thenPlay) {
        autoPlayAfterDownload.value = null
        const v = variants.value.find(vv => vv.id === variant.id)
        if (v) selectedVariant.value = v
      }
    } else if (state.status === 'IN_PROGRESS') {
      startPolling(variant.id)
    }
  } catch (e: any) {
    error.value = e.message
    autoPlayAfterDownload.value = null
  }
}

async function downloadAll() {
  downloadingAll.value = true
  try {
    await api.downloadContent(id)
    variants.value = await api.getVariants(id)
  } catch (e: any) {
    error.value = e.message
  } finally {
    downloadingAll.value = false
  }
}

function onVideoError(e: Event) {
  const el = e.target as HTMLVideoElement
  const err = el.error
  const msg = err ? `code=${err.code}, ${err.message || 'no message'}` : 'unknown'
  videoError.value = `Video playback error: ${msg}`
  console.error('Video error:', err, 'src:', el.src, 'networkState:', el.networkState, 'readyState:', el.readyState)
}

async function handlePlay(variant: EpisodeVariantDto) {
  videoError.value = ''
  if (variant.localFilepath) {
    selectedVariant.value = variant
    await nextTick()
    try {
      await videoRef.value?.play()
    } catch {
      /* autoplay may be blocked; user can press play */
    }
  } else {
    downloadSingleVariant(variant, true)
  }
}

async function copyHlsUrl(variant: EpisodeVariantDto) {
  hlsLoading.value = variant.id
  try {
    const result = await api.getHlsUrl(variant.id)
    hlsUrl.value = result.url
    await navigator.clipboard.writeText(result.url)
  } catch (e: any) {
    error.value = `HLS error: ${e.message}`
  } finally {
    hlsLoading.value = null
  }
}

function isDownloading(variantId: number): boolean {
  const st = downloadStates.get(variantId)
  return st?.status === 'IN_PROGRESS'
}

function getProgress(variantId: number): { downloaded: number; total: number; bytes: number } | null {
  const st = downloadStates.get(variantId)
  if (!st || st.status !== 'IN_PROGRESS') return null
  if (st.totalSegments == null || st.totalSegments === 0) return null
  return {
    downloaded: st.downloadedSegments ?? 0,
    total: st.totalSegments,
    bytes: Number(st.totalBytes ?? 0),
  }
}

function getProgressPercent(variantId: number): number {
  const p = getProgress(variantId)
  if (!p || p.total === 0) return 0
  return Math.round((p.downloaded / p.total) * 100)
}

function toggleGroup(key: string) {
  if (expandedGroups.value.has(key)) expandedGroups.value.delete(key)
  else expandedGroups.value.add(key)
}

function formatSourceInt(v: number | null | undefined): string {
  if (v === null || v === undefined) return 'null'
  return String(v)
}

function compareSourceSeason(
  a: number | null | undefined,
  b: number | null | undefined,
): number {
  const an = a === undefined || a === null ? null : a
  const bn = b === undefined || b === null ? null : b
  if (an === null && bn === null) return 0
  if (an === null) return 1
  if (bn === null) return -1
  return an - bn
}

interface GroupEntry {
  key: string
  label: string
  decoded: number
  total: number
  items: EpisodeVariantDto[]
}

interface Section {
  title: string
  groups: GroupEntry[]
}

const displaySections = computed<Section[]>(() => {
  if (groupBy.value === 'episode') {
    const seasonMap = new Map<number | null, Map<string, GroupEntry>>()
    for (const v of variants.value) {
      const s: number | null = v.seasonNumber === undefined || v.seasonNumber === null ? null : v.seasonNumber
      if (!seasonMap.has(s)) seasonMap.set(s, new Map())
      const epNum = v.episodeNumber === undefined || v.episodeNumber === null ? null : v.episodeNumber
      const epKey = `s${s === null ? 'null' : s}e${epNum === null ? 'null' : epNum}`
      const groups = seasonMap.get(s)!
      if (!groups.has(epKey)) {
        groups.set(epKey, {
          key: epKey,
          label: `Episode ${formatSourceInt(v.episodeNumber)}`,
          decoded: 0,
          total: 0,
          items: [],
        })
      }
      const g = groups.get(epKey)!
      g.items.push(v)
      g.total++
      if (v.mp4Link) g.decoded++
    }
    return Array.from(seasonMap.entries())
      .sort((a, b) => compareSourceSeason(a[0], b[0]))
      .map(([sKey, groups]) => ({
        title: `Season ${formatSourceInt(sKey)}`,
        groups: Array.from(groups.values()).sort((a, b) => {
          const aEp = a.items[0]?.episodeNumber
          const bEp = b.items[0]?.episodeNumber
          return compareSourceSeason(aEp, bEp)
        }),
      }))
  } else {
    const studioMap = new Map<string, GroupEntry>()
    for (const v of variants.value) {
      const studio = v.translationTitle ?? 'Unknown'
      if (!studioMap.has(studio)) {
        studioMap.set(studio, { key: studio, label: `${studio} (${v.translationType})`, decoded: 0, total: 0, items: [] })
      }
      const g = studioMap.get(studio)!
      g.items.push(v)
      g.total++
      if (v.mp4Link) g.decoded++
    }
    for (const g of studioMap.values()) {
      g.items.sort((a, b) => {
        const sd = compareSourceSeason(a.seasonNumber, b.seasonNumber)
        if (sd !== 0) return sd
        return compareSourceSeason(a.episodeNumber, b.episodeNumber)
      })
    }
    const groups = Array.from(studioMap.values()).sort((a, b) => a.label.localeCompare(b.label))
    return [{ title: '', groups }]
  }
})

const decodedCount = computed(() => variants.value.filter(v => v.mp4Link).length)
const downloadedCount = computed(() => variants.value.filter(v => v.localFilepath).length)
const totalCount = computed(() => variants.value.length)

function streamUrl(variantId: number): string {
  const base = import.meta.env.VITE_API_URL ?? ''
  return `${base}/api/v1/stream/${variantId}`
}

const uniqueStudios = computed(() => {
  const set = new Set<string>()
  for (const v of variants.value) if (v.translationTitle) set.add(v.translationTitle)
  return set.size
})

function identifiers(c: ContentDto) {
  const ids: { label: string; value: string }[] = []
  if (c.kinopoiskId) ids.push({ label: 'KP', value: c.kinopoiskId })
  if (c.imdbId) ids.push({ label: 'IMDB', value: c.imdbId })
  if (c.shikimoriId) ids.push({ label: 'Shikimori', value: c.shikimoriId })
  if (c.kodikId) ids.push({ label: 'Kodik', value: c.kodikId })
  return ids
}

function expandAll() {
  for (const section of displaySections.value) {
    for (const g of section.groups) expandedGroups.value.add(g.key)
  }
}

function collapseAll() {
  expandedGroups.value.clear()
}

onMounted(load)
onUnmounted(stopAllPolling)
</script>

<template>
  <div>
    <!-- Loading -->
    <div v-if="loading" class="space-y-4">
      <div class="skeleton h-8 w-1/2" />
      <div class="glass-card p-6">
        <div class="skeleton h-60 w-full mb-4" />
        <div class="skeleton h-5 w-3/4 mb-2" />
        <div class="skeleton h-4 w-1/2" />
      </div>
    </div>

    <!-- Error -->
    <div v-else-if="error && !content" class="glass-card p-6 text-center">
      <p class="text-[var(--color-neon-red)]">{{ error }}</p>
      <button class="neon-btn mt-4" @click="router.back()">Go back</button>
    </div>

    <template v-else-if="content">
      <!-- Header -->
      <div class="flex items-start justify-between mb-6 gap-4">
        <div>
          <button
            class="text-sm text-[var(--color-text-muted)] hover:text-white mb-2 transition-colors"
            @click="router.back()"
          >
            ← Back
          </button>
          <h1 class="text-2xl sm:text-3xl font-bold">{{ content.title }}</h1>
          <p v-if="content.titleOrig" class="text-[var(--color-text-muted)] mt-1">{{ content.titleOrig }}</p>
        </div>
        <button
          class="neon-btn flex-shrink-0"
          @click="router.push({ name: 'export', params: { id } })"
        >
          Export
        </button>
      </div>

      <!-- Screenshots -->
      <div v-if="content.screenshots?.length" class="flex gap-2 mb-6 overflow-x-auto pb-2">
        <img
          v-for="(src, idx) in content.screenshots.slice(0, 5)"
          :key="idx"
          :src="src.startsWith('//') ? 'https:' + src : src"
          class="h-32 rounded-lg object-cover flex-shrink-0 border border-white/5"
          loading="lazy"
        />
      </div>

      <!-- Ratings row -->
      <div v-if="content.kinopoiskRating || content.imdbRating || content.shikimoriRating" class="flex flex-wrap gap-3 mb-4">
        <div v-if="content.kinopoiskRating" class="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-[#ff6600]/10 border border-[#ff6600]/20">
          <span class="text-xs font-bold text-[#ff6600]">KP</span>
          <span class="text-sm font-semibold text-white">{{ content.kinopoiskRating }}</span>
        </div>
        <div v-if="content.imdbRating" class="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-[#f5c518]/10 border border-[#f5c518]/20">
          <span class="text-xs font-bold text-[#f5c518]">IMDb</span>
          <span class="text-sm font-semibold text-white">{{ content.imdbRating }}</span>
        </div>
        <div v-if="content.shikimoriRating" class="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-[#2e51a2]/10 border border-[#2e51a2]/20">
          <span class="text-xs font-bold text-[#4f8ef7]">Shikimori</span>
          <span class="text-sm font-semibold text-white">{{ content.shikimoriRating }}</span>
        </div>
      </div>

      <!-- Genres -->
      <div v-if="content.genres" class="flex flex-wrap gap-1.5 mb-4">
        <span
          v-for="genre in content.genres.split(',')"
          :key="genre"
          class="px-2.5 py-1 rounded-full text-xs bg-[var(--color-neon-pink)]/10 text-[var(--color-neon-pink)] border border-[var(--color-neon-pink)]/20"
        >
          {{ genre.trim() }}
        </span>
      </div>

      <!-- Geo-block warning -->
      <div v-if="content.blockedCountries" class="glass-card p-3 mb-4 border border-[var(--color-neon-orange)]/30 bg-[var(--color-neon-orange)]/5">
        <div class="flex items-center gap-2">
          <span class="text-[var(--color-neon-orange)]">⚠</span>
          <span class="text-xs text-[var(--color-neon-orange)]">
            Geo-blocked in: {{ content.blockedCountries }}
          </span>
        </div>
      </div>

      <!-- Info cards -->
      <div class="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-3 mb-6">
        <div class="glass-card p-4 text-center">
          <p class="text-xs text-[var(--color-text-muted)] uppercase tracking-wider">Type</p>
          <p class="font-semibold mt-1 text-sm">{{ content.type }}</p>
        </div>
        <div class="glass-card p-4 text-center">
          <p class="text-xs text-[var(--color-text-muted)] uppercase tracking-wider">Year</p>
          <p class="font-semibold mt-1 text-sm">{{ content.year ?? '—' }}</p>
        </div>
        <div class="glass-card p-4 text-center">
          <p class="text-xs text-[var(--color-text-muted)] uppercase tracking-wider">Quality</p>
          <p class="font-semibold mt-1 text-sm">{{ content.quality ?? '—' }}</p>
        </div>
        <div class="glass-card p-4 text-center">
          <p class="text-xs text-[var(--color-text-muted)] uppercase tracking-wider">Decoded</p>
          <p class="font-semibold mt-1 text-sm">
            <span :class="decodedCount === totalCount ? 'text-[var(--color-neon-green)]' : 'text-[var(--color-neon-orange)]'">
              {{ decodedCount }}
            </span>
            / {{ totalCount }}
          </p>
        </div>
        <div class="glass-card p-4 text-center">
          <p class="text-xs text-[var(--color-text-muted)] uppercase tracking-wider">Downloaded</p>
          <p class="font-semibold mt-1 text-sm">
            <span :class="downloadedCount === totalCount ? 'text-[var(--color-neon-green)]' : 'text-[var(--color-neon-blue)]'">
              {{ downloadedCount }}
            </span>
            / {{ totalCount }}
          </p>
        </div>
        <div class="glass-card p-4 text-center">
          <p class="text-xs text-[var(--color-text-muted)] uppercase tracking-wider">Studios</p>
          <p class="font-semibold mt-1 text-sm text-[var(--color-neon-blue)]">{{ uniqueStudios }}</p>
        </div>
      </div>

      <!-- Identifiers -->
      <div v-if="identifiers(content).length" class="flex flex-wrap gap-2 mb-6">
        <span
          v-for="ident in identifiers(content)"
          :key="ident.label"
          class="badge bg-white/5 text-[var(--color-text-muted)]"
        >
          {{ ident.label }}: {{ ident.value }}
        </span>
      </div>

      <!-- Material data (expandable) -->
      <details v-if="content.materialData && Object.keys(content.materialData).length" class="glass-card mb-6">
        <summary class="px-4 py-3 cursor-pointer text-sm font-semibold text-[var(--color-text-muted)] hover:text-white transition-colors select-none">
          Material Data ({{ Object.keys(content.materialData).length }} fields)
        </summary>
        <div class="px-4 pb-4 border-t border-white/5">
          <div class="grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-1.5 mt-3 text-xs">
            <div v-for="(val, key) in content.materialData" :key="String(key)" class="flex gap-2 py-1">
              <span class="text-[var(--color-text-muted)] min-w-[140px] flex-shrink-0">{{ key }}</span>
              <span class="text-white break-all">{{ typeof val === 'object' ? JSON.stringify(val) : val }}</span>
            </div>
          </div>
        </div>
      </details>

      <!-- Action bar -->
      <div class="flex flex-wrap items-center gap-3 mb-6">
        <button class="neon-btn" :disabled="decoding" @click="decodeAll(false)">
          <span v-if="decoding && !decodingVariantId" class="inline-block animate-spin mr-1">⟳</span>
          {{ decoding && !decodingVariantId ? 'Decoding...' : 'Decode all' }}
        </button>
        <button
          class="neon-btn !bg-gradient-to-r !from-[var(--color-neon-orange)] !to-[var(--color-neon-red)]"
          :disabled="decoding"
          @click="decodeAll(true)"
        >
          Force re-decode
        </button>
        <button
          class="neon-btn !bg-gradient-to-r !from-[var(--color-neon-green)] !to-[var(--color-neon-blue)]"
          :disabled="downloadingAll"
          @click="downloadAll"
        >
          <span v-if="downloadingAll" class="inline-block animate-spin mr-1">⟳</span>
          {{ downloadingAll ? 'Downloading...' : 'Download all' }}
        </button>

        <div class="ml-auto flex items-center gap-2">
          <span class="text-xs text-[var(--color-text-muted)]">Group by:</span>
          <button
            class="px-3 py-1.5 rounded-lg text-xs font-medium transition-all"
            :class="groupBy === 'episode'
              ? 'bg-[var(--color-neon-pink)]/20 text-[var(--color-neon-pink)] border border-[var(--color-neon-pink)]/30'
              : 'bg-white/5 text-[var(--color-text-muted)] border border-white/10 hover:border-white/20'"
            @click="groupBy = 'episode'; expandedGroups.clear()"
          >
            Episode
          </button>
          <button
            class="px-3 py-1.5 rounded-lg text-xs font-medium transition-all"
            :class="groupBy === 'studio'
              ? 'bg-[var(--color-neon-blue)]/20 text-[var(--color-neon-blue)] border border-[var(--color-neon-blue)]/30'
              : 'bg-white/5 text-[var(--color-text-muted)] border border-white/10 hover:border-white/20'"
            @click="groupBy = 'studio'; expandedGroups.clear()"
          >
            Studio
          </button>
        </div>
      </div>

      <!-- Expand/collapse controls -->
      <div class="flex gap-2 mb-4 text-xs">
        <button class="text-[var(--color-text-muted)] hover:text-white transition-colors" @click="expandAll">
          Expand all
        </button>
        <span class="text-white/20">|</span>
        <button class="text-[var(--color-text-muted)] hover:text-white transition-colors" @click="collapseAll">
          Collapse all
        </button>
      </div>

      <!-- Sections -->
      <div v-for="section in displaySections" :key="section.title" class="mb-8">
        <h2 v-if="section.title" class="text-lg font-semibold mb-4">
          <span class="text-[var(--color-neon-blue)]">{{ section.title }}</span>
        </h2>

        <div class="space-y-2">
          <div v-for="group in section.groups" :key="group.key" class="glass-card overflow-hidden">
            <!-- Group header -->
            <button
              class="w-full px-4 py-3 flex items-center gap-3 hover:bg-white/[0.02] transition-colors text-left"
              @click="toggleGroup(group.key)"
            >
              <span
                class="text-[var(--color-text-muted)] text-xs transition-transform duration-200"
                :class="expandedGroups.has(group.key) ? 'rotate-90' : ''"
              >▶</span>

              <span class="font-semibold text-sm flex-1">{{ group.label }}</span>

              <span class="text-xs text-[var(--color-text-muted)]">
                <span :class="group.decoded === group.total ? 'text-[var(--color-neon-green)]' : 'text-[var(--color-neon-orange)]'">
                  {{ group.decoded }}
                </span>
                / {{ group.total }}
              </span>

              <div class="w-16 h-1.5 rounded-full bg-white/5 overflow-hidden">
                <div
                  class="h-full rounded-full transition-all duration-300"
                  :class="group.decoded === group.total ? 'bg-[var(--color-neon-green)]' : 'bg-[var(--color-neon-orange)]'"
                  :style="{ width: `${group.total ? (group.decoded / group.total) * 100 : 0}%` }"
                />
              </div>
            </button>

            <!-- Group items -->
            <div v-if="expandedGroups.has(group.key)" class="border-t border-white/5">
              <div class="overflow-x-auto">
                <table class="w-full text-sm">
                  <thead>
                    <tr class="border-b border-white/5 text-[var(--color-text-muted)] text-xs uppercase tracking-wider">
                      <template v-if="groupBy === 'episode'">
                        <th class="px-4 py-2 text-left">Translation</th>
                        <th class="px-4 py-2 text-left">Type</th>
                      </template>
                      <template v-else>
                        <th class="px-4 py-2 text-left">Episode</th>
                      </template>
                      <th class="px-4 py-2 text-left">Quality</th>
                      <th class="px-4 py-2 text-left">MP4</th>
                      <th class="px-4 py-2 text-left">Status</th>
                      <th class="px-4 py-2 text-left">Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr
                      v-for="v in group.items"
                      :key="v.id"
                      class="border-b border-white/5 last:border-0 hover:bg-white/[0.02] transition-colors"
                    >
                      <template v-if="groupBy === 'episode'">
                        <td class="px-4 py-2.5 text-[var(--color-text-muted)]">
                          {{ v.translationTitle }}
                        </td>
                        <td class="px-4 py-2.5">
                          <span class="badge bg-white/5 text-xs"
                            :class="v.translationType === 'voice' ? 'text-[var(--color-neon-blue)]' : 'text-[var(--color-neon-orange)]'">
                            {{ v.translationType }}
                          </span>
                        </td>
                      </template>
                      <template v-else>
                        <td class="px-4 py-2.5 font-medium">
                          S{{ formatSourceInt(v.seasonNumber) }}E{{ formatSourceInt(v.episodeNumber) }}
                        </td>
                      </template>
                      <td class="px-4 py-2.5">
                        <span class="badge bg-white/5 text-[var(--color-neon-blue)] text-xs">
                          {{ v.quality ?? '—' }}
                        </span>
                      </td>
                      <td class="px-4 py-2.5">
                        <span v-if="v.mp4Link" class="text-[var(--color-neon-green)]">✓</span>
                        <span v-else class="text-[var(--color-text-muted)]">—</span>
                      </td>
                      <td class="px-4 py-2.5">
                        <template v-if="isDownloading(v.id)">
                          <div class="flex flex-col gap-1 min-w-[120px]">
                            <div class="flex items-center gap-1.5">
                              <span class="inline-block animate-spin text-[var(--color-neon-blue)] text-xs">⟳</span>
                              <span class="text-xs text-[var(--color-neon-blue)]">
                                {{ getProgressPercent(v.id) }}%
                              </span>
                              <span v-if="getProgress(v.id)" class="text-[10px] text-[var(--color-text-muted)]">
                                {{ getProgress(v.id)!.downloaded }}/{{ getProgress(v.id)!.total }}
                              </span>
                            </div>
                            <div class="w-full h-1.5 rounded-full bg-white/5 overflow-hidden">
                              <div
                                class="h-full rounded-full bg-[var(--color-neon-blue)] transition-all duration-500"
                                :style="{ width: `${getProgressPercent(v.id)}%` }"
                              />
                            </div>
                            <span v-if="getProgress(v.id)?.bytes" class="text-[10px] text-[var(--color-text-muted)]">
                              {{ formatBytes(getProgress(v.id)?.bytes) }}
                            </span>
                          </div>
                        </template>
                        <template v-else-if="downloadStates.get(v.id)?.status === 'FAILED'">
                          <span class="text-[var(--color-neon-red)] text-xs" :title="downloadStates.get(v.id)?.error ?? ''">
                            ✗ Failed
                          </span>
                        </template>
                        <template v-else-if="v.localFilepath">
                          <span class="text-[var(--color-neon-green)]">✓ Saved</span>
                        </template>
                        <template v-else>
                          <span class="text-[var(--color-text-muted)]">—</span>
                        </template>
                      </td>
                      <td class="px-4 py-2.5">
                        <div class="flex items-center gap-2">
                          <button
                            v-if="v.kodikLink"
                            class="text-xs hover:underline disabled:opacity-40"
                            :class="v.localFilepath
                              ? 'text-[var(--color-neon-pink)]'
                              : 'text-[var(--color-neon-pink)]/70'"
                            :disabled="isDownloading(v.id) || autoPlayAfterDownload === v.id"
                            @click="handlePlay(v)"
                          >
                            <template v-if="autoPlayAfterDownload === v.id">
                              <span class="inline-block animate-spin">⟳</span> Downloading...
                            </template>
                            <template v-else-if="v.localFilepath">
                              ▶ Play
                            </template>
                            <template v-else>
                              ▶ Download & Play
                            </template>
                          </button>
                          <button
                            v-if="!v.mp4Link"
                            class="text-xs text-[var(--color-neon-blue)] hover:underline disabled:opacity-40"
                            :disabled="decodingVariantId === v.id || decoding"
                            @click="decodeSingleVariant(v)"
                          >
                            <span v-if="decodingVariantId === v.id" class="inline-block animate-spin">⟳</span>
                            <span v-else>Decode</span>
                          </button>
                          <button
                            v-if="v.kodikLink && !v.localFilepath && !isDownloading(v.id)"
                            class="text-xs text-[var(--color-neon-green)] hover:underline disabled:opacity-40"
                            :disabled="downloadingAll"
                            @click="downloadSingleVariant(v)"
                          >
                            Download
                          </button>
                          <button
                            v-if="v.kodikLink"
                            class="text-xs text-[var(--color-neon-orange)] hover:underline disabled:opacity-40"
                            :disabled="hlsLoading === v.id"
                            @click="copyHlsUrl(v)"
                            :title="'Copy HLS m3u8 URL to clipboard'"
                          >
                            <span v-if="hlsLoading === v.id" class="inline-block animate-spin">⟳</span>
                            <span v-else>HLS</span>
                          </button>
                        </div>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Video player modal -->
      <Teleport to="body">
        <div
          v-if="selectedVariant"
          class="fixed inset-0 z-50 flex items-center justify-center bg-black/80 backdrop-blur-sm"
          @click.self="selectedVariant = null"
        >
          <div class="glass-card p-4 w-full max-w-4xl mx-4">
            <div class="flex items-center justify-between mb-3">
              <h3 class="font-semibold text-sm">
                S{{ formatSourceInt(selectedVariant.seasonNumber) }}E{{ formatSourceInt(selectedVariant.episodeNumber) }}
                — {{ selectedVariant.translationTitle }}
              </h3>
              <button
                class="text-[var(--color-text-muted)] hover:text-white text-xl"
                @click="selectedVariant = null"
              >×</button>
            </div>
            <video
              ref="videoRef"
              :key="selectedVariant.id"
              :src="streamUrl(selectedVariant.id)"
              controls
              autoplay
              playsinline
              preload="auto"
              class="w-full rounded-lg bg-black"
              style="max-height: 70vh"
              @error="onVideoError"
              @loadeddata="videoError = ''"
            />
            <p v-if="videoError" class="text-[var(--color-neon-red)] text-sm mt-2">{{ videoError }}</p>
            <p class="text-[var(--color-text-muted)] text-xs mt-1">
              Stream: {{ streamUrl(selectedVariant.id) }}
            </p>
          </div>
        </div>
      </Teleport>

      <!-- Download progress overlay -->
      <Teleport to="body">
        <div
          v-if="autoPlayAfterDownload != null"
          class="fixed bottom-6 right-6 z-40 glass-card p-4 min-w-[280px] border border-[var(--color-neon-blue)]/30"
        >
          <div class="flex items-center gap-2 mb-2">
            <span class="inline-block animate-spin text-[var(--color-neon-blue)]">⟳</span>
            <span class="text-sm font-medium">Downloading video...</span>
          </div>
          <template v-if="getProgress(autoPlayAfterDownload)">
            <div class="w-full h-2 rounded-full bg-white/5 overflow-hidden mb-1.5">
              <div
                class="h-full rounded-full bg-gradient-to-r from-[var(--color-neon-blue)] to-[var(--color-neon-green)] transition-all duration-500"
                :style="{ width: `${getProgressPercent(autoPlayAfterDownload)}%` }"
              />
            </div>
            <div class="flex justify-between text-[10px] text-[var(--color-text-muted)]">
              <span>{{ getProgress(autoPlayAfterDownload)!.downloaded }} / {{ getProgress(autoPlayAfterDownload)!.total }} segments</span>
              <span>{{ formatBytes(getProgress(autoPlayAfterDownload)!.bytes) }}</span>
            </div>
          </template>
          <p class="text-[10px] text-[var(--color-text-muted)] mt-1.5">
            Video will play automatically when ready
          </p>
        </div>
      </Teleport>
    </template>
  </div>
</template>
