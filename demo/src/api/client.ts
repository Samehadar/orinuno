import type {
  CalendarFilter,
  CalendarResponse,
  ContentDto,
  ContentExportDto,
  DecoderHealth,
  DownloadState,
  EpisodeVariantDto,
  HealthResponse,
  KodikCountry,
  KodikGenre,
  KodikQuality,
  KodikTranslation,
  KodikYear,
  PageResponse,
  ParseRequest,
  ProxyHealth,
  ReferenceResponse,
  SchemaDriftHealth,
} from './types'

const BASE = import.meta.env.VITE_API_URL ?? ''
const API_KEY = import.meta.env.VITE_API_KEY ?? ''

function headers(): Record<string, string> {
  const h: Record<string, string> = { 'Content-Type': 'application/json' }
  if (API_KEY) h['X-API-KEY'] = API_KEY
  return h
}

async function get<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`, { headers: headers() })
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
  return res.json()
}

async function post<T>(path: string, body?: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: headers(),
    body: body ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
  const text = await res.text()
  return text ? JSON.parse(text) : ({} as T)
}

export const api = {
  searchContent(req: ParseRequest) {
    return post<ContentDto[]>('/api/v1/parse/search', req)
  },

  getContentList(page = 0, size = 20, sortBy = 'id', order = 'ASC') {
    return get<PageResponse<ContentDto>>(
      `/api/v1/content?page=${page}&size=${size}&sortBy=${sortBy}&order=${order}`,
    )
  },

  getContent(id: number) {
    return get<ContentDto>(`/api/v1/content/${id}`)
  },

  getVariants(contentId: number) {
    return get<EpisodeVariantDto[]>(`/api/v1/content/${contentId}/variants`)
  },

  getByKinopoisk(kinopoiskId: string) {
    return get<ContentDto>(`/api/v1/content/by-kinopoisk/${kinopoiskId}`)
  },

  decodeContent(contentId: number, force = false) {
    return post<void>(`/api/v1/parse/decode/${contentId}?force=${force}`)
  },

  decodeVariant(variantId: number, force = false) {
    return post<{ variantId: number; decoded: boolean }>(
      `/api/v1/parse/decode/variant/${variantId}?force=${force}`,
    )
  },

  getExport(contentId: number) {
    return get<ContentExportDto>(`/api/v1/export/${contentId}`)
  },

  getReadyExports(page = 0, size = 20) {
    return get<PageResponse<ContentExportDto>>(
      `/api/v1/export/ready?page=${page}&size=${size}`,
    )
  },

  getHealth() {
    return get<HealthResponse>('/api/v1/health')
  },

  getDecoderHealth() {
    return get<DecoderHealth>('/api/v1/health/decoder')
  },

  getProxyHealth() {
    return get<ProxyHealth>('/api/v1/health/proxy')
  },

  getSchemaDriftHealth() {
    return get<SchemaDriftHealth>('/api/v1/health/schema-drift')
  },

  downloadVariant(variantId: number) {
    return post<DownloadState>(`/api/v1/download/${variantId}`)
  },

  getDownloadStatus(variantId: number) {
    return get<DownloadState>(`/api/v1/download/${variantId}/status`)
  },

  downloadContent(contentId: number) {
    return post<{ contentId: number; downloadedCount: number }>(
      `/api/v1/download/content/${contentId}`,
    )
  },

  getHlsUrl(variantId: number) {
    return get<{ url: string }>(`/api/v1/hls/${variantId}/url`)
  },

  async getHlsManifest(variantId: number): Promise<string> {
    const res = await fetch(`${BASE}/api/v1/hls/${variantId}/manifest`, { headers: headers() })
    if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
    return res.text()
  },

  getTranslations(fresh = false) {
    return get<ReferenceResponse<KodikTranslation>>(
      `/api/v1/reference/translations${fresh ? '?fresh=true' : ''}`,
    )
  },

  getGenres(fresh = false) {
    return get<ReferenceResponse<KodikGenre>>(
      `/api/v1/reference/genres${fresh ? '?fresh=true' : ''}`,
    )
  },

  getCountries(fresh = false) {
    return get<ReferenceResponse<KodikCountry>>(
      `/api/v1/reference/countries${fresh ? '?fresh=true' : ''}`,
    )
  },

  getYears(fresh = false) {
    return get<ReferenceResponse<KodikYear>>(
      `/api/v1/reference/years${fresh ? '?fresh=true' : ''}`,
    )
  },

  getQualities(fresh = false) {
    return get<ReferenceResponse<KodikQuality>>(
      `/api/v1/reference/qualities${fresh ? '?fresh=true' : ''}`,
    )
  },

  getCalendar(filter: CalendarFilter = {}) {
    const params = new URLSearchParams()
    if (filter.status) params.set('status', filter.status)
    if (filter.kind) params.set('kind', filter.kind)
    if (filter.minScore != null) params.set('minScore', String(filter.minScore))
    if (filter.limit != null) params.set('limit', String(filter.limit))
    if (filter.enrich) params.set('enrich', 'true')
    const qs = params.toString()
    return get<CalendarResponse>(`/api/v1/calendar${qs ? `?${qs}` : ''}`)
  },
}
