<script setup lang="ts">
import { computed, ref } from 'vue'
import { api } from '../api/client'
import type {
  ContentDto,
  ProviderDecodeResult,
  ProviderName,
  RankedSourceCandidate,
  RankedSourcesResponse,
} from '../api/types'

type LookupBy = 'contentId' | 'kinopoiskId'
type SandboxProvider = 'SIBNET' | 'ANIBOOM' | 'JUTSU'
type Tab = 'episode' | 'sandbox'

const tab = ref<Tab>('episode')

const lookupBy = ref<LookupBy>('kinopoiskId')
const lookupValue = ref('')
const seasonInput = ref<number>(1)
const episodeInput = ref<number>(1)
const preferOrder = ref<string>('KODIK,ANIBOOM,JUTSU,SIBNET')
const usePreferOverride = ref<boolean>(false)

const lookupLoading = ref(false)
const sourcesLoading = ref(false)
const lookupError = ref('')
const sourcesError = ref('')

const resolvedContent = ref<ContentDto | null>(null)
const sources = ref<RankedSourcesResponse | null>(null)

const sandboxProvider = ref<SandboxProvider>('SIBNET')
const sandboxUrl = ref('')
const sandboxLoading = ref(false)
const sandboxError = ref('')
const sandboxResult = ref<ProviderDecodeResult | null>(null)

const sandboxPlaceholders: Record<SandboxProvider, string> = {
  SIBNET: 'https://video.sibnet.ru/shell.php?videoid=…',
  ANIBOOM: 'https://aniboom.one/embed/…',
  JUTSU: 'https://jut.su/<slug>/episode-<n>.html',
}

const providerCount = computed(() => {
  const cands = sources.value?.candidates ?? []
  const set = new Set<string>()
  for (const c of cands) set.add(c.provider)
  return set.size
})

async function lookupContent(): Promise<number | null> {
  if (lookupBy.value === 'contentId') {
    const id = Number(lookupValue.value)
    if (!Number.isFinite(id) || id <= 0) {
      lookupError.value = 'Content ID must be a positive number'
      return null
    }
    return id
  }
  if (!lookupValue.value.trim()) {
    lookupError.value = 'Kinopoisk ID is required'
    return null
  }
  lookupLoading.value = true
  lookupError.value = ''
  try {
    const dto = await api.getByKinopoisk(lookupValue.value.trim())
    resolvedContent.value = dto
    return dto.id
  } catch (e: any) {
    lookupError.value = e?.message ?? 'Lookup failed'
    resolvedContent.value = null
    return null
  } finally {
    lookupLoading.value = false
  }
}

async function loadSources() {
  sources.value = null
  sourcesError.value = ''
  resolvedContent.value = null
  const contentId = await lookupContent()
  if (contentId == null) return
  if (lookupBy.value === 'contentId') {
    try {
      resolvedContent.value = await api.getContent(contentId)
    } catch {
      // non-fatal: ranking can still render without the title
    }
  }
  sourcesLoading.value = true
  try {
    const prefer = usePreferOverride.value ? preferOrder.value.trim() : undefined
    sources.value = await api.getEpisodeSources(
      contentId,
      seasonInput.value,
      episodeInput.value,
      prefer && prefer.length ? prefer : undefined,
    )
  } catch (e: any) {
    sourcesError.value = e?.message ?? 'Sources lookup failed'
  } finally {
    sourcesLoading.value = false
  }
}

async function runSandbox() {
  sandboxResult.value = null
  sandboxError.value = ''
  if (!sandboxUrl.value.trim()) {
    sandboxError.value = 'URL is required'
    return
  }
  sandboxLoading.value = true
  try {
    sandboxResult.value = await api.decodeProviderUrl({
      provider: sandboxProvider.value,
      url: sandboxUrl.value.trim(),
    })
  } catch (e: any) {
    sandboxError.value = e?.message ?? 'Decode failed'
  } finally {
    sandboxLoading.value = false
  }
}

function providerColor(provider: string): string {
  const p = provider.toUpperCase()
  if (p === 'KODIK') return 'bg-[var(--color-neon-pink)]/15 text-[var(--color-neon-pink)] border-[var(--color-neon-pink)]/40'
  if (p === 'ANIBOOM') return 'bg-[var(--color-neon-blue)]/15 text-[var(--color-neon-blue)] border-[var(--color-neon-blue)]/40'
  if (p === 'JUTSU') return 'bg-[var(--color-neon-orange)]/15 text-[var(--color-neon-orange)] border-[var(--color-neon-orange)]/40'
  if (p === 'SIBNET') return 'bg-[var(--color-neon-green)]/15 text-[var(--color-neon-green)] border-[var(--color-neon-green)]/40'
  return 'bg-white/5 text-[var(--color-text-muted)] border-white/10'
}

function methodColor(method: string | null): string {
  if (method === 'REGEX') return 'text-[var(--color-neon-green)]'
  if (method === 'SNIFF') return 'text-[var(--color-neon-orange)]'
  return 'text-[var(--color-text-muted)]'
}

function shorten(s: string | null, head = 36, tail = 18): string {
  if (!s) return '—'
  if (s.length <= head + tail + 1) return s
  return `${s.slice(0, head)}…${s.slice(-tail)}`
}

function formatScore(score: number): string {
  return score.toFixed(3)
}

function copy(value: string | null) {
  if (!value) return
  navigator.clipboard?.writeText(value).catch(() => undefined)
}

function knownProviders(): { value: ProviderName; label: string }[] {
  return [
    { value: 'KODIK', label: 'Kodik' },
    { value: 'ANIBOOM', label: 'Aniboom' },
    { value: 'JUTSU', label: 'JutSu' },
    { value: 'SIBNET', label: 'Sibnet' },
  ]
}

function candidatesByProvider(): Record<string, RankedSourceCandidate[]> {
  const out: Record<string, RankedSourceCandidate[]> = {}
  if (!sources.value) return out
  for (const c of sources.value.candidates) {
    const key = c.provider
    if (!out[key]) out[key] = []
    out[key].push(c)
  }
  return out
}
</script>

<template>
  <div>
    <!-- Hero -->
    <div class="text-center mb-8">
      <h1 class="text-4xl sm:text-5xl font-extrabold mb-3">
        <span class="gradient-text">Multi-Source View</span>
      </h1>
      <p class="text-[var(--color-text-muted)] text-lg">
        AP-7 ranked candidates across all providers, plus a per-provider URL sandbox
      </p>
    </div>

    <!-- Tabs -->
    <div class="flex justify-center mb-6">
      <div class="glass-card flex gap-1 p-1">
        <button
          class="px-4 py-2 rounded-md text-sm font-medium transition-colors"
          :class="tab === 'episode' ? 'bg-[var(--color-neon-pink)]/20 text-[var(--color-neon-pink)]' : 'text-[var(--color-text-muted)] hover:text-white'"
          @click="tab = 'episode'"
        >
          📺 Episode candidates
        </button>
        <button
          class="px-4 py-2 rounded-md text-sm font-medium transition-colors"
          :class="tab === 'sandbox' ? 'bg-[var(--color-neon-pink)]/20 text-[var(--color-neon-pink)]' : 'text-[var(--color-text-muted)] hover:text-white'"
          @click="tab = 'sandbox'"
        >
          🧪 Provider sandbox
        </button>
      </div>
    </div>

    <!-- Episode tab -->
    <section v-if="tab === 'episode'">
      <!-- Note -->
      <div class="glass-card p-4 mb-6 max-w-3xl mx-auto border-[var(--color-neon-blue)]/30">
        <p class="text-xs text-[var(--color-text-muted)]">
          Returns ranked candidates from <code class="text-[var(--color-neon-blue)]">episode_source</code>
          + <code class="text-[var(--color-neon-blue)]">episode_video</code> tables (PLAYER-1, AP-7).
          Today most rows come from Kodik dual-write — Sibnet/Aniboom/JutSu candidates only appear
          for content already ingested through their own discovery paths.
        </p>
      </div>

      <!-- Lookup form -->
      <div class="glass-card p-6 mb-6 max-w-3xl mx-auto">
        <form @submit.prevent="loadSources" class="space-y-4">
          <div class="flex gap-2">
            <select v-model="lookupBy" class="neon-input !w-auto !rounded-r-none text-sm">
              <option value="kinopoiskId">Kinopoisk ID</option>
              <option value="contentId">Content ID</option>
            </select>
            <input
              v-model="lookupValue"
              :placeholder="lookupBy === 'kinopoiskId' ? '535341' : '42'"
              class="neon-input !rounded-l-none"
            />
          </div>

          <div class="grid grid-cols-2 gap-3">
            <label class="text-xs text-[var(--color-text-muted)] flex flex-col gap-1">
              Season
              <input v-model.number="seasonInput" type="number" min="1" class="neon-input" />
            </label>
            <label class="text-xs text-[var(--color-text-muted)] flex flex-col gap-1">
              Episode
              <input v-model.number="episodeInput" type="number" min="1" class="neon-input" />
            </label>
          </div>

          <div class="space-y-2">
            <label class="flex items-center gap-2 text-sm text-[var(--color-text-muted)] cursor-pointer">
              <input v-model="usePreferOverride" type="checkbox" class="w-4 h-4 accent-[var(--color-neon-pink)]" />
              Override provider preference order
            </label>
            <input
              v-if="usePreferOverride"
              v-model="preferOrder"
              placeholder="KODIK,ANIBOOM,JUTSU,SIBNET"
              class="neon-input text-sm font-mono"
            />
            <p v-if="usePreferOverride" class="text-[10px] text-[var(--color-text-muted)]">
              Comma-separated provider order; first = highest weight in AP-7.
            </p>
          </div>

          <button
            type="submit"
            class="neon-btn w-full"
            :disabled="lookupLoading || sourcesLoading || !lookupValue.trim()"
          >
            <span v-if="lookupLoading || sourcesLoading" class="inline-block animate-spin mr-1">⟳</span>
            {{ lookupLoading || sourcesLoading ? 'Loading…' : 'Rank candidates' }}
          </button>
        </form>
      </div>

      <!-- Errors -->
      <div v-if="lookupError" class="glass-card p-4 mb-4 max-w-3xl mx-auto border-[var(--color-neon-red)]/50">
        <p class="text-sm text-[var(--color-neon-red)]">Lookup: {{ lookupError }}</p>
      </div>
      <div v-if="sourcesError" class="glass-card p-4 mb-4 max-w-3xl mx-auto border-[var(--color-neon-red)]/50">
        <p class="text-sm text-[var(--color-neon-red)]">Sources: {{ sourcesError }}</p>
      </div>

      <!-- Resolved content header -->
      <div v-if="resolvedContent" class="glass-card p-4 mb-4 max-w-3xl mx-auto">
        <div class="flex items-center justify-between gap-3 flex-wrap">
          <div>
            <h3 class="font-semibold">{{ resolvedContent.title }}</h3>
            <p class="text-xs text-[var(--color-text-muted)] mt-0.5">
              <span v-if="resolvedContent.titleOrig">{{ resolvedContent.titleOrig }} · </span>
              <span v-if="resolvedContent.year">{{ resolvedContent.year }} · </span>
              content_id={{ resolvedContent.id }}
            </p>
          </div>
          <span class="badge bg-white/5 text-[var(--color-neon-blue)]">
            S{{ seasonInput }}E{{ episodeInput }}
          </span>
        </div>
      </div>

      <!-- Sources result -->
      <div v-if="sources" class="max-w-4xl mx-auto">
        <div class="flex items-center justify-between mb-3 px-1 text-xs text-[var(--color-text-muted)]">
          <span>{{ sources.count }} candidate(s) · {{ providerCount }} provider(s)</span>
          <span v-if="usePreferOverride" class="font-mono">prefer: {{ preferOrder }}</span>
        </div>

        <div v-if="sources.count === 0" class="glass-card p-8 text-center">
          <span class="block text-4xl mb-3">🪞</span>
          <p class="text-[var(--color-text-muted)]">No candidates for this episode yet</p>
          <p class="text-xs text-[var(--color-text-muted)] mt-1">
            The dual-write only fires after a Kodik decode succeeds for this variant.
          </p>
        </div>

        <div v-else class="space-y-3">
          <div
            v-for="(group, provider) in candidatesByProvider()"
            :key="provider"
            class="glass-card p-4"
          >
            <div class="flex items-center justify-between mb-3">
              <span :class="['badge border', providerColor(String(provider))]">
                {{ provider }}
              </span>
              <span class="text-[10px] text-[var(--color-text-muted)]">
                {{ group.length }} bucket(s)
              </span>
            </div>
            <div class="space-y-2">
              <div
                v-for="c in group"
                :key="String(provider) + ':' + (c.quality ?? 'auto') + ':' + (c.translatorId ?? '_')"
                class="flex items-center justify-between gap-3 flex-wrap p-2 rounded bg-white/5"
              >
                <div class="flex items-center gap-3 flex-wrap">
                  <span class="badge bg-white/5 text-[var(--color-neon-pink)] font-mono">
                    {{ c.quality ?? 'auto' }}
                  </span>
                  <span v-if="c.translatorName" class="text-xs text-[var(--color-text-muted)]">
                    {{ c.translatorName }}
                  </span>
                  <span v-if="c.decodeMethod" class="text-[10px] font-mono" :class="methodColor(c.decodeMethod)">
                    via {{ c.decodeMethod }}
                  </span>
                  <span v-if="c.decodeFailedCount && c.decodeFailedCount > 0"
                    class="text-[10px] text-[var(--color-neon-red)]">
                    fail×{{ c.decodeFailedCount }}
                  </span>
                </div>
                <div class="flex items-center gap-2 flex-wrap">
                  <span class="text-[10px] font-mono text-[var(--color-text-muted)]">
                    {{ shorten(c.videoUrl) }}
                  </span>
                  <button
                    v-if="c.videoUrl"
                    @click="copy(c.videoUrl)"
                    class="px-2 py-0.5 text-[10px] rounded bg-white/5 hover:bg-white/10 text-[var(--color-text-muted)]"
                    title="Copy URL"
                  >
                    📋
                  </button>
                  <span class="badge bg-[var(--color-neon-green)]/15 text-[var(--color-neon-green)] font-mono text-[10px]">
                    {{ formatScore(c.score) }}
                  </span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- Sandbox tab -->
    <section v-if="tab === 'sandbox'">
      <div class="glass-card p-4 mb-6 max-w-3xl mx-auto border-[var(--color-neon-orange)]/30">
        <p class="text-xs text-[var(--color-text-muted)]">
          Stateless decoder for Sibnet / Aniboom / JutSu. Hits production egress; nothing is
          written to the database. Useful for verifying that a given iframe URL still decodes
          (e.g. after an upstream change). Kodik decode is not exposed here — it requires a
          variant row, use Library → Decode for that.
        </p>
      </div>

      <div class="glass-card p-6 mb-6 max-w-3xl mx-auto">
        <form @submit.prevent="runSandbox" class="space-y-4">
          <div class="flex gap-2">
            <select v-model="sandboxProvider" class="neon-input !w-auto !rounded-r-none text-sm">
              <option value="SIBNET">Sibnet</option>
              <option value="ANIBOOM">Aniboom</option>
              <option value="JUTSU">JutSu</option>
            </select>
            <input
              v-model="sandboxUrl"
              :placeholder="sandboxPlaceholders[sandboxProvider]"
              class="neon-input !rounded-l-none font-mono text-sm"
            />
          </div>
          <button
            type="submit"
            class="neon-btn w-full"
            :disabled="sandboxLoading || !sandboxUrl.trim()"
          >
            <span v-if="sandboxLoading" class="inline-block animate-spin mr-1">⟳</span>
            {{ sandboxLoading ? 'Decoding…' : 'Decode' }}
          </button>
        </form>
      </div>

      <div v-if="sandboxError" class="glass-card p-4 mb-4 max-w-3xl mx-auto border-[var(--color-neon-red)]/50">
        <p class="text-sm text-[var(--color-neon-red)]">{{ sandboxError }}</p>
      </div>

      <div v-if="sandboxResult" class="glass-card p-4 max-w-3xl mx-auto">
        <div class="flex items-center justify-between mb-3 flex-wrap gap-2">
          <span :class="['badge border', providerColor(sandboxProvider)]">
            {{ sandboxProvider }}
          </span>
          <span
            v-if="sandboxResult.success"
            class="badge bg-[var(--color-neon-green)]/15 text-[var(--color-neon-green)]"
          >
            ✓ Success
          </span>
          <span
            v-else
            class="badge bg-[var(--color-neon-red)]/15 text-[var(--color-neon-red)]"
          >
            ✗ {{ sandboxResult.errorCode ?? 'Failure' }}
          </span>
        </div>

        <p v-if="sandboxResult.format" class="text-xs text-[var(--color-text-muted)] mb-3">
          format: <span class="font-mono text-[var(--color-neon-blue)]">{{ sandboxResult.format }}</span>
        </p>

        <div v-if="sandboxResult.success" class="space-y-2">
          <div
            v-for="(url, q) in sandboxResult.qualities"
            :key="q"
            class="flex items-center justify-between gap-3 flex-wrap p-2 rounded bg-white/5"
          >
            <span class="badge bg-white/5 text-[var(--color-neon-pink)] font-mono">{{ q }}</span>
            <span class="text-[10px] font-mono text-[var(--color-text-muted)] break-all">
              {{ url }}
            </span>
            <button
              @click="copy(url)"
              class="px-2 py-0.5 text-[10px] rounded bg-white/5 hover:bg-white/10 text-[var(--color-text-muted)]"
              title="Copy URL"
            >
              📋
            </button>
          </div>
        </div>
      </div>

      <div class="max-w-3xl mx-auto mt-6">
        <details class="glass-card p-4">
          <summary class="text-sm text-[var(--color-text-muted)] cursor-pointer">
            Provider quick reference
          </summary>
          <div class="mt-3 space-y-2 text-xs text-[var(--color-text-muted)]">
            <div v-for="p in knownProviders()" :key="p.value" class="flex items-center gap-2">
              <span :class="['badge border w-20 justify-center', providerColor(p.value)]">{{ p.label }}</span>
              <span v-if="p.value === 'KODIK'">Discovery primitive — exposed under Library / Search.</span>
              <span v-if="p.value === 'SIBNET'">{{ sandboxPlaceholders.SIBNET }}</span>
              <span v-if="p.value === 'ANIBOOM'">{{ sandboxPlaceholders.ANIBOOM }}</span>
              <span v-if="p.value === 'JUTSU'">{{ sandboxPlaceholders.JUTSU }}</span>
            </div>
          </div>
        </details>
      </div>
    </section>
  </div>
</template>
