import type {
  CalendarFilter,
  CalendarResponse,
  ContentDto,
  ContentExportDto,
  DecoderHealth,
  DownloadState,
  EpisodeVariantDto,
  HealthResponse,
  JutsuAnimeInfo,
  JutsuCatalogFilterParams,
  JutsuCatalogPage,
  JutsuDriftSnapshot,
  JutsuNoticeEntry,
  JutsuNoticeFeed,
  JutsuPageMeta,
  KodikCountry,
  KodikGenre,
  KodikQuality,
  KodikTranslation,
  KodikYear,
  PageResponse,
  ParseRequest,
  ProviderDecodeRequest,
  ProviderDecodeResult,
  ProxyHealth,
  RankedSourcesResponse,
  ReferenceResponse,
  SchemaDriftHealth,
  SourcesCapabilitiesResponse,
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

  getEpisodeSources(
    contentId: number,
    season: number,
    episode: number,
    prefer?: string,
  ) {
    const qs = prefer ? `?prefer=${encodeURIComponent(prefer)}` : ''
    return get<RankedSourcesResponse>(
      `/api/v1/anime/${contentId}/episodes/${season}/${episode}/sources${qs}`,
    )
  },

  decodeProviderUrl(req: ProviderDecodeRequest) {
    const provider = req.provider.toLowerCase()
    return post<ProviderDecodeResult>(`/api/v1/sources/${provider}/decode`, {
      url: req.url,
    })
  },

  getSourcesCapabilities() {
    return get<SourcesCapabilitiesResponse>('/api/v1/sources')
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

  // ─────────────────────────────────────────────────────────────────────────
  // jut.su SDK surface — see JutsuApiController + ADR 0015.
  // Filter values accept either slugs (e.g. "action") or enum names ("ACTION")
  // since the controller binds both. We send slugs because they're what the
  // response shapes echo back, so a UI never has to translate.
  // ─────────────────────────────────────────────────────────────────────────

  jutsuBrowseCatalog(filter: JutsuCatalogFilterParams = {}) {
    const qs = jutsuFilterParams(filter).toString()
    return get<JutsuCatalogPage>(
      `/api/v1/sources/jutsu/catalog${qs ? `?${qs}` : ''}`,
    )
  },

  jutsuSearch(filter: JutsuCatalogFilterParams) {
    if (!filter.q) throw new Error('q is required for jut.su search')
    const qs = jutsuFilterParams(filter).toString()
    return get<JutsuCatalogPage>(`/api/v1/sources/jutsu/search?${qs}`)
  },

  jutsuGetAnimeInfo(slug: string) {
    return get<JutsuAnimeInfo>(`/api/v1/sources/jutsu/anime/${encodeURIComponent(slug)}`)
  },

  jutsuGetEpisodeMeta(url: string) {
    return get<JutsuPageMeta>(
      `/api/v1/sources/jutsu/episode?url=${encodeURIComponent(url)}`,
    )
  },

  jutsuGetNoticeFeed(cursor?: number) {
    const qs = cursor != null ? `?cursor=${cursor}` : ''
    return get<JutsuNoticeFeed>(`/api/v1/sources/jutsu/notice${qs}`)
  },

  /**
   * Stream the notice feed as NDJSON; each line is one {@link JutsuNoticeEntry}.
   * Returns an async iterator the caller can consume incrementally. Each feed
   * page costs one outbound request against the SDK's 1 RPS budget, so keep
   * `maxFeeds` modest.
   */
  async *jutsuStreamNoticeEntries(
    startCursor: number,
    maxFeeds = 5,
    signal?: AbortSignal,
  ): AsyncGenerator<JutsuNoticeEntry, void, void> {
    const url = `${BASE}/api/v1/sources/jutsu/notice/stream?startCursor=${startCursor}&maxFeeds=${maxFeeds}`
    const res = await fetch(url, { headers: headers(), signal })
    if (!res.ok || !res.body) throw new Error(`${res.status} ${res.statusText}`)
    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let nl: number
      while ((nl = buffer.indexOf('\n')) !== -1) {
        const line = buffer.slice(0, nl).trim()
        buffer = buffer.slice(nl + 1)
        if (line) yield JSON.parse(line) as JutsuNoticeEntry
      }
    }
    const tail = buffer.trim()
    if (tail) yield JSON.parse(tail) as JutsuNoticeEntry
  },

  jutsuGetDrift() {
    return get<JutsuDriftSnapshot>('/api/v1/sources/jutsu/drift')
  },
}

function jutsuFilterParams(f: JutsuCatalogFilterParams): URLSearchParams {
  const p = new URLSearchParams()
  if (f.page != null) p.set('page', String(f.page))
  if (f.q) p.set('q', f.q)
  for (const g of f.genres ?? []) p.append('genres', g)
  for (const t of f.types ?? []) p.append('types', t)
  for (const y of f.years ?? []) p.append('years', y)
  if (f.sort) p.set('sort', f.sort)
  return p
}
