# Runbook: Calendar delta watcher stuck (CAL-6)

## Symptom

- `kodik_calendar_outbox` row count stops growing for >30 minutes.
- `/api/v1/calendar/outbox?limit=1` returns the same `nextSince` repeatedly.
- Logs from `CalendarDeltaWatcher` go silent.

## Detect

```bash
mysql> SELECT MAX(detected_at) AS last_event,
              TIMESTAMPDIFF(MINUTE, MAX(detected_at), NOW()) AS minutes_silent,
              COUNT(*) AS total
       FROM kodik_calendar_outbox;
```

If `minutes_silent` > the configured `orinuno.calendar.delta-watcher.interval-minutes` × 3 and
the calendar dump itself is healthy (`HEAD https://dumps.kodikres.com/calendar.json` returns 200
with a recent `Last-Modified`), the scheduler has stalled.

```bash
curl -sI https://dumps.kodikres.com/calendar.json | grep -iE "last-modified|etag"
```

## Mitigate

1. Force a manual run by calling the scheduler bean's actuator endpoint (when wired) or restart
   the orinuno app — `@Scheduled` will pick up immediately.
2. If the watcher hangs on a malformed entry, set
   `orinuno.calendar.delta-watcher.fail-fast: false` (default) and check
   `CalendarDeltaWatcher` logs for `ignored entry shikimori_id=…`.

## Root-cause

- **Upstream payload schema drift** — Kodik occasionally introduces new fields. Run the offline
  schema regression: `mvn -pl orinuno-app test -Dtest=KodikApiFixtureSchemaTest` to confirm.
- **Database lock** — `kodik_calendar_state` upsert blocked by a long-running migration. Check
  `SHOW PROCESSLIST` for `Waiting for table metadata lock`.
- **Thread pool saturation** — `decoderMaintenanceTaskScheduler` thread is all spent on
  expensive `refreshExpiredLinks` calls. Check `Thread.getAllStackTraces()` via
  `/actuator/threaddump`.

## Prevent

- Alert on `MAX(detected_at) < NOW() - INTERVAL 30 MINUTE` from `kodik_calendar_outbox`.
- Keep `orinuno.calendar.delta-watcher.interval-minutes` ≤ 5.
- Run `KodikApiFixtureSchemaTest` in CI to catch upstream schema drift before it lands in prod.
