<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api/client'
import type { ContentExportDto } from '../api/types'

const route = useRoute()
const router = useRouter()
const id = Number(route.params.id)
const data = ref<ContentExportDto | null>(null)
const loading = ref(true)
const error = ref('')
const showJson = ref(false)
const expandedSeasons = ref<Set<number>>(new Set())

async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await api.getExport(id)
    if (data.value.seasons?.length) {
      expandedSeasons.value.add(data.value.seasons[0].seasonNumber)
    }
  } catch (e: any) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function toggleSeason(num: number) {
  if (expandedSeasons.value.has(num)) expandedSeasons.value.delete(num)
  else expandedSeasons.value.add(num)
}

const totalVariants = computed(() => {
  if (!data.value) return 0
  return data.value.seasons?.reduce((acc, s) =>
    acc + s.episodes.reduce((a, e) => a + e.variants.length, 0), 0) ?? 0
})

const decodedVariants = computed(() => {
  if (!data.value) return 0
  return data.value.seasons?.reduce((acc, s) =>
    acc + s.episodes.reduce((a, e) => a + e.variants.filter(v => v.mp4Link).length, 0), 0) ?? 0
})

const prettyJson = computed(() => data.value ? JSON.stringify(data.value, null, 2) : '')

function copyJson() {
  navigator.clipboard.writeText(prettyJson.value)
}

onMounted(load)
</script>

<template>
  <div>
    <button
      class="text-sm text-[var(--color-text-muted)] hover:text-white mb-4 transition-colors"
      @click="router.back()"
    >
      ← Back
    </button>

    <div v-if="loading" class="space-y-4">
      <div class="skeleton h-8 w-1/3" />
      <div class="skeleton h-64 w-full" />
    </div>

    <div v-else-if="error" class="glass-card p-6 text-center">
      <p class="text-[var(--color-neon-red)]">{{ error }}</p>
    </div>

    <template v-else-if="data">
      <!-- Header -->
      <div class="flex flex-col sm:flex-row sm:items-center justify-between mb-6 gap-4">
        <div>
          <h1 class="text-2xl font-bold">
            <span class="gradient-text">Export</span>: {{ data.title }}
          </h1>
          <p class="text-[var(--color-text-muted)] text-sm mt-1">
            {{ totalVariants }} variants, {{ decodedVariants }} decoded
          </p>
        </div>
        <div class="flex gap-2">
          <button
            class="neon-btn text-sm"
            @click="showJson = !showJson"
          >
            {{ showJson ? 'Tree view' : 'JSON view' }}
          </button>
          <button
            v-if="showJson"
            class="neon-btn !bg-gradient-to-r !from-[var(--color-neon-blue)] !to-[var(--color-neon-green)] text-sm"
            @click="copyJson"
          >
            Copy
          </button>
        </div>
      </div>

      <!-- Meta -->
      <div class="grid grid-cols-2 sm:grid-cols-4 gap-3 mb-6">
        <div class="glass-card p-3 text-center">
          <p class="text-xs text-[var(--color-text-muted)]">Type</p>
          <p class="font-semibold text-sm mt-1">{{ data.type }}</p>
        </div>
        <div class="glass-card p-3 text-center">
          <p class="text-xs text-[var(--color-text-muted)]">Year</p>
          <p class="font-semibold text-sm mt-1">{{ data.year ?? '—' }}</p>
        </div>
        <div class="glass-card p-3 text-center">
          <p class="text-xs text-[var(--color-text-muted)]">Seasons</p>
          <p class="font-semibold text-sm mt-1">{{ data.seasons?.length ?? 0 }}</p>
        </div>
        <div class="glass-card p-3 text-center">
          <p class="text-xs text-[var(--color-text-muted)]">Decoded</p>
          <p class="font-semibold text-sm mt-1" :class="decodedVariants === totalVariants ? 'text-[var(--color-neon-green)]' : 'text-[var(--color-neon-orange)]'">
            {{ Math.round((decodedVariants / (totalVariants || 1)) * 100) }}%
          </p>
        </div>
      </div>

      <!-- JSON view -->
      <div v-if="showJson" class="glass-card p-4 overflow-auto max-h-[600px]">
        <pre class="text-xs text-[var(--color-text-muted)] whitespace-pre font-mono">{{ prettyJson }}</pre>
      </div>

      <!-- Tree view -->
      <div v-else class="space-y-3">
        <div v-for="season in data.seasons" :key="season.seasonNumber" class="glass-card overflow-hidden">
          <button
            class="w-full px-4 py-3 flex items-center justify-between hover:bg-white/[0.02] transition-colors"
            @click="toggleSeason(season.seasonNumber)"
          >
            <span class="font-semibold">
              <span class="text-[var(--color-neon-blue)]">Season {{ season.seasonNumber }}</span>
              <span class="text-[var(--color-text-muted)] text-sm ml-2">
                {{ season.episodes.length }} episodes
              </span>
            </span>
            <span class="text-[var(--color-text-muted)] transition-transform duration-200"
              :class="expandedSeasons.has(season.seasonNumber) ? 'rotate-180' : ''">
              ▼
            </span>
          </button>

          <div v-if="expandedSeasons.has(season.seasonNumber)" class="border-t border-white/5">
            <div v-for="episode in season.episodes" :key="episode.episodeNumber" class="border-b border-white/5 last:border-0">
              <div class="px-4 py-2 bg-white/[0.01]">
                <span class="text-sm font-medium">Episode {{ episode.episodeNumber }}</span>
                <span class="text-xs text-[var(--color-text-muted)] ml-2">
                  {{ episode.variants.length }} variant(s)
                </span>
              </div>
              <div class="px-4 pb-2">
                <div
                  v-for="variant in episode.variants"
                  :key="variant.id"
                  class="flex items-center gap-3 py-1.5 text-sm"
                >
                  <span class="text-[var(--color-text-muted)] w-32 truncate" :title="variant.translationTitle">
                    {{ variant.translationTitle }}
                  </span>
                  <span class="badge bg-white/5 text-[var(--color-neon-blue)] text-xs">
                    {{ variant.translationType }}
                  </span>
                  <span v-if="variant.quality" class="badge bg-white/5 text-xs">
                    {{ variant.quality }}
                  </span>
                  <span
                    :class="variant.mp4Link
                      ? 'text-[var(--color-neon-green)]'
                      : 'text-[var(--color-text-muted)]'"
                    class="text-xs ml-auto"
                  >
                    {{ variant.mp4Link ? '✓ decoded' : '— pending' }}
                  </span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
