<script setup lang="ts">
import { ref } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const mobileMenuOpen = ref(false)

const navItems = [
  { to: '/', label: 'Search', icon: '🔍' },
  { to: '/content', label: 'Library', icon: '📚' },
  { to: '/calendar', label: 'Calendar', icon: '📅' },
  { to: '/reference', label: 'Reference', icon: '📖' },
  { to: '/health', label: 'Health', icon: '💊' },
]
</script>

<template>
  <div class="min-h-screen relative">
    <!-- Particles background -->
    <div class="particles">
      <div v-for="i in 20" :key="i" class="particle"
        :style="{
          left: `${Math.random() * 100}%`,
          animationDuration: `${8 + Math.random() * 12}s`,
          animationDelay: `${Math.random() * 5}s`,
          width: `${1 + Math.random() * 2}px`,
          height: `${1 + Math.random() * 2}px`,
        }" />
    </div>

    <!-- Nav -->
    <nav class="sticky top-0 z-50 glass-card !rounded-none border-x-0 border-t-0">
      <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div class="flex items-center justify-between h-16">
          <router-link to="/" class="flex items-center gap-2 group">
            <span class="text-2xl">⛩️</span>
            <span class="text-xl font-bold gradient-text font-[var(--font-heading)]">
              Orinuno
            </span>
          </router-link>

          <!-- Desktop nav -->
          <div class="hidden sm:flex items-center gap-1">
            <router-link
              v-for="item in navItems"
              :key="item.to"
              :to="item.to"
              class="relative px-4 py-2 rounded-lg text-sm font-medium transition-all duration-300"
              :class="route.path === item.to || (item.to !== '/' && route.path.startsWith(item.to))
                ? 'text-[var(--color-neon-pink)]'
                : 'text-[var(--color-text-muted)] hover:text-[var(--color-text-primary)]'"
            >
              <span class="mr-1.5">{{ item.icon }}</span>
              {{ item.label }}
              <span
                v-if="route.path === item.to || (item.to !== '/' && route.path.startsWith(item.to))"
                class="absolute bottom-0 left-2 right-2 h-0.5 bg-gradient-to-r from-[var(--color-neon-pink)] to-[var(--color-neon-blue)] rounded-full"
              />
            </router-link>
          </div>

          <!-- Mobile menu button -->
          <button
            class="sm:hidden text-[var(--color-text-muted)] hover:text-white"
            @click="mobileMenuOpen = !mobileMenuOpen"
          >
            <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                :d="mobileMenuOpen ? 'M6 18L18 6M6 6l12 12' : 'M4 6h16M4 12h16M4 18h16'" />
            </svg>
          </button>
        </div>

        <!-- Mobile menu -->
        <div v-if="mobileMenuOpen" class="sm:hidden pb-4 space-y-1">
          <router-link
            v-for="item in navItems"
            :key="item.to"
            :to="item.to"
            class="block px-4 py-2 rounded-lg text-sm font-medium transition-all"
            :class="route.path === item.to
              ? 'text-[var(--color-neon-pink)] bg-[rgba(255,45,117,0.1)]'
              : 'text-[var(--color-text-muted)]'"
            @click="mobileMenuOpen = false"
          >
            <span class="mr-2">{{ item.icon }}</span>{{ item.label }}
          </router-link>
        </div>
      </div>
    </nav>

    <!-- Content -->
    <main class="relative z-10 max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
      <router-view />
    </main>
  </div>
</template>
