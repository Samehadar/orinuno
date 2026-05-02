# Runbooks (KB-1, ADR 0011)

Action-oriented playbooks for operational issues. Each runbook is a single page that answers
**what's broken**, **how do I detect it**, **how do I fix it**, and **how do I prevent it from
recurring**.

| Runbook | Symptom | Linked code |
|---------|---------|-------------|
| [decode-empty-results.md](decode-empty-results.md) | Decoder returns 0 qualities for valid Kodik links | `KodikVideoDecoderService`, `PlaywrightSniffDecoder` |
| [kodik-token-revoked.md](kodik-token-revoked.md) | All Kodik tokens marked unusable; 401/403 in metrics | `KodikTokenRegistry` |
| [calendar-watcher-stuck.md](calendar-watcher-stuck.md) | `kodik_calendar_outbox` stops growing | `CalendarDeltaWatcher`, `CalendarDeltaScheduler` |
| [provider-cdn-block.md](provider-cdn-block.md) | Sibnet/Aniboom/JutSu return 403 / geo-block from production egress | `SibnetDecoderService`, `AniboomDecoderService`, `JutsuDecoderService` |
| [enrichment-rate-limit.md](enrichment-rate-limit.md) | Jikan / Shikimori start 429-ing the enrichment scheduler | `EnrichmentService`, `MalEnrichmentClient`, `ShikimoriEnrichmentClient` |

## Runbook Format

Every page follows the same five sections:

1. **Symptom** — the user-visible failure (alert text, log pattern, HTTP code).
2. **Detect** — exact metric name, log query, or DB query that confirms the diagnosis.
3. **Mitigate** — the safe immediate action (rotate token, restart scheduler, …).
4. **Root-cause** — what to check after mitigation to find the cause.
5. **Prevent** — the change (config, code, alert) that stops it next time.

## Adding a Runbook

- New runbook = new file under `docs/runbooks/<short-slug>.md`.
- Add a row to the table above.
- Cross-link from the closest ADR/doc.
- Owner of a feature is responsible for adding a runbook for any failure mode that has surfaced
  in production at least once.
