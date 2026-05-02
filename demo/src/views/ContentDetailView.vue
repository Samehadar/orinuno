<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api/client'
import type { ContentDto, DownloadState, EpisodeVariantDto } from '../api/types'
import ScreenshotImage from '../components/ScreenshotImage.vue'

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
const downloadStartedAt = reactive<Map<number, number>>(new Map())

interface Toast {
  id: number
  kind: 'success' | 'error' | 'info'
  title: string
  body?: string
}
const toasts = ref<Toast[]>([])
let toastSeq = 0

function pushToast(kind: Toast['kind'], title: string, body?: string, ttlMs = 5000) {
  const t: Toast = { id: ++toastSeq, kind, title, body }
  toasts.value.push(t)
  if (ttlMs > 0) {
    setTimeout(() => {
      toasts.value = toasts.value.filter(x => x.id !== t.id)
    }, ttlMs)
  }
}

function dismissToast(id: number) {
  toasts.value = toasts.value.filter(x => x.id !== id)
}

function shorten(url: string | null | undefined, head = 28, tail = 16): string {
  if (!url) return ''
  if (url.length <= head + tail + 1) return url
  return `${url.slice(0, head)}…${url.slice(-tail)}`
}

async function copyToClipboard(text: string, label = 'Copied') {
  try {
    await navigator.clipboard.writeText(text)
    pushToast('success', label, shorten(text), 2500)
  } catch {
    pushToast('error', 'Copy failed', 'Clipboard not available', 3000)
  }
}

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
        downloadStartedAt.delete(variantId)
        variants.value = await api.getVariants(id)
        if (state.status === 'COMPLETED') {
          pushToast('success', `Variant ${variantId} downloaded`, state.filepath ? shorten(state.filepath) : undefined, 5000)
        } else {
          pushToast('error', `Variant ${variantId} download failed`, state.error ?? 'unknown', 7000)
        }
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
      downloadStartedAt.delete(variantId)
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

function snapshotMp4(): Map<number, string | null> {
  const m = new Map<number, string | null>()
  for (const v of variants.value) m.set(v.id, v.mp4Link)
  return m
}

function describeDecodeDiff(before: Map<number, string | null>): { gained: number; refreshed: number; lost: number; firstNewLink: string | null } {
  let gained = 0
  let refreshed = 0
  let lost = 0
  let firstNewLink: string | null = null
  for (const v of variants.value) {
    const prev = before.get(v.id) ?? null
    const next = v.mp4Link ?? null
    if (!prev && next) {
      gained++
      if (!firstNewLink) firstNewLink = next
    } else if (prev && next && prev !== next) {
      refreshed++
    } else if (prev && !next) {
      lost++
    }
  }
  return { gained, refreshed, lost, firstNewLink }
}

async function decodeAll(force = false) {
  decoding.value = true
  const before = snapshotMp4()
  try {
    await api.decodeContent(id, force)
    await new Promise(r => setTimeout(r, 2000))
    variants.value = await api.getVariants(id)
    const diff = describeDecodeDiff(before)
    if (diff.gained === 0 && diff.refreshed === 0) {
      pushToast('info', force ? 'Force re-decode finished' : 'Decode finished', 'No new mp4 links — try Force re-decode or check decoder health', 6000)
    } else {
      pushToast(
        'success',
        force ? `Force re-decoded ${diff.refreshed + diff.gained} variants` : `Decoded ${diff.gained} variants`,
        diff.firstNewLink ? `Sample: ${shorten(diff.firstNewLink)}` : undefined,
        6000,
      )
    }
  } catch (e: any) {
    error.value = e.message
    pushToast('error', 'Decode failed', e.message, 6000)
  } finally {
    decoding.value = false
  }
}

async function decodeSingleVariant(variant: EpisodeVariantDto, force = false) {
  decodingVariantId.value = variant.id
  const beforeLink = variant.mp4Link ?? null
  try {
    const result = await api.decodeVariant(variant.id, force)
    variants.value = await api.getVariants(id)
    const after = variants.value.find(v => v.id === variant.id)
    if (!result.decoded) {
      pushToast('info', `Variant ${variant.id}: already decoded`, 'Use Force to re-decode', 4000)
    } else if (after?.mp4Link && beforeLink !== after.mp4Link) {
      pushToast('success', `Variant ${variant.id} decoded`, shorten(after.mp4Link), 6000)
    } else if (after?.mp4Link) {
      pushToast('info', `Variant ${variant.id}: link unchanged`, shorten(after.mp4Link), 4500)
    } else {
      pushToast('info', `Variant ${variant.id}: no link returned`, 'Likely geo-blocked or decoder unavailable', 6000)
    }
  } catch (e: any) {
    error.value = e.message
    pushToast('error', `Decode failed for variant ${variant.id}`, e.message, 6000)
  } finally {
    decodingVariantId.value = null
  }
}

async function downloadSingleVariant(variant: EpisodeVariantDto, thenPlay = false) {
  if (thenPlay) autoPlayAfterDownload.value = variant.id
  downloadStartedAt.set(variant.id, Date.now())
  try {
    const state = await api.downloadVariant(variant.id)
    downloadStates.set(variant.id, state)
    if (state.status === 'COMPLETED') {
      downloadStartedAt.delete(variant.id)
      variants.value = await api.getVariants(id)
      pushToast('success', `Variant ${variant.id} already downloaded`, undefined, 3500)
      if (thenPlay) {
        autoPlayAfterDownload.value = null
        const v = variants.value.find(vv => vv.id === variant.id)
        if (v) selectedVariant.value = v
      }
    } else if (state.status === 'IN_PROGRESS') {
      pushToast('info', `Download started for variant ${variant.id}`, thenPlay ? 'Will play automatically when ready' : 'Tracking progress in the row below', 5000)
      startPolling(variant.id)
    } else if (state.status === 'FAILED') {
      downloadStartedAt.delete(variant.id)
      pushToast('error', `Download failed for variant ${variant.id}`, state.error ?? 'unknown', 7000)
      autoPlayAfterDownload.value = null
    }
  } catch (e: any) {
    error.value = e.message
    downloadStartedAt.delete(variant.id)
    autoPlayAfterDownload.value = null
    pushToast('error', 'Download request failed', e.message, 6000)
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
    pushToast('success', `HLS link copied (variant ${variant.id})`, shorten(result.url), 4000)
  } catch (e: any) {
    error.value = `HLS error: ${e.message}`
    pushToast('error', 'HLS link failed', e.message, 6000)
  } finally {
    hlsLoading.value = null
  }
}

function isDownloading(variantId: number): boolean {
  const st = downloadStates.get(variantId)
  return st?.status === 'IN_PROGRESS' || downloadStartedAt.has(variantId)
}

interface ProgressView {
  kind: 'segments' | 'bytes' | 'indeterminate'
  percent: number
  caption: string
  subCaption?: string
  elapsedLabel: string
  phaseHint?: string
}

const nowTick = ref(Date.now())
let nowTimer: ReturnType<typeof setInterval> | null = null

function formatElapsed(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000))
  if (total < 60) return `${total}s`
  const m = Math.floor(total / 60)
  const s = total % 60
  return `${m}m ${s.toString().padStart(2, '0')}s`
}

function getProgressView(variantId: number): ProgressView | null {
  const st = downloadStates.get(variantId)
  const started = downloadStartedAt.get(variantId)
  const isActive = (st?.status === 'IN_PROGRESS') || (!!started)
  if (!isActive) return null

  const elapsedMs = started ? (nowTick.value - started) : 0
  const elapsedLabel = started ? formatElapsed(elapsedMs) : '0s'

  if (st?.totalSegments && st.totalSegments > 0) {
    const done = st.downloadedSegments ?? 0
    const pct = Math.round((done / st.totalSegments) * 100)
    return {
      kind: 'segments',
      percent: pct,
      caption: `${pct}%`,
      subCaption: `${done}/${st.totalSegments} segments${st.totalBytes ? ` · ${formatBytes(st.totalBytes)}` : ''}`,
      elapsedLabel,
      phaseHint: 'HLS segments (Playwright)',
    }
  }

  if (st?.totalBytes && st?.expectedTotalBytes && st.expectedTotalBytes > 0) {
    const pct = Math.min(100, Math.round((st.totalBytes / st.expectedTotalBytes) * 100))
    return {
      kind: 'bytes',
      percent: pct,
      caption: `${pct}%`,
      subCaption: `${formatBytes(st.totalBytes)} / ${formatBytes(st.expectedTotalBytes)}`,
      elapsedLabel,
      phaseHint: 'Direct MP4 (CDN)',
    }
  }

  if (st?.totalBytes && st.totalBytes > 0) {
    return {
      kind: 'bytes',
      percent: 0,
      caption: formatBytes(st.totalBytes),
      subCaption: 'size unknown',
      elapsedLabel,
      phaseHint: 'Direct MP4 (CDN, streaming)',
    }
  }

  const elapsedSec = Math.floor(elapsedMs / 1000)
  const phaseHint = elapsedSec < 30
    ? 'Browser handshake (Playwright, up to 30s)'
    : elapsedSec < 45
      ? 'Playwright timed out — falling back to direct MP4'
      : 'Decoding fresh CDN URL (fallback)'
  return {
    kind: 'indeterminate',
    percent: 0,
    caption: 'Initializing…',
    subCaption: 'waiting for first byte',
    elapsedLabel,
    phaseHint,
  }
}

const activeDownloadIds = computed<number[]>(() => {
  const ids: number[] = []
  for (const [vid, st] of downloadStates.entries()) {
    if (st.status === 'IN_PROGRESS') ids.push(vid)
  }
  for (const vid of downloadStartedAt.keys()) {
    if (!ids.includes(vid)) ids.push(vid)
  }
  return ids
})

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

onMounted(() => {
  load()
  nowTimer = setInterval(() => { nowTick.value = Date.now() }, 1000)
})
onUnmounted(() => {
  stopAllPolling()
  if (nowTimer) clearInterval(nowTimer)
})
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
        <div
          v-for="(src, idx) in content.screenshots.slice(0, 5)"
          :key="idx"
          class="h-32 w-56 rounded-lg overflow-hidden flex-shrink-0 border border-white/5 bg-[#0d1020]"
        >
          <ScreenshotImage
            :src="src"
            :alt="'Screenshot ' + (idx + 1)"
            img-class="w-full h-full object-cover"
            placeholder-class="w-full h-full flex items-center justify-center text-2xl opacity-20"
          />
        </div>
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
          :title="'Background download for every variant. Same backend strategy chain as the per-row Download button.'"
        >
          <span v-if="downloadingAll" class="inline-block animate-spin mr-1">⟳</span>
          {{ downloadingAll ? 'Downloading...' : 'Download all' }}
        </button>

        <a
          href="https://samehadar.github.io/orinuno/architecture/download-pathways/"
          target="_blank"
          rel="noopener noreferrer"
          class="text-xs text-[var(--color-text-muted)] hover:text-[var(--color-neon-blue)] underline decoration-dotted"
          :title="'Decision table: Download vs Download & Play vs HLS vs Embed'"
        >
          What's the difference?
        </a>

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
                      <th class="px-4 py-2 text-left min-w-[280px]">Links</th>
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
                      <td class="px-4 py-2.5 align-top">
                        <div class="flex flex-col gap-1.5 min-w-[260px] max-w-[320px]">
                          <div class="flex items-center gap-1.5">
                            <span class="text-[10px] uppercase tracking-wider text-[var(--color-text-muted)] w-12 flex-shrink-0">kodik</span>
                            <code
                              v-if="v.kodikLink"
                              class="text-[11px] text-[var(--color-text-muted)] truncate flex-1 cursor-pointer hover:text-white"
                              :title="v.kodikLink"
                              @click="copyToClipboard(v.kodikLink!, 'kodik link copied')"
                            >{{ shorten(v.kodikLink) }}</code>
                            <span v-else class="text-[11px] text-[var(--color-text-muted)] italic flex-1">—</span>
                          </div>
                          <div class="flex items-center gap-1.5">
                            <span class="text-[10px] uppercase tracking-wider text-[var(--color-text-muted)] w-12 flex-shrink-0">mp4</span>
                            <code
                              v-if="v.mp4Link"
                              class="text-[11px] text-[var(--color-neon-green)] truncate flex-1 cursor-pointer hover:underline"
                              :title="v.mp4Link"
                              @click="copyToClipboard(v.mp4Link!, 'mp4 link copied')"
                            >{{ shorten(v.mp4Link) }}</code>
                            <span v-else class="text-[11px] text-[var(--color-text-muted)] italic flex-1">
                              not decoded — press Decode
                            </span>
                          </div>
                        </div>
                      </td>
                      <td class="px-4 py-2.5">
                        <template v-if="isDownloading(v.id)">
                          <div class="flex flex-col gap-1 min-w-[160px]">
                            <div class="flex items-center gap-1.5">
                              <span class="inline-block animate-spin text-[var(--color-neon-blue)] text-xs">⟳</span>
                              <span class="text-xs text-[var(--color-neon-blue)] font-medium">
                                {{ getProgressView(v.id)?.caption }}
                              </span>
                              <span class="ml-auto text-[10px] text-[var(--color-text-muted)] tabular-nums">
                                {{ getProgressView(v.id)?.elapsedLabel }}
                              </span>
                            </div>
                            <div class="w-full h-1.5 rounded-full bg-white/5 overflow-hidden">
                              <div
                                v-if="getProgressView(v.id)?.kind !== 'indeterminate'"
                                class="h-full rounded-full bg-[var(--color-neon-blue)] transition-all duration-500"
                                :style="{ width: `${getProgressView(v.id)?.percent ?? 0}%` }"
                              />
                              <div
                                v-else
                                class="h-full rounded-full bg-[var(--color-neon-blue)]/40 animate-pulse"
                                style="width: 100%"
                              />
                            </div>
                            <span v-if="getProgressView(v.id)?.subCaption" class="text-[10px] text-[var(--color-text-muted)]">
                              {{ getProgressView(v.id)?.subCaption }}
                            </span>
                            <span v-if="getProgressView(v.id)?.phaseHint" class="text-[10px] text-[var(--color-text-muted)]/80 italic">
                              {{ getProgressView(v.id)?.phaseHint }}
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
                            :title="'Same backend job as Download (background) — opens the player modal once COMPLETED.'"
                          >
                            <template v-if="autoPlayAfterDownload === v.id">
                              <span class="inline-block animate-spin">⟳</span> Downloading...
                            </template>
                            <template v-else-if="v.localFilepath">
                              ▶ Play
                            </template>
                            <template v-else>
                              ▶ Download &amp; Play
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
                            :title="'Background download (saves locally). Same endpoint as Download &amp; Play — UI does not auto-open the player when complete.'"
                          >
                            Download (background)
                          </button>
                          <button
                            v-if="v.kodikLink"
                            class="text-xs text-[var(--color-neon-orange)] hover:underline disabled:opacity-40"
                            :disabled="hlsLoading === v.id"
                            @click="copyHlsUrl(v)"
                            :title="'Copy m3u8 URL for an external player (VLC/mpv/ffmpeg). No local download.'"
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

      <!-- Download progress overlay (shows all active downloads) -->
      <Teleport to="body">
        <div
          v-if="activeDownloadIds.length > 0"
          class="fixed bottom-6 right-6 z-40 flex flex-col gap-2 max-w-[340px]"
        >
          <div
            v-for="vid in activeDownloadIds"
            :key="vid"
            class="glass-card p-4 border"
            :class="vid === autoPlayAfterDownload
              ? 'border-[var(--color-neon-blue)]/50 shadow-lg shadow-[var(--color-neon-blue)]/20'
              : 'border-[var(--color-neon-blue)]/30'"
          >
            <div class="flex items-center justify-between gap-2 mb-2">
              <div class="flex items-center gap-2">
                <span class="inline-block animate-spin text-[var(--color-neon-blue)]">⟳</span>
                <span class="text-sm font-medium">
                  {{ getProgressView(vid)?.caption ?? 'Downloading' }}
                </span>
              </div>
              <span class="text-[10px] text-[var(--color-text-muted)] tabular-nums">
                variant {{ vid }} · {{ getProgressView(vid)?.elapsedLabel ?? '0s' }}
              </span>
            </div>
            <div class="w-full h-2 rounded-full bg-white/5 overflow-hidden mb-1.5">
              <div
                v-if="getProgressView(vid)?.kind !== 'indeterminate'"
                class="h-full rounded-full bg-gradient-to-r from-[var(--color-neon-blue)] to-[var(--color-neon-green)] transition-all duration-500"
                :style="{ width: `${getProgressView(vid)?.percent ?? 0}%` }"
              />
              <div
                v-else
                class="h-full rounded-full bg-[var(--color-neon-blue)]/40 animate-pulse"
                style="width: 100%"
              />
            </div>
            <p v-if="getProgressView(vid)?.subCaption" class="text-[10px] text-[var(--color-text-muted)]">
              {{ getProgressView(vid)?.subCaption }}
            </p>
            <p v-if="getProgressView(vid)?.phaseHint" class="text-[10px] text-[var(--color-text-muted)]/80 italic mt-0.5">
              {{ getProgressView(vid)?.phaseHint }}
            </p>
            <p v-if="vid === autoPlayAfterDownload" class="text-[10px] text-[var(--color-neon-blue)] mt-1.5">
              ▶ Will play automatically when ready
            </p>
          </div>
        </div>
      </Teleport>

      <!-- Toast notifications -->
      <Teleport to="body">
        <div class="fixed top-6 right-6 z-50 flex flex-col gap-2 w-[320px] max-w-[calc(100vw-3rem)]">
          <div
            v-for="t in toasts"
            :key="t.id"
            class="glass-card p-3 border flex items-start gap-2 cursor-pointer transition-all duration-200 hover:translate-x-[-2px]"
            :class="{
              'border-[var(--color-neon-green)]/40': t.kind === 'success',
              'border-[var(--color-neon-red)]/40': t.kind === 'error',
              'border-[var(--color-neon-blue)]/40': t.kind === 'info',
            }"
            @click="dismissToast(t.id)"
          >
            <span
              class="text-sm flex-shrink-0"
              :class="{
                'text-[var(--color-neon-green)]': t.kind === 'success',
                'text-[var(--color-neon-red)]': t.kind === 'error',
                'text-[var(--color-neon-blue)]': t.kind === 'info',
              }"
            >
              {{ t.kind === 'success' ? '✓' : t.kind === 'error' ? '✗' : 'ℹ' }}
            </span>
            <div class="flex-1 min-w-0">
              <p class="text-xs font-medium leading-tight">{{ t.title }}</p>
              <p v-if="t.body" class="text-[10px] text-[var(--color-text-muted)] mt-0.5 break-all">{{ t.body }}</p>
            </div>
            <button class="text-[var(--color-text-muted)] hover:text-white text-xs">×</button>
          </div>
        </div>
      </Teleport>
    </template>
  </div>
</template>
