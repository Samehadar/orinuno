# Orinuno Demo

Anime-style web UI for the Orinuno API.

Built with **Vue 3 + TypeScript + Vite + Tailwind CSS 4**.

## Features

- **Search** — find anime, movies, and series via Kodik API
- **Library** — browse saved content with sorting and pagination
- **Content detail** — view metadata, screenshots, episode variants, decode links, and play MP4
- **Export** — tree view (season/episode/variant) with JSON preview and copy
- **Health dashboard** — service, decoder, and proxy status with auto-refresh

## Quick start

```bash
cp .env.example .env
npm install
npm run dev
```

The dev server starts at `http://localhost:3000` and proxies `/api/*` requests to `http://localhost:8080` (Orinuno backend).

## Environment variables

| Variable | Description | Default |
|---|---|---|
| `VITE_API_URL` | API base URL (empty = use Vite proxy) | `""` |
| `VITE_API_KEY` | API key for protected endpoints | `""` |

## Docker

From the project root:

```bash
docker compose up -d
```

The demo is accessible at `http://localhost:3000`. Nginx proxies API requests to the backend container.

## Project structure

```
src/
  api/          — typed API client and DTOs
  views/        — Vue pages (Search, ContentList, ContentDetail, Export, Health)
  components/   — reusable components
  router.ts     — Vue Router config
  style.css     — Tailwind + anime theme (neon/glass-morphism)
  App.vue       — layout with navigation
```
