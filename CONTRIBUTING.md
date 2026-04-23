# Contributing

Thanks for considering a contribution. This project is small and runs on
a single maintainer's time, so keeping things light and low-friction is
the priority.

## Before you start

- Read the [Code of Conduct](./CODE_OF_CONDUCT.md).
- For anything security-sensitive, see [SECURITY.md](./SECURITY.md).
  Do **not** file a public issue for vulnerabilities.
- For bugs and features, open an [issue](./issues/new/choose) first.
  A five-minute discussion beats a rejected PR.

## Dev setup

Java 21, Maven, Docker (for Testcontainers). Full instructions are
in the main [README](./README.md#quick-start). TL;DR:

```bash
git clone https://github.com/Samehadar/orinuno.git
cd orinuno
mvn verify           # compile + run tests (Testcontainers needs Docker)
```

## Pull request flow

1. Fork, branch off `master`.
2. Make the change, keep the diff focused.
3. Open a PR; the template asks for a one-line **Why**.
4. Wait for CI to go green (build, tests, CodeQL).
5. The maintainer reviews, requests changes if needed, merges.

Small PRs land faster than big ones.

## Commit messages

We use [Conventional Commits](https://www.conventionalcommits.org/). The
PR title becomes the squashed commit, so this matters for the PR title
as well. Examples:

```
feat(parse): add filter by release year
fix(decoder): handle missing translation_id gracefully
chore(deps): bump testcontainers to 1.22.0
docs: clarify how to run live integration tests
```

Release versioning and the changelog are generated from these prefixes,
so stray commit messages won't break anything — they just won't appear
in the release notes.

## A few don'ts

- Don't commit secrets (tokens, `.env`, credentials). The repo has push
  protection enabled, but human review is the last line.
- Don't rename core modules, entities, or public API fields without
  flagging it in the issue first — downstream consumers depend on them.
- Don't pile multiple unrelated changes into one PR; split them.
