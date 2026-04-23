<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api/client'
import type { ContentDto, ParseRequest } from '../api/types'
import ScreenshotImage from '../components/ScreenshotImage.vue'

const router = useRouter()
const query = ref('')
const searchBy = ref<'title' | 'kinopoiskId' | 'imdbId' | 'shikimoriId'>('title')
const decodeLinks = ref(false)
const loading = ref(false)
const results = ref<ContentDto[]>([])
const error = ref('')
const searched = ref(false)
const elapsed = ref(0)
let timer: ReturnType<typeof setInterval> | null = null

async function search() {
  if (!query.value.trim()) return
  loading.value = true
  error.value = ''
  searched.value = true
  elapsed.value = 0
  timer = setInterval(() => elapsed.value++, 1000)

  const req: ParseRequest = { decodeLinks: decodeLinks.value }
  if (searchBy.value === 'title') req.title = query.value
  else if (searchBy.value === 'kinopoiskId') req.kinopoiskId = query.value
  else if (searchBy.value === 'imdbId') req.imdbId = query.value
  else req.shikimoriId = query.value

  try {
    results.value = await api.searchContent(req)
  } catch (e: any) {
    error.value = e.message || 'Request failed'
  } finally {
    loading.value = false
    if (timer) { clearInterval(timer); timer = null }
  }
}

interface UniqueContent extends ContentDto {
  translationCount: number
}

const uniqueResults = computed<UniqueContent[]>(() => {
  const seen = new Map<number, UniqueContent>()
  for (const item of results.value) {
    if (seen.has(item.id)) {
      seen.get(item.id)!.translationCount++
    } else {
      seen.set(item.id, { ...item, translationCount: 1 })
    }
  }
  return Array.from(seen.values())
})

function typeColor(type: string) {
  const t = type?.toLowerCase()
  if (t === 'anime-serial' || t === 'anime') return 'bg-[var(--color-neon-pink)]/20 text-[var(--color-neon-pink)]'
  if (t?.includes('serial')) return 'bg-[var(--color-neon-blue)]/20 text-[var(--color-neon-blue)]'
  return 'bg-[var(--color-neon-orange)]/20 text-[var(--color-neon-orange)]'
}
</script>

<template>
  <div>
    <!-- Hero -->
    <div class="text-center mb-10">
      <h1 class="text-4xl sm:text-5xl font-extrabold mb-3">
        <span class="gradient-text">Kodik Search</span>
      </h1>
      <p class="text-[var(--color-text-muted)] text-lg">
        Search anime, movies and series via Kodik API
      </p>
    </div>

    <!-- Search form -->
    <div class="glass-card p-6 mb-8 max-w-2xl mx-auto">
      <form @submit.prevent="search" class="space-y-4">
        <div class="flex gap-2">
          <select
            v-model="searchBy"
            class="neon-input !w-auto !rounded-r-none text-sm"
          >
            <option value="title">Title</option>
            <option value="kinopoiskId">Kinopoisk ID</option>
            <option value="imdbId">IMDB ID</option>
            <option value="shikimoriId">Shikimori ID</option>
          </select>
          <input
            v-model="query"
            :placeholder="searchBy === 'title' ? 'Naruto, Chainsaw Man...' : 'Enter ID...'"
            class="neon-input !rounded-l-none"
          />
        </div>
        <div class="flex items-center justify-between">
          <label class="flex items-center gap-2 text-sm text-[var(--color-text-muted)] cursor-pointer">
            <input
              v-model="decodeLinks"
              type="checkbox"
              class="w-4 h-4 accent-[var(--color-neon-pink)]"
            />
            Decode MP4 links
          </label>
          <button type="submit" class="neon-btn" :disabled="loading || !query.trim()">
            <span v-if="loading" class="inline-block animate-spin mr-1">⟳</span>
            {{ loading ? 'Searching...' : 'Search' }}
          </button>
        </div>
      </form>
    </div>

    <!-- Error -->
    <div v-if="error" class="glass-card p-4 mb-6 border-[var(--color-neon-red)]/50 max-w-2xl mx-auto">
      <p class="text-[var(--color-neon-red)] text-sm">{{ error }}</p>
    </div>

    <!-- Loading -->
    <div v-if="loading" class="space-y-4">
      <div class="text-center py-4">
        <p class="text-[var(--color-text-muted)]">
          Searching Kodik API...
          <span class="text-[var(--color-neon-blue)] font-mono ml-1">{{ elapsed }}s</span>
        </p>
        <p v-if="elapsed > 5" class="text-xs text-[var(--color-text-muted)] mt-1">
          Kodik API can take 20-40 seconds for popular titles
        </p>
      </div>
      <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
        <div v-for="i in 6" :key="i" class="glass-card overflow-hidden">
          <div class="skeleton h-40 w-full" />
          <div class="p-4 space-y-2">
            <div class="skeleton h-5 w-3/4" />
            <div class="skeleton h-4 w-1/2" />
          </div>
        </div>
      </div>
    </div>

    <!-- Results -->
    <div v-else-if="uniqueResults.length" class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
      <div
        v-for="item in uniqueResults"
        :key="item.id"
        class="glass-card overflow-hidden cursor-pointer group"
        @click="router.push({ name: 'content-detail', params: { id: item.id } })"
      >
        <div class="relative h-44 overflow-hidden bg-[#0d1020]">
          <ScreenshotImage
            :src="item.screenshots?.[0]"
            :alt="item.title"
            img-class="w-full h-full object-cover transition-transform duration-500 group-hover:scale-110"
          />
          <div class="absolute inset-0 bg-gradient-to-t from-[var(--color-bg-deep)] via-transparent to-transparent" />
          <span :class="['badge absolute top-2 right-2', typeColor(item.type)]">
            {{ item.type }}
          </span>
        </div>

        <div class="p-4">
          <h3 class="font-semibold text-sm line-clamp-2 group-hover:text-[var(--color-neon-pink)] transition-colors">
            {{ item.title }}
          </h3>
          <p v-if="item.titleOrig" class="text-xs text-[var(--color-text-muted)] mt-1 line-clamp-1">
            {{ item.titleOrig }}
          </p>
          <div class="flex flex-wrap items-center gap-2 mt-2 text-xs text-[var(--color-text-muted)]">
            <span v-if="item.year">{{ item.year }}</span>
            <span v-if="item.kinopoiskRating" class="flex items-center gap-0.5 text-[#ff6600] font-medium">
              KP {{ item.kinopoiskRating }}
            </span>
            <span v-if="item.imdbRating" class="flex items-center gap-0.5 text-[#f5c518] font-medium">
              {{ item.imdbRating }}
            </span>
            <span v-if="item.quality" class="badge bg-white/5 text-[var(--color-neon-blue)]">{{ item.quality }}</span>
            <span v-if="item.lastEpisode" class="badge bg-white/5 text-[var(--color-neon-green)]">
              {{ item.lastEpisode }} ep
            </span>
            <span v-if="item.translationCount > 1" class="badge bg-white/5 text-[var(--color-neon-orange)]">
              {{ item.translationCount }} dubs
            </span>
          </div>
          <div v-if="item.genres" class="flex flex-wrap gap-1 mt-1.5">
            <span
              v-for="g in item.genres.split(',').slice(0, 3)"
              :key="g"
              class="px-1.5 py-0.5 rounded text-[10px] bg-white/5 text-[var(--color-text-muted)]"
            >{{ g.trim() }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Empty state -->
    <div v-else-if="searched && !loading" class="text-center py-16">
      <span class="text-5xl block mb-4">🔮</span>
      <p class="text-[var(--color-text-muted)] text-lg">No results found</p>
      <p class="text-[var(--color-text-muted)] text-sm mt-1">Try a different search query</p>
    </div>
  </div>
</template>
