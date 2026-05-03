import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'search', component: () => import('./views/SearchView.vue') },
    { path: '/content', name: 'content-list', component: () => import('./views/ContentListView.vue') },
    { path: '/content/:id', name: 'content-detail', component: () => import('./views/ContentDetailView.vue') },
    { path: '/sources', name: 'sources', component: () => import('./views/SourcesView.vue') },
    { path: '/export/:id', name: 'export', component: () => import('./views/ExportView.vue') },
    { path: '/reference', name: 'reference', component: () => import('./views/ReferenceView.vue') },
    { path: '/calendar', name: 'calendar', component: () => import('./views/CalendarView.vue') },
    { path: '/health', name: 'health', component: () => import('./views/HealthView.vue') },
  ],
})

export default router
