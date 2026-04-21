# docs/

PlantUML source files for architecture diagrams. Each `.puml` is rendered to SVG and PNG in `docs/images/` automatically by [`.github/workflows/render-diagrams.yml`](../.github/workflows/render-diagrams.yml).

| File | Description |
|------|-------------|
| `0_architecture_overview.puml` | C4 Container diagram — consumer, orinuno containers, external systems |
| `1_kodik_api_flow.puml` | Kodik API flow — rate limiter, raw-response-first, schema drift, DB upsert |
| `2_video_decoding.puml` | ROT13 + URL-safe Base64 video link decoding (8 steps) |
| `3_export_flow.puml` | Structured export (seasons → episodes → variants) |
| `4_ttl_refresh.puml` | Background TTL refresh + failed decode retry |
| `7_hls_manifest.puml` | HLS manifest retrieval + URL absolutization |
| `6_video_download_playwright.puml` | Video download via Playwright + parallel HLS segment fetch + ffmpeg remux |

## Local rendering

Requires Docker (or a `plantuml.jar`).

```bash
# From the repo root:
docker run --rm -v "$PWD/docs:/data" -w /data plantuml/plantuml:latest \
    -tsvg -o images '*.puml'

# PNG alternative (for viewers without SVG):
docker run --rm -v "$PWD/docs:/data" -w /data plantuml/plantuml:latest \
    -tpng -o images '*.puml'
```

Without Docker:

```bash
# macOS:
brew install plantuml
plantuml -tsvg -o images docs/*.puml

# Or download plantuml.jar manually:
java -jar plantuml.jar -tsvg -o images docs/*.puml
```

Online (no install): paste `.puml` content into [plantuml.com/plantuml](https://www.plantuml.com/plantuml/uml).

## Other references

- [KODIK_API_SOURCES.md](KODIK_API_SOURCES.md) — unofficial Kodik API references (external docs + local vendor copies)
- [vendor/](vendor/) — local mirrors of external documentation for offline reading
