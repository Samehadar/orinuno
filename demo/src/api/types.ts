export interface ContentDto {
  id: number
  kodikId: string
  type: string
  title: string
  titleOrig: string | null
  otherTitle: string | null
  year: number | null
  kinopoiskId: string | null
  imdbId: string | null
  shikimoriId: string | null
  worldartLink: string | null
  screenshots: string[] | null
  camrip: boolean
  lgbt: boolean
  lastSeason: number | null
  lastEpisode: number | null
  episodesCount: number | null
  quality: string | null
  materialData: Record<string, unknown> | null
  kinopoiskRating: number | null
  imdbRating: number | null
  shikimoriRating: number | null
  genres: string | null
  blockedCountries: string | null
  createdAt: string | null
  updatedAt: string | null
}

export interface EpisodeVariantDto {
  id: number
  contentId: number
  seasonNumber: number | null
  episodeNumber: number | null
  translationId: number
  translationTitle: string
  translationType: string
  quality: string | null
  kodikLink: string | null
  mp4Link: string | null
  localFilepath: string | null
  createdAt: string | null
  updatedAt: string | null
}

export interface DownloadState {
  status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED'
  filepath: string | null
  error: string | null
  totalSegments: number | null
  downloadedSegments: number | null
  totalBytes: number | null
  expectedTotalBytes: number | null
}

export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface ContentExportDto {
  id: number
  type: string
  title: string
  titleOrig: string | null
  otherTitle: string | null
  year: number | null
  kinopoiskId: string | null
  imdbId: string | null
  shikimoriId: string | null
  screenshots: string[] | null
  camrip: boolean
  lgbt: boolean
  seasons: SeasonExportDto[]
}

export interface SeasonExportDto {
  seasonNumber: number
  episodes: EpisodeExportDto[]
}

export interface EpisodeExportDto {
  episodeNumber: number
  variants: VariantExportDto[]
}

export interface VariantExportDto {
  id: number
  translationId: number
  translationTitle: string
  translationType: string
  quality: string | null
  mp4Link: string | null
}

export interface ParseRequest {
  title?: string
  kinopoiskId?: string
  imdbId?: string
  shikimoriId?: string
  decodeLinks?: boolean
}

export interface HealthResponse {
  status: string
  decoderAvailable?: boolean
  proxyPoolSize?: number
}

export interface DecoderHealth {
  totalAttempts: number
  successCount: number
  failureCount: number
  successRate: number
  recentFailures: Record<string, string>
}

export interface ProxyHealth {
  enabled: boolean
  totalProxies: number
  activeProxies: number
  failedProxies: number
  strategy: string
}

export interface SchemaDriftEntry {
  type: string
  unknownFields: string[]
  firstSeen: string
  lastSeen: string
  hitCount: number
}

export interface SchemaDriftHealth {
  status: 'CLEAN' | 'DRIFT_DETECTED'
  totalChecks: number
  totalDriftsDetected: number
  affectedTypes: number
  drifts: SchemaDriftEntry[]
}

export interface ReferenceResponse<T> {
  time: string
  total: number
  results: T[]
}

export interface KodikTranslation {
  id: number
  title: string
  count: number
}

export interface KodikGenre {
  title: string
  count: number
}

export interface KodikCountry {
  title: string
  count: number
}

export interface KodikYear {
  year: number
  count: number
}

export interface KodikQuality {
  title: string
  count: number
}

export type ReferenceKind =
  | 'translations'
  | 'genres'
  | 'countries'
  | 'years'
  | 'qualities'

export interface CalendarImage {
  original: string | null
  preview: string | null
  x96: string | null
  x48: string | null
  x24: string | null
}

export interface CalendarAnime {
  id: string
  name: string
  russian: string | null
  image: CalendarImage | null
}

export interface CalendarEntry {
  nextEpisode: number | null
  nextEpisodeAt: string | null
  duration: number | null
  anime: CalendarAnime
  kind: string | null
  score: number | null
  status: string | null
  episodes: number | null
  episodesAired: number | null
  airedOn: string | null
  releasedOn: string | null
}

export interface EnrichedCalendarEntry {
  entry: CalendarEntry
  orinunoContentId: number | null
}

export interface CalendarResponse {
  fetchedAt: string
  etag: string | null
  total: number
  entries: EnrichedCalendarEntry[]
}

export interface CalendarFilter {
  status?: string
  kind?: string
  minScore?: number
  limit?: number
  enrich?: boolean
}

export type ProviderName = 'KODIK' | 'SIBNET' | 'ANIBOOM' | 'JUTSU'

export interface RankedSourceCandidate {
  provider: ProviderName | string
  translatorId: string | null
  translatorName: string | null
  quality: string | null
  videoUrl: string | null
  videoFormat: string | null
  decodedAt: string | null
  decodeMethod: string | null
  decodeFailedCount: number | null
  score: number
}

export interface RankedSourcesResponse {
  contentId: number
  season: number
  episode: number
  count: number
  candidates: RankedSourceCandidate[]
}

export interface ProviderDecodeRequest {
  provider: 'KODIK' | 'SIBNET' | 'ANIBOOM' | 'JUTSU'
  url: string
}

export interface ProviderDecodeResult {
  success: boolean
  qualities: Record<string, string>
  format: string | null
  errorCode: string | null
}

export interface SourceProviderInfo {
  id: string
  displayName: string
  description: string
  operations: string[]
  credentialsRequired: boolean
  credentialsConfigured: boolean
  notes: string | null
}

export interface SourcesCapabilitiesResponse {
  providers: SourceProviderInfo[]
  count: number
}

// ─────────────────────────────────────────────────────────────────────────────
// JutSu SDK surface — backed by JutsuApiController under /api/v1/sources/jutsu/.
// Field shapes mirror the Swagger snapshot at docs-site/openapi.json.
// ─────────────────────────────────────────────────────────────────────────────

export interface JutsuCatalogEntry {
  slug: string
  title: string
  originalTitle: string | null
  thumbnailUrl: string | null
  episodeCount: number | null
  movieCount: number | null
  genres: string[]
  types: string[]
  year: string | null
  detailUrl: string
}

export interface JutsuCatalogPage {
  page: number
  entries: JutsuCatalogEntry[]
  hasMore: boolean
}

export interface JutsuEpisodeListing {
  slug: string
  season: number
  episode: number
  label: string
  url: string
}

export interface JutsuSeason {
  index: number
  name: string
  episodeCount: number
  episodes: JutsuEpisodeListing[]
}

/**
 * One full-length movie ("полнометражный фильм") attached to an anime entry on jut.su. Films are
 * a sibling concept to seasons / episodes — they live under `/{slug}/film-N.html` URLs and
 * jut.su renders them in a dedicated "Полнометражные фильмы" block separate from season grids.
 */
export interface JutsuFilmListing {
  slug: string
  /** 1-based film index from the URL (`/life-no-game/film-1.html` → 1). */
  index: number
  label: string
  url: string
}

export interface JutsuAnimeInfo {
  slug: string
  title: string
  originalTitle: string | null
  synopsis: string | null
  thumbnailUrl: string | null
  /** Coarse filter-form year bucket (e.g. `"2015-2023"`); use `years` for per-season air years. */
  year: string | null
  /** Per-season air years from the labelled info block (e.g. `[2014, 2020, 2024]`). */
  years: number[]
  /** Russian age rating wire form: `"0+"` / `"6+"` / `"12+"` / `"16+"` / `"18+"` or null. */
  ageRating: string | null
  genres: string[]
  types: string[]
  seasons: JutsuSeason[]
  /**
   * Full-length movies attached to the same series. Empty for anime without movies. Films are
   * NOT counted in `totalEpisodeCount` — see `totalFilmCount`.
   */
  films: JutsuFilmListing[]
  totalEpisodeCount: number
  totalFilmCount: number
}

export interface JutsuEpisodeMeta {
  slug: string
  season: number
  episode: number
  displayTitle: string
  pageTitle: string
  canonicalUrl: string
  thumbnailUrl: string | null
  prevEpisodeUrl: string | null
  nextEpisodeUrl: string | null
  allEpisodesUrl: string | null
  premiumGated: boolean
}

export interface JutsuNoticeEntry {
  slug: string
  season: number
  episode: number
  title: string
  episodeUrl: string
  thumbnailUrl: string | null
  relativeDate: string
}

export interface JutsuNoticeFeed {
  requestedCursor: number
  nextCursor: number | null
  entries: JutsuNoticeEntry[]
  hasEntries: boolean
}

export interface JutsuDriftEvent {
  signal: string
  source: string
  detail: string
  timestamp: string
  selector: string | null
  fixtureRef: string | null
}

export interface JutsuDriftSnapshot {
  capturedAt: string
  health: 'HEALTHY' | 'DEGRADED' | 'UNAVAILABLE' | string
  lifetimeEvents: number
  windowSize: number
  eventsInWindow: number
  bySignal: Record<string, number>
  recentEvents: JutsuDriftEvent[]
}

export interface JutsuCatalogFilterParams {
  page?: number
  genres?: string[]
  types?: string[]
  years?: string[]
  sort?: string
  q?: string
}

