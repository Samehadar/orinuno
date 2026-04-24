<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '../api/client'
import type {
  KodikCountry,
  KodikGenre,
  KodikQuality,
  KodikTranslation,
  KodikYear,
  ReferenceKind,
  ReferenceResponse,
} from '../api/types'

type AnyItem = KodikTranslation | KodikGenre | KodikCountry | KodikYear | KodikQuality

interface KindConfig {
  id: ReferenceKind
  label: string
  icon: string
  description: string
  loader: (fresh: boolean) => Promise<ReferenceResponse<AnyItem>>
  primaryField: (item: AnyItem) => string | number
}

const kinds: KindConfig[] = [
  {
    id: 'translations',
    label: 'Translations',
    icon: '🎙',
    description: 'Озвучки и субтитры, индексируемые Kodik.',
    loader: (fresh) => api.getTranslations(fresh) as Promise<ReferenceResponse<AnyItem>>,
    primaryField: (item) => (item as KodikTranslation).title,
  },
  {
    id: 'genres',
    label: 'Genres',
    icon: '🏷',
    description: 'Каталог жанров. Эндпоинт возвращает только title и count.',
    loader: (fresh) => api.getGenres(fresh) as Promise<ReferenceResponse<AnyItem>>,
    primaryField: (item) => (item as KodikGenre).title,
  },
  {
    id: 'countries',
    label: 'Countries',
    icon: '🌍',
    description: 'Страны производства.',
    loader: (fresh) => api.getCountries(fresh) as Promise<ReferenceResponse<AnyItem>>,
    primaryField: (item) => (item as KodikCountry).title,
  },
  {
    id: 'years',
    label: 'Years',
    icon: '📅',
    description: 'Года выхода. В ответе Kodik используется поле year, а не title.',
    loader: (fresh) => api.getYears(fresh) as Promise<ReferenceResponse<AnyItem>>,
    primaryField: (item) => (item as KodikYear).year,
  },
  {
    id: 'qualities',
    label: 'Qualities',
    icon: '📺',
    description: 'Метки качества видео (WEB-DL, BDRip и т.п.).',
    loader: (fresh) => api.getQualities(fresh) as Promise<ReferenceResponse<AnyItem>>,
    primaryField: (item) => (item as KodikQuality).title,
  },
]

const activeId = ref<ReferenceKind>('translations')
const fresh = ref(false)
const loading = ref(false)
const error = ref('')
const filter = ref('')
const data = ref<Record<ReferenceKind, ReferenceResponse<AnyItem> | null>>({
  translations: null,
  genres: null,
  countries: null,
  years: null,
  qualities: null,
})
const lastLoaded = ref<Record<ReferenceKind, string>>({
  translations: '',
  genres: '',
  countries: '',
  years: '',
  qualities: '',
})

const activeKind = computed(() => kinds.find((k) => k.id === activeId.value)!)
const activeResponse = computed(() => data.value[activeId.value])
const filtered = computed(() => {
  const resp = activeResponse.value
  if (!resp) return []
  const q = filter.value.trim().toLowerCase()
  if (!q) return resp.results
  return resp.results.filter((item) =>
    String(activeKind.value.primaryField(item)).toLowerCase().includes(q),
  )
})

async function load(kind: ReferenceKind) {
  loading.value = true
  error.value = ''
  try {
    const cfg = kinds.find((k) => k.id === kind)!
    data.value[kind] = await cfg.loader(fresh.value)
    lastLoaded.value[kind] = new Date().toLocaleTimeString()
  } catch (e: any) {
    error.value = e.message ?? String(e)
  } finally {
    loading.value = false
  }
}

function select(id: ReferenceKind) {
  activeId.value = id
  filter.value = ''
  if (!data.value[id]) {
    load(id)
  }
}

onMounted(() => {
  load(activeId.value)
})
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-6 flex-wrap gap-3">
      <h1 class="text-3xl font-bold">
        <span class="gradient-text">Reference Catalog</span>
      </h1>
      <div class="flex items-center gap-3">
        <label class="flex items-center gap-2 text-sm text-[var(--color-text-muted)]">
          <input
            type="checkbox"
            v-model="fresh"
            class="accent-[var(--color-neon-pink)]"
          />
          Skip cache (fresh=true)
        </label>
        <button class="neon-btn text-sm" :disabled="loading" @click="load(activeId)">
          {{ loading ? 'Loading…' : 'Reload' }}
        </button>
      </div>
    </div>

    <p class="text-sm text-[var(--color-text-muted)] mb-4">
      Cached dictionaries exposed by <code class="font-mono">/api/v1/reference/*</code>. Values
      are served from Caffeine unless the server-side toggle is off or you pass
      <code class="font-mono">fresh=true</code>.
    </p>

    <div class="flex gap-2 flex-wrap mb-4">
      <button
        v-for="k in kinds"
        :key="k.id"
        class="px-3 py-2 rounded-lg text-sm transition-all border"
        :class="k.id === activeId
          ? 'border-[var(--color-neon-pink)] text-[var(--color-neon-pink)] bg-[rgba(255,45,117,0.08)]'
          : 'border-white/10 text-[var(--color-text-muted)] hover:text-white hover:border-white/30'"
        @click="select(k.id)"
      >
        <span class="mr-1">{{ k.icon }}</span>
        {{ k.label }}
      </button>
    </div>

    <div v-if="error" class="glass-card p-4 mb-4 border border-[var(--color-neon-red)]/40">
      <p class="text-sm text-[var(--color-neon-red)]">{{ error }}</p>
    </div>

    <div class="glass-card p-6">
      <div class="flex items-center justify-between flex-wrap gap-3 mb-4">
        <div>
          <h2 class="text-lg font-semibold">
            <span class="mr-2">{{ activeKind.icon }}</span>
            {{ activeKind.label }}
          </h2>
          <p class="text-xs text-[var(--color-text-muted)] mt-1">{{ activeKind.description }}</p>
        </div>
        <div class="flex items-center gap-3 text-xs text-[var(--color-text-muted)]">
          <span v-if="activeResponse">
            total: <span class="text-white">{{ activeResponse.total }}</span>
          </span>
          <span v-if="activeResponse">
            server time:
            <span class="font-mono text-[var(--color-neon-blue)]">{{ activeResponse.time }}</span>
          </span>
          <span v-if="lastLoaded[activeId]">updated {{ lastLoaded[activeId] }}</span>
        </div>
      </div>

      <input
        v-model="filter"
        type="text"
        placeholder="Filter…"
        class="w-full mb-4 px-3 py-2 rounded-lg bg-white/5 border border-white/10 text-sm outline-none focus:border-[var(--color-neon-pink)]/60"
      />

      <div v-if="loading && !activeResponse" class="grid grid-cols-2 md:grid-cols-4 gap-2">
        <div v-for="i in 12" :key="i" class="skeleton h-10 w-full" />
      </div>

      <div
        v-else-if="filtered.length"
        class="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-2"
      >
        <div
          v-for="(item, idx) in filtered"
          :key="idx"
          class="flex items-center justify-between px-3 py-2 rounded-lg bg-white/5 border border-white/5"
        >
          <span class="text-sm truncate" :title="String(activeKind.primaryField(item))">
            {{ activeKind.primaryField(item) }}
          </span>
          <span class="ml-2 text-xs text-[var(--color-text-muted)]">
            {{ (item as { count: number }).count }}
          </span>
        </div>
      </div>

      <p v-else class="text-sm text-[var(--color-text-muted)]">No entries.</p>
    </div>
  </div>
</template>
