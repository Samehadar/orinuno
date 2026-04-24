---
title: Contributing
description: How to propose changes, run the build, and follow commit conventions.
---

Contributions are welcome — bug reports, feature ideas, docs fixes, and
pull requests alike.

## Before you start

- Read the [Code of Conduct](/orinuno/legal/code-of-conduct/).
- For anything security-sensitive, see the [Security policy](/orinuno/legal/security-policy/). Do **not** file a public issue for vulnerabilities.
- For bugs and features, open an issue first. A five-minute discussion
  beats a rejected PR.

## Dev setup

Java 21, Maven, Docker (for Testcontainers).

```sh
git clone https://github.com/Samehadar/orinuno.git
cd orinuno
mvn verify   # compile + run tests (Testcontainers needs Docker)
```

Full instructions live in [Quick Start](/orinuno/getting-started/quick-start/) and
[Prerequisites](/orinuno/getting-started/prerequisites/).

## Pull request flow

1. Fork, branch off `master`.
2. Make the change, keep the diff focused.
3. Open a PR; the template asks for a one-line **Why**.
4. Wait for CI to go green (build, tests, CodeQL).
5. The maintainer reviews, requests changes if needed, and merges.

Small PRs land faster than big ones.

## Commit messages

We use [Conventional Commits](https://www.conventionalcommits.org/). The
PR title becomes the squashed commit, so this matters for the PR title as
well:

```
feat(parse): add filter by release year
fix(decoder): handle missing translation_id gracefully
chore(deps): bump testcontainers to 1.22.0
docs: clarify how to run live integration tests
```

## A few don'ts

- Don't commit secrets (tokens, `.env`, credentials). The repo has push
  protection enabled, but human review is the last line.
- Don't rename core modules, entities, or public API fields without
  flagging it in the issue first — downstream consumers depend on them.
- Don't pile multiple unrelated changes into one PR; split them.

## Related

- [Project structure](/orinuno/development/project-structure/)
- [Testing](/orinuno/development/testing/)
- [Code style](/orinuno/development/code-style/)
