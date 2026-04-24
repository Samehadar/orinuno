---
title: Responsible Use
description: What we ask users and contributors to do — and not to do — when running or extending Orinuno.
---

Orinuno ships with **conservative defaults** — the Kodik client sleeps
`request-delay-ms: 500` between calls, the token bucket allows only
`rate-limit-per-minute: 30`, and CDN link TTL refresh is batched. These
knobs exist so you can tune them to your situation, but please do so
responsibly.

## When using, forking, or building on top of Orinuno, please do not:

- **Mass-scrape** Kodik or any other provider. Do not run wide, unattended
  crawls that ignore pagination courtesy, rate limits, or server-error
  signals. "Mass" means anything significantly larger than the needs of
  your own legitimate research or personal catalogue.
- **Commercially re-distribute** video content obtained through this
  project, in whole or in part — regardless of the monetisation model
  (paid subscriptions, ad-supported, API-behind-paywall, re-hosted streams,
  downloadable archives, etc.).
- **Mirror or proxy** Kodik's endpoints on publicly reachable
  infrastructure so that anonymous third parties can hit them through your
  deployment. Orinuno instances are meant for personal, team, or
  integration-testing scenarios, not as a public open-to-all gateway.
- **Bypass rate limits** that Kodik applies to your token — for example,
  by rotating multiple tokens or IP addresses to exceed the combined quota
  allocated to you.
- **Strip attribution or disclaimers** when redistributing the code. The
  MIT licence allows broad reuse, but keep the copyright header, the
  [Disclaimer](/orinuno/legal/disclaimer/), and a reference to this
  Responsible Use page in your downstream documentation.
- **Use Orinuno to harm the Kodik platform or its users** — this includes
  scraping personally identifiable information, enumerating tokens,
  probing for vulnerabilities, or generating abusive load.

## When using Orinuno, please do:

- **Tune rate limits for your situation.** The defaults in `application.yml` (`orinuno.parse.rate-limit-per-minute`, `orinuno.kodik.request-delay-ms`) are deliberately conservative. If you need to be even more polite, **lower** them. If you are running against a private local mirror, a sandbox, or a licensed feed, you may **raise** them — but never above the quota that your API provider allows.
- **Cache aggressively.** The project stores decoded links with a TTL so
  that the same content is not re-decoded on every request. Keep that
  behaviour; extend it to your own layer if you build one on top.
- **Respect geo-blocks and takedowns.** `GeoBlockDetector` and the
  `blocked_countries` field exist to let you know when content should not
  be played in a given region. Honour those signals in any UI you build.
- **Honour takedown requests.** If a rights-holder contacts you about
  specific titles in your catalogue, remove them promptly. See
  [Takedown requests](/orinuno/legal/takedowns/).
- **Disclose use of AI-generated code** when contributing back, so that
  reviewers can scrutinise it with the same rigour as hand-written
  patches.

By contributing to Orinuno or using it in a derivative work, you agree to
follow these responsible-use guidelines and the
[Code of Conduct](/orinuno/legal/code-of-conduct/).

## Related

- [Disclaimer](/orinuno/legal/disclaimer/)
- [Code of Conduct](/orinuno/legal/code-of-conduct/)
