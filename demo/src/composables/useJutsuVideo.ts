import { ref } from 'vue'
import { api } from '../api/client'
import type { ProviderDecodeResult } from '../api/types'

/**
 * Shared jut.su decode + playback + download composable.
 *
 * Why a composable: jut.su CDN links from `r{N}.yandexwebcache.org` are signed against the session
 * that fetched them, so the browser must hit them through the backend's `/api/v1/sources/jutsu/stream`
 * pass-through (PROXY-1, see `docs/quirks-and-hacks.md` → "JutSu DLE auth + sticky cookies + 1 RPS").
 * Both the Sources sandbox tab and the JutSu page Episode-meta tab need the exact same flow:
 *
 *   POST /api/v1/sources/jutsu/decode  ─►  qualities map  ─►  ▶ Play / ⬇ Download via stream proxy
 *
 * Keeping that flow in one place stops behaviour from drifting between the two views — particularly
 * around the proxy URL shape, the CDN host whitelist, and the chunk-by-chunk download with progress
 * reporting.
 */

type DownloadStatus = 'downloading' | 'done' | 'error' | 'cancelled'

export interface DownloadEntry {
  status: DownloadStatus
  received: number
  total: number
  startedAt: number
  errorMessage?: string
  aborter: AbortController
}

const API_BASE = (import.meta.env.VITE_API_URL ?? '').replace(/\/$/, '')

/**
 * Build a backend pass-through URL for jut.su CDN links. Required because Yandex CDN signs URLs
 * against the session that fetched them — the browser is a different session and gets 403 if it
 * opens the raw URL. Canonical path is `/api/v1/sources/jutsu/stream`; the legacy
 * `/api/v1/providers/jutsu/stream` alias still works but is deprecated.
 */
export function streamProxyUrl(upstream: string): string {
  return `${API_BASE}/api/v1/sources/jutsu/stream?url=${encodeURIComponent(upstream)}`
}

export function isJutsuCdnUrl(url: string): boolean {
  try {
    const u = new URL(url)
    return u.hostname.toLowerCase().endsWith('.yandexwebcache.org')
  } catch {
    return false
  }
}

/**
 * Build a sensible filename for the saved file. The CDN URL looks like
 * https://r270106.yandexwebcache.org/one-punch-man/1/11.1080.282c57e06cdfaff2.mp4
 * — we want `one-punch-man-s01e11-1080p.mp4`. Best-effort only: if the URL doesn't fit the
 * expected layout we fall back to whatever is sensible. Filename ends up in the
 * `Content-Disposition` header so backend can sanitise it server-side too.
 */
export function buildFilename(rawUrl: string): string {
  try {
    const u = new URL(rawUrl)
    const segments = u.pathname.split('/').filter(Boolean)
    if (segments.length === 0) return 'jutsu-download.mp4'
    const last = segments[segments.length - 1]
    const match = last.match(/^(\d+)\.(\d{3,4})\..*\.mp4$/i)
    const slug = segments[0] || 'episode'
    const season = segments.length >= 3 && /^\d+$/.test(segments[1]) ? segments[1] : '1'
    if (match) {
      const ep = match[1]
      const quality = match[2]
      const sNum = String(season).padStart(2, '0')
      const eNum = String(ep).padStart(2, '0')
      return `${slug}-s${sNum}e${eNum}-${quality}p.mp4`
    }
    return `${slug}-${last}`
  } catch {
    return 'jutsu-download.mp4'
  }
}

export function fmtBytes(n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`
  return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`
}

export function downloadProgressPct(entry: DownloadEntry): number {
  if (!entry.total) return 0
  return Math.round((entry.received / entry.total) * 100)
}

export function downloadEta(entry: DownloadEntry): string {
  if (entry.status !== 'downloading' || !entry.total || !entry.received) return ''
  const elapsedSec = (Date.now() - entry.startedAt) / 1000
  if (elapsedSec < 0.5) return ''
  const speed = entry.received / elapsedSec
  if (speed <= 0) return ''
  const remaining = (entry.total - entry.received) / speed
  if (!isFinite(remaining)) return ''
  return `${fmtBytes(speed)}/s · ETA ${Math.ceil(remaining)}s`
}

export interface UseJutsuVideoOptions {
  /**
   * If a download larger than this threshold (bytes) starts, the user is asked to confirm before
   * the browser commits the whole file to RAM (the chunk-collection path uses Blob, no streaming
   * filesystem APIs). 0 disables the prompt. Default: 1 GB.
   */
  largeDownloadConfirmBytes?: number
}

export function useJutsuVideo(options: UseJutsuVideoOptions = {}) {
  const largeBytes = options.largeDownloadConfirmBytes ?? 1024 * 1024 * 1024

  // Decode state ---------------------------------------------------------------------------------
  const decodeLoading = ref(false)
  const decodeError = ref<string>('')
  const decodeResult = ref<ProviderDecodeResult | null>(null)

  async function decode(url: string) {
    const trimmed = url.trim()
    if (!trimmed) {
      decodeError.value = 'URL is required'
      return
    }
    decodeError.value = ''
    decodeResult.value = null
    decodeLoading.value = true
    try {
      decodeResult.value = await api.decodeProviderUrl({ provider: 'JUTSU', url: trimmed })
    } catch (e: unknown) {
      decodeError.value = e instanceof Error ? e.message : 'Decode failed'
    } finally {
      decodeLoading.value = false
    }
  }

  function clearDecode() {
    decodeError.value = ''
    decodeResult.value = null
  }

  // Inline player --------------------------------------------------------------------------------
  const playerUrl = ref<string | null>(null)

  function playInPlayer(rawUrl: string, scrollTargetId?: string) {
    playerUrl.value = streamProxyUrl(rawUrl)
    if (scrollTargetId) {
      // Scroll target into view so the user actually sees the player appear.
      setTimeout(() => {
        document
          .getElementById(scrollTargetId)
          ?.scrollIntoView({ behavior: 'smooth', block: 'center' })
      }, 50)
    }
  }

  function closePlayer() {
    playerUrl.value = null
  }

  // Downloads ------------------------------------------------------------------------------------
  const downloads = ref<Record<string, DownloadEntry>>({})

  /**
   * Download a jut.su CDN URL through the backend proxy with progress reporting. Uses
   * `ReadableStream` from the fetch body so we can update progress chunk-by-chunk and surface a
   * cancel button. Whole file is accumulated as a {@link Blob} in memory because the File System
   * Access API is Chrome-only and StreamSaver.js requires a service worker — for typical episodes
   * (<1 GB) the Blob path is fine. Episodes larger than the configured threshold get a confirm()
   * prompt.
   */
  async function startDownload(url: string) {
    if (downloads.value[url]?.status === 'downloading') return
    const aborter = new AbortController()
    downloads.value = {
      ...downloads.value,
      [url]: {
        status: 'downloading',
        received: 0,
        total: 0,
        startedAt: Date.now(),
        aborter,
      },
    }
    const filename = buildFilename(url)
    const proxyUrl = streamProxyUrl(url) + `&filename=${encodeURIComponent(filename)}`
    try {
      const resp = await fetch(proxyUrl, { signal: aborter.signal })
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`)
      const total = Number(resp.headers.get('Content-Length') ?? 0)
      if (largeBytes > 0 && total > largeBytes) {
        const ok = window.confirm(
          `This file is ~${fmtBytes(total)}. The browser will hold it in RAM until the download finishes. Continue?`,
        )
        if (!ok) {
          aborter.abort()
          downloads.value = {
            ...downloads.value,
            [url]: { ...downloads.value[url], status: 'cancelled' },
          }
          return
        }
      }
      if (!resp.body) throw new Error('Response has no body (HTTP/1.0?)')
      const reader = resp.body.getReader()
      const chunks: Uint8Array[] = []
      let received = 0
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        if (value) {
          chunks.push(value)
          received += value.length
          downloads.value = {
            ...downloads.value,
            [url]: { ...downloads.value[url], received, total },
          }
        }
      }
      const blob = new Blob(chunks as BlobPart[], {
        type: resp.headers.get('Content-Type') ?? 'video/mp4',
      })
      const blobUrl = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = blobUrl
      a.download = filename
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      setTimeout(() => URL.revokeObjectURL(blobUrl), 60_000)
      downloads.value = {
        ...downloads.value,
        [url]: { ...downloads.value[url], status: 'done', received, total },
      }
    } catch (err: unknown) {
      const e = err as { name?: string; message?: string }
      if (e?.name === 'AbortError') {
        downloads.value = {
          ...downloads.value,
          [url]: { ...downloads.value[url], status: 'cancelled' },
        }
        return
      }
      downloads.value = {
        ...downloads.value,
        [url]: {
          ...downloads.value[url],
          status: 'error',
          errorMessage: e?.message ?? 'Download failed',
        },
      }
    }
  }

  function cancelDownload(url: string) {
    const entry = downloads.value[url]
    if (!entry || entry.status !== 'downloading') return
    entry.aborter.abort()
  }

  function clearDownload(url: string) {
    const next = { ...downloads.value }
    delete next[url]
    downloads.value = next
  }

  return {
    // Decode
    decode,
    decodeLoading,
    decodeError,
    decodeResult,
    clearDecode,

    // Player
    playerUrl,
    playInPlayer,
    closePlayer,

    // Downloads
    downloads,
    startDownload,
    cancelDownload,
    clearDownload,

    // URL helpers (also re-exported as named exports above for non-composable contexts)
    streamProxyUrl,
    isJutsuCdnUrl,
  }
}
