---
title: Code Style
description: Spotless and SpotBugs configuration, plus a short list of local-only conventions.
---

Formatting is enforced by [Spotless](https://github.com/diffplug/spotless)
using Google Java Format in AOSP profile. SpotBugs is available for static
analysis but is opt-in, not wired into CI.

## Spotless

Runs automatically on `mvn verify`. If the build fails on a formatting
check, apply the fix locally:

```sh
mvn spotless:apply
```

The AOSP profile uses 4-space indentation and a 100-column limit. Wildcard
imports are banned; IntelliJ users should set the "Class count to use
import with *" and "Names count to use static import with *" to something
unreachable (e.g., 999).

## SpotBugs (opt-in)

Not part of `verify`. Run manually:

```sh
mvn spotbugs:check
```

The exclusion filter lives in `spotbugs-exclude.xml`. Current baseline:
zero findings.

## Conventions we care about

- **No comments that narrate the code.** Comments explain non-obvious
  intent, trade-offs, or constraints — not what a line does.
- **Records for DTOs where possible.** `ContentExportDto` is a record;
  entities stay as Lombok `@Data` classes.
- **Lombok for boilerplate.** `@Data`, `@Builder`, `@RequiredArgsConstructor`
  are the three we use most.
- **Reactive where it helps.** Controllers return `Mono<ResponseEntity<T>>`
  when the call chain is non-trivial; otherwise plain `ResponseEntity<T>`
  is fine.
- **No `${...}` in MyBatis for user input.** Only whitelisted `sortBy` and
  `order` parameters use string interpolation, and the controller
  validates them before passing in.

## Related

- [Contributing](/orinuno/development/contributing/)
- [Project structure](/orinuno/development/project-structure/)
