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
