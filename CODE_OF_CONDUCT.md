# Code of Conduct

This Code of Conduct applies to everyone who contributes to Orinuno
(as author, maintainer, contributor, bug-reporter, or user) and to
every interaction that happens in the context of this project — issues,
pull requests, discussions, social channels, and private correspondence
with the maintainer.

It has two parts:

1. **Contributor Covenant** — the standard community behaviour pledge.
2. **Responsible-use guidelines** — specific to the nature of this project.

---

## 1. Contributor Covenant

### Our Pledge

We as members, contributors, and leaders pledge to make participation in
our community a harassment-free experience for everyone, regardless of
age, body size, visible or invisible disability, ethnicity, sex
characteristics, gender identity and expression, level of experience,
education, socio-economic status, nationality, personal appearance, race,
religion, or sexual identity and orientation.

We pledge to act and interact in ways that contribute to an open,
welcoming, diverse, inclusive, and healthy community.

### Our Standards

Examples of behaviour that contributes to a positive environment:

- Demonstrating empathy and kindness toward other people.
- Being respectful of differing opinions, viewpoints, and experiences.
- Giving and gracefully accepting constructive feedback.
- Accepting responsibility and apologising to those affected by our
  mistakes, and learning from the experience.
- Focusing on what is best not just for us as individuals, but for the
  overall community.

Examples of unacceptable behaviour:

- The use of sexualised language or imagery, and sexual attention or
  advances of any kind.
- Trolling, insulting or derogatory comments, and personal or political
  attacks.
- Public or private harassment.
- Publishing others' private information, such as a physical or email
  address, without their explicit permission.
- Other conduct which could reasonably be considered inappropriate in a
  professional setting.

### Enforcement Responsibilities

The project maintainer is responsible for clarifying and enforcing these
standards and will take appropriate and fair corrective action in
response to any behaviour deemed inappropriate, threatening, offensive,
or harmful.

### Scope

This Code of Conduct applies within all project spaces, and also applies
when an individual is officially representing the project in public
spaces.

### Enforcement

Instances of abusive, harassing, or otherwise unacceptable behaviour may
be reported to the maintainer through the contact links at
<https://lyutarevich.com/>. All complaints will be reviewed and
investigated promptly and fairly.

### Attribution

This Contributor Covenant is adapted from version 2.1 of the Contributor
Covenant, available at
<https://www.contributor-covenant.org/version/2/1/code_of_conduct.html>.

---

## 2. Responsible-use guidelines

Orinuno is published for research and educational purposes (see
[DISCLAIMER.md](./DISCLAIMER.md)). The code demonstrates how to work
with Kodik's public API, how to decode obfuscated video URLs, and how
to automate a headless browser to capture HLS manifests. It is powerful
enough to be misused, and we rely on the community to use it wisely.

When using, forking, or building on top of Orinuno, **please do not**:

- **Mass-scrape** Kodik or any other provider. Do not run wide
  unattended crawls that ignore pagination courtesy, rate limits, or
  server error signals. "Mass" means anything significantly larger than
  the needs of your own legitimate research or personal catalogue.
- **Commercially re-distribute** video content obtained through this
  project, in whole or in part, regardless of the monetisation model
  (paid subscriptions, ad-supported, API-behind-paywall, re-hosted
  streams, downloadable archives, etc.).
- **Mirror or proxy** Kodik's endpoints on publicly reachable
  infrastructure so that anonymous third parties can hit them through
  your deployment. Orinuno instances are meant for personal, team, or
  integration-testing scenarios, not as a public open-to-all gateway.
- **Bypass rate limits** that Kodik applies to your token, for example
  by rotating multiple tokens or IP addresses to exceed the combined
  quota they allocated to you.
- **Strip attribution or disclaimers** when redistributing the code. The
  MIT licence allows broad reuse, but keep the copyright header,
  `DISCLAIMER.md`, and a reference to this Code of Conduct in your
  downstream documentation.
- **Use Orinuno to harm the Kodik platform or its users** — this
  includes scraping personally identifiable information, enumerating
  tokens, probing for vulnerabilities, or generating abusive load.

When using Orinuno, **please do**:

- **Tune rate limits for your situation.** The defaults in
  `application.yml` (`orinuno.parse.rate-limit-per-minute`,
  `orinuno.kodik.request-delay-ms`) are deliberately conservative. If
  you need to be even more polite, **lower** them. If you are running
  against a private local mirror, a sandbox, or a licensed feed, you
  may **raise** them — but never above the quota that your API provider
  allows.
- **Cache aggressively.** The project stores decoded links with a TTL
  so that the same content is not re-decoded on every request. Keep
  that behaviour; extend it to your own layer if you build one on top.
- **Respect geo-blocks and takedowns.** The `GeoBlockDetector` and the
  `blocked_countries` field exist to let you know when content should
  not be played in a given region. Honour those signals in any UI you
  build.
- **Honour takedown requests.** If a rights-holder contacts you about
  specific titles in your catalogue, remove them promptly.
- **Disclose use of AI-generated code** when contributing back, so that
  reviewers can scrutinise it with the same rigour as hand-written
  patches.

By contributing to Orinuno or using it in a derivative work, you agree
to follow both the Contributor Covenant above and these responsible-use
guidelines.
