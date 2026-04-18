<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { api } from '../api/client'
import type { DecoderHealth, HealthResponse, ProxyHealth, SchemaDriftHealth } from '../api/types'

const health = ref<HealthResponse | null>(null)
const decoder = ref<DecoderHealth | null>(null)
const proxy = ref<ProxyHealth | null>(null)
const schemaDrift = ref<SchemaDriftHealth | null>(null)
const loading = ref(true)
const error = ref('')
const lastUpdated = ref('')
let timer: ReturnType<typeof setInterval>

function timeAgo(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime()
  const mins = Math.floor(diff / 60000)
  if (mins < 1) return 'just now'
  if (mins < 60) return `${mins}m ago`
  const hours = Math.floor(mins / 60)
  if (hours < 24) return `${hours}h ago`
  return `${Math.floor(hours / 24)}d ago`
}

async function load() {
  error.value = ''
  try {
    const [h, d, p, sd] = await Promise.allSettled([
      api.getHealth(),
      api.getDecoderHealth(),
      api.getProxyHealth(),
      api.getSchemaDriftHealth(),
    ])
    if (h.status === 'fulfilled') health.value = h.value
    if (d.status === 'fulfilled') decoder.value = d.value
    if (p.status === 'fulfilled') proxy.value = p.value
    if (sd.status === 'fulfilled') schemaDrift.value = sd.value
    lastUpdated.value = new Date().toLocaleTimeString()
  } catch (e: any) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  load()
  timer = setInterval(load, 10000)
})

onUnmounted(() => clearInterval(timer))

function statusColor(status: string) {
  if (status === 'UP' || status === 'HEALTHY') return 'text-[var(--color-neon-green)]'
  if (status === 'DEGRADED') return 'text-[var(--color-neon-orange)]'
  return 'text-[var(--color-neon-red)]'
}
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-3xl font-bold">
        <span class="gradient-text">Health Dashboard</span>
      </h1>
      <div class="flex items-center gap-3">
        <span v-if="lastUpdated" class="text-xs text-[var(--color-text-muted)]">
          Updated: {{ lastUpdated }}
        </span>
        <button class="neon-btn text-sm" @click="load">Refresh</button>
      </div>
    </div>

    <!-- Loading -->
    <div v-if="loading" class="grid grid-cols-1 md:grid-cols-3 gap-4">
      <div v-for="i in 3" :key="i" class="glass-card p-6">
        <div class="skeleton h-6 w-1/2 mb-4" />
        <div class="skeleton h-16 w-full mb-2" />
        <div class="skeleton h-4 w-3/4" />
      </div>
    </div>

    <div v-else class="space-y-4">
    <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
      <!-- Service health -->
      <div class="glass-card p-6">
        <div class="flex items-center gap-3 mb-4">
          <div class="w-3 h-3 rounded-full animate-pulse-glow"
            :class="health?.status === 'UP' ? 'bg-[var(--color-neon-green)]' : 'bg-[var(--color-neon-red)]'" />
          <h2 class="text-lg font-semibold">Service</h2>
        </div>
        <div v-if="health" class="space-y-3">
          <div class="flex justify-between text-sm">
            <span class="text-[var(--color-text-muted)]">Status</span>
            <span :class="statusColor(health.status)" class="font-semibold">{{ health.status }}</span>
          </div>
          <div v-if="health.decoderAvailable !== undefined" class="flex justify-between text-sm">
            <span class="text-[var(--color-text-muted)]">Decoder</span>
            <span :class="health.decoderAvailable ? 'text-[var(--color-neon-green)]' : 'text-[var(--color-neon-red)]'">
              {{ health.decoderAvailable ? 'Available' : 'Down' }}
            </span>
          </div>
          <div v-if="health.proxyPoolSize !== undefined" class="flex justify-between text-sm">
            <span class="text-[var(--color-text-muted)]">Proxy Pool</span>
            <span>{{ health.proxyPoolSize }}</span>
          </div>
        </div>
        <p v-else class="text-[var(--color-neon-red)] text-sm">Unavailable</p>
      </div>

      <!-- Decoder health -->
      <div class="glass-card p-6">
        <div class="flex items-center gap-3 mb-4">
          <span class="text-xl">🔓</span>
          <h2 class="text-lg font-semibold">Decoder</h2>
        </div>
        <div v-if="decoder" class="space-y-3">
          <div class="flex justify-between text-sm">
            <span class="text-[var(--color-text-muted)]">Total Attempts</span>
            <span>{{ decoder.totalAttempts }}</span>
          </div>
          <div class="flex justify-between text-sm">
            <span class="text-[var(--color-text-muted)]">Success</span>
            <span class="text-[var(--color-neon-green)]">{{ decoder.successCount }}</span>
          </div>
          <div class="flex justify-between text-sm">
            <span class="text-[var(--color-text-muted)]">Failures</span>
            <span class="text-[var(--color-neon-red)]">{{ decoder.failureCount }}</span>
          </div>
          <!-- Success rate bar -->
          <div>
            <div class="flex justify-between text-xs text-[var(--color-text-muted)] mb-1">
              <span>Success Rate</span>
              <span>{{ (decoder.successRate * 100).toFixed(1) }}%</span>
            </div>
            <div class="h-2 rounded-full bg-white/5 overflow-hidden">
              <div
                class="h-full rounded-full transition-all duration-500"
                :class="decoder.successRate > 0.8 ? 'bg-[var(--color-neon-green)]' :
                  decoder.successRate > 0.5 ? 'bg-[var(--color-neon-orange)]' : 'bg-[var(--color-neon-red)]'"
                :style="{ width: `${decoder.successRate * 100}%` }"
              />
            </div>
          </div>
        </div>
        <p v-else class="text-[var(--color-text-muted)] text-sm">No data</p>
      </div>

      <!-- Proxy health -->
      <div class="glass-card p-6">
        <div class="flex items-center gap-3 mb-4">
          <span class="text-xl">🌐</span>
          <h2 class="text-lg font-semibold">Proxy</h2>
        </div>
        <div v-if="proxy" class="space-y-3">
          <div class="flex justify-between text-sm">
            <span class="text-[var(--color-text-muted)]">Enabled</span>
            <span :class="proxy.enabled ? 'text-[var(--color-neon-green)]' : 'text-[var(--color-text-muted)]'">
              {{ proxy.enabled ? 'Yes' : 'No' }}
            </span>
          </div>
          <div class="flex justify-between text-sm">
            <span class="text-[var(--color-text-muted)]">Total</span>
            <span>{{ proxy.totalProxies }}</span>
          </div>
          <div class="flex justify-between text-sm">
            <span class="text-[var(--color-text-muted)]">Active</span>
            <span class="text-[var(--color-neon-green)]">{{ proxy.activeProxies }}</span>
          </div>
          <div class="flex justify-between text-sm">
            <span class="text-[var(--color-text-muted)]">Failed</span>
            <span class="text-[var(--color-neon-red)]">{{ proxy.failedProxies }}</span>
          </div>
          <div class="flex justify-between text-sm">
            <span class="text-[var(--color-text-muted)]">Strategy</span>
            <span class="badge bg-white/5 text-[var(--color-neon-blue)]">{{ proxy.strategy }}</span>
          </div>
        </div>
        <p v-else class="text-[var(--color-text-muted)] text-sm">No data</p>
      </div>

      <!-- Schema Drift card -->
      <div class="glass-card p-6">
        <div class="flex items-center gap-3 mb-4">
          <div class="w-3 h-3 rounded-full animate-pulse-glow"
            :class="schemaDrift?.status === 'CLEAN' ? 'bg-[var(--color-neon-green)]' : 'bg-[var(--color-neon-orange)]'" />
          <h2 class="text-lg font-semibold">Schema Drift</h2>
        </div>
        <div v-if="schemaDrift" class="space-y-3">
          <div class="flex justify-between text-sm">
            <span class="text-[var(--color-text-muted)]">Status</span>
            <span :class="schemaDrift.status === 'CLEAN' ? 'text-[var(--color-neon-green)]' : 'text-[var(--color-neon-orange)]'" class="font-semibold">
              {{ schemaDrift.status === 'CLEAN' ? 'Clean' : 'Drift Detected' }}
            </span>
          </div>
          <div class="flex justify-between text-sm">
            <span class="text-[var(--color-text-muted)]">Checks</span>
            <span>{{ schemaDrift.totalChecks }}</span>
          </div>
          <div class="flex justify-between text-sm">
            <span class="text-[var(--color-text-muted)]">Drift Events</span>
            <span :class="schemaDrift.totalDriftsDetected > 0 ? 'text-[var(--color-neon-orange)]' : ''">
              {{ schemaDrift.totalDriftsDetected }}
            </span>
          </div>
          <div class="flex justify-between text-sm">
            <span class="text-[var(--color-text-muted)]">Affected Types</span>
            <span :class="schemaDrift.affectedTypes > 0 ? 'text-[var(--color-neon-orange)]' : ''">
              {{ schemaDrift.affectedTypes }}
            </span>
          </div>
        </div>
        <p v-else class="text-[var(--color-text-muted)] text-sm">No data</p>
      </div>
    </div>

    <!-- Schema Drift details -->
    <div v-if="schemaDrift?.drifts?.length" class="glass-card overflow-hidden">
      <div class="px-6 py-4 border-b border-white/5">
        <div class="flex items-center gap-2">
          <span class="text-[var(--color-neon-orange)]">⚠</span>
          <h3 class="font-semibold">Schema Drift Details</h3>
          <span class="badge bg-[var(--color-neon-orange)]/10 text-[var(--color-neon-orange)] text-xs">
            {{ schemaDrift.drifts.length }} type{{ schemaDrift.drifts.length > 1 ? 's' : '' }} affected
          </span>
        </div>
        <p class="text-xs text-[var(--color-text-muted)] mt-1">
          Kodik API returned fields not present in our DTO models. These may be new API features to integrate.
        </p>
      </div>
      <div class="divide-y divide-white/5">
        <div v-for="drift in schemaDrift.drifts" :key="drift.type" class="px-6 py-4">
          <div class="flex items-center justify-between mb-2">
            <span class="font-mono text-sm text-[var(--color-neon-blue)]">{{ drift.type }}</span>
            <div class="flex items-center gap-3 text-xs text-[var(--color-text-muted)]">
              <span>{{ drift.hitCount }}x seen</span>
              <span>first: {{ timeAgo(drift.firstSeen) }}</span>
              <span>last: {{ timeAgo(drift.lastSeen) }}</span>
            </div>
          </div>
          <div class="flex flex-wrap gap-1.5">
            <span
              v-for="field in drift.unknownFields"
              :key="field"
              class="px-2 py-1 rounded text-xs font-mono bg-[var(--color-neon-orange)]/10 text-[var(--color-neon-orange)] border border-[var(--color-neon-orange)]/20"
            >{{ field }}</span>
          </div>
        </div>
      </div>
    </div>
    </div>
  </div>
</template>
