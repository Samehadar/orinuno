# `data/` — runtime state (gitignored)

This directory is mounted as a volume in Docker compose and holds runtime state that must
survive restarts but is **never** committed to git (see root `.gitignore`).

Only a handful of files in this directory are exceptions and can be committed:

- `data/README.md` — this file.
- `data/kodik_tokens.example.json` — schema reference for operators.

## Contents

### `kodik_tokens.json` (managed)

Kodik API token registry — see [TOKENS.md](TOKENS.md) and
`docs-site/src/content/docs/operations/kodik-tokens.md` for the full contract.

Schema at a glance:

```jsonc
{
  "stable":   [ { "value": "...", "functions_availability": { ... }, "last_checked": "...", "note": "..." } ],
  "unstable": [ ... ],
  "legacy":   [ ... ],
  "dead":     [ ... ]
}
```

- Tiers mirror AnimeParsers' [`kdk_tokns/tokens.json`][ap-tokens]:
  `stable` (works, rotates rarely) → `unstable` (works, rotates often) → `legacy` (partial
  scope) → `dead` (retired; kept for audit).
- File is (re)written by the service on every mutation using the `.tmp + ATOMIC_MOVE`
  pattern, and POSIX-chmoded to `600` when the filesystem allows it.
- Operators can edit it by hand — just stop the app, edit, start. Validation runs on boot.
- Do **not** commit this file; it contains secrets.

[ap-tokens]: https://github.com/YaNesyTortiK/AnimeParsers/blob/main/src/anime_parsers_ru/kdk_tokns/tokens.json

### Downloaded videos

Playwright / HLS pipeline writes downloaded `.mp4` files here. Also gitignored.
