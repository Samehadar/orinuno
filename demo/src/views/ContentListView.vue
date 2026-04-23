<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api/client'
import type { ContentDto, PageResponse } from '../api/types'
import ScreenshotImage from '../components/ScreenshotImage.vue'

const router = useRouter()
const page = ref(0)
const sortBy = ref('updatedAt')
const order = ref<'ASC' | 'DESC'>('DESC')
const loading = ref(false)
const data = ref<PageResponse<ContentDto> | null>(null)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await api.getContentList(page.value, 18, sortBy.value, order.value)
  } catch (e: any) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function prevPage() {
  if (page.value > 0) {
    page.value--
    load()
  }
}

function nextPage() {
  if (data.value && page.value < data.value.totalPages - 1) {
    page.value++
    load()
  }
}

function typeColor(type: string) {
  const t = type?.toLowerCase()
  if (t === 'anime-serial' || t === 'anime') return 'bg-[var(--color-neon-pink)]/20 text-[var(--color-neon-pink)]'
  if (t?.includes('serial')) return 'bg-[var(--color-neon-blue)]/20 text-[var(--color-neon-blue)]'
  return 'bg-[var(--color-neon-orange)]/20 text-[var(--color-neon-orange)]'
}

watch([sortBy, order], () => {
  page.value = 0
  load()
})

onMounted(load)
</script>

<template>
  <div>
    <div class="flex flex-col sm:flex-row sm:items-center justify-between mb-6 gap-4">
      <h1 class="text-3xl font-bold">
        <span class="gradient-text">Library</span>
        <span v-if="data" class="text-base font-normal text-[var(--color-text-muted)] ml-2">
          {{ data.totalElements }} items
        </span>
      </h1>
      <div class="flex gap-2">
        <select v-model="sortBy" class="neon-input !w-auto text-sm">
          <option value="updatedAt">Updated</option>
          <option value="createdAt">Created</option>
          <option value="title">Title</option>
          <option value="year">Year</option>
          <option value="kinopoisk_rating">KP Rating</option>
          <option value="imdb_rating">IMDb Rating</option>
        </select>
        <select v-model="order" class="neon-input !w-auto text-sm">
          <option value="DESC">↓ Desc</option>
          <option value="ASC">↑ Asc</option>
        </select>
      </div>
    </div>

    <!-- Error -->
    <div v-if="error" class="glass-card p-4 mb-6 border-[var(--color-neon-red)]/50">
      <p class="text-[var(--color-neon-red)] text-sm">{{ error }}</p>
    </div>

    <!-- Loading -->
    <div v-if="loading" class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
      <div v-for="i in 6" :key="i" class="glass-card overflow-hidden">
        <div class="skeleton h-36 w-full" />
        <div class="p-4 space-y-2">
          <div class="skeleton h-5 w-3/4" />
          <div class="skeleton h-4 w-1/2" />
        </div>
      </div>
    </div>

    <!-- Content grid -->
    <div v-else-if="data?.content.length" class="space-y-6">
      <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
        <div
          v-for="item in data.content"
          :key="item.id"
          class="glass-card overflow-hidden cursor-pointer group"
          @click="router.push({ name: 'content-detail', params: { id: item.id } })"
        >
          <div class="relative h-36 overflow-hidden bg-[#0d1020]">
            <ScreenshotImage
              :src="item.screenshots?.[0]"
              :alt="item.title"
              img-class="w-full h-full object-cover transition-transform duration-500 group-hover:scale-110"
              placeholder-class="w-full h-full flex items-center justify-center text-3xl opacity-20"
            />
            <div class="absolute inset-0 bg-gradient-to-t from-[var(--color-bg-deep)] via-transparent to-transparent" />
            <span :class="['badge absolute top-2 right-2', typeColor(item.type)]">
              {{ item.type }}
            </span>
          </div>
          <div class="p-4">
            <h3 class="font-semibold text-sm line-clamp-1 group-hover:text-[var(--color-neon-pink)] transition-colors">
              {{ item.title }}
            </h3>
            <div class="flex items-center gap-2 mt-1.5 text-xs text-[var(--color-text-muted)]">
              <span v-if="item.year">{{ item.year }}</span>
              <span v-if="item.kinopoiskRating" class="flex items-center gap-0.5 text-[#ff6600] font-medium">
                KP {{ item.kinopoiskRating }}
              </span>
              <span v-if="item.imdbRating" class="flex items-center gap-0.5 text-[#f5c518] font-medium">
                {{ item.imdbRating }}
              </span>
              <span v-if="item.lastEpisode" class="badge bg-white/5 text-[var(--color-neon-green)]">
                {{ item.lastEpisode }} ep
              </span>
            </div>
            <div v-if="item.genres" class="flex flex-wrap gap-1 mt-1.5">
              <span
                v-for="g in item.genres.split(',').slice(0, 3)"
                :key="g"
                class="px-1.5 py-0.5 rounded text-[10px] bg-white/5 text-[var(--color-text-muted)]"
              >{{ g.trim() }}</span>
              <span
                v-if="item.genres.split(',').length > 3"
                class="px-1.5 py-0.5 rounded text-[10px] bg-white/5 text-[var(--color-text-muted)]"
              >+{{ item.genres.split(',').length - 3 }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Pagination -->
      <div class="flex items-center justify-center gap-4">
        <button class="neon-btn !py-2 !px-4 text-sm" :disabled="page === 0" @click="prevPage">← Prev</button>
        <span class="text-sm text-[var(--color-text-muted)]">
          {{ page + 1 }} / {{ data.totalPages }}
        </span>
        <button class="neon-btn !py-2 !px-4 text-sm" :disabled="page >= data.totalPages - 1" @click="nextPage">Next →</button>
      </div>
    </div>

    <!-- Empty -->
    <div v-else-if="!loading" class="text-center py-16">
      <span class="text-5xl block mb-4">📭</span>
      <p class="text-[var(--color-text-muted)] text-lg">Library is empty</p>
      <p class="text-[var(--color-text-muted)] text-sm mt-1">Search for content first</p>
    </div>
  </div>
</template>
