# MovieCloud Production Development Roadmap

This document defines an implementation and verification plan for evolving MovieCloud while preserving its existing CloudStream extension repository.

## Current architecture

- Kotlin/Gradle CloudStream provider modules
- Static HTML/CSS/JavaScript provider directory in `web/`
- Vercel serverless media gateway in `api/stream.js`
- GitHub Actions build workflow
- Vercel deployment configuration

MovieCloud is not currently a complete account-based subscription streaming backend. Features below are proposals until implemented and tested.

## Product boundaries

Only publish, index, relay, or play media that MovieCloud owns or is explicitly authorized to distribute. Provider-page scraping, access-control circumvention, DRM bypass, and unrestricted proxying are out of scope. Keep external-provider links clearly distinguished from direct authorized media playback.

## Workstreams

### 1. Baseline and reliability
- Capture the default branch, module inventory, workflow configuration, Vercel routes, and current artifact layout.
- Build every Gradle module in CI; report failures per module rather than hiding them.
- Add static checks for JavaScript syntax, JSON validity, Kotlin formatting/lint where compatible, and broken internal links.
- Add regression tests for artifact-index parsing, search/filter behavior, media URL validation, byte-range forwarding, redirects, and HLS playlist rewriting.
- Record device/browser test matrix and known provider limitations.

### 2. Extension platform
- Standardize provider metadata, package naming, versioning, capabilities, and source ownership notices.
- Share safe, well-tested parsing/network helpers where provider implementations genuinely overlap.
- Add provider-level fixtures and tests for search, catalog, details, seasons/episodes, and source extraction without depending on live third-party sites.
- Make provider health observable in CI without treating a temporary upstream outage as a successful extraction.
- Generate repository manifests and artifact indexes from successful build outputs; never publish placeholder or stale artifacts as current.

### 3. Web experience
- Improve semantic HTML, keyboard navigation, focus visibility, reduced-motion support, loading/empty/error states, and mobile layouts.
- Add provider detail views, category filters, sort options, favorites, recently viewed providers, and persistent user preferences.
- Add install instructions and artifact status that derive from the generated manifest.
- Add PWA support only with a tested update strategy and no caching of private or unauthorized media.
- Keep external provider navigation explicit; do not embed third-party pages as a workaround for playback restrictions.

### 4. Authorized media playback
- Keep the gateway allowlist mandatory and fail closed when it is empty.
- Validate URL protocol, hostname, redirects, request method, response size/time limits, and forwarded headers.
- Add tests for malformed URLs, localhost/private-network targets, redirect chains, range requests, HLS child playlists, and upstream errors.
- Support MP4/HLS only when the source is authorized and browser-compatible. DRM-protected content is not handled.
- Add player controls for seeking, volume, playback speed, captions when available, and resume position.
- Avoid logging signed media URLs, credentials, or sensitive query parameters.

### 5. Optional first-party service
This is a separate product expansion, not an assumption about the current repository:
- API service with schema validation, rate limiting, structured errors, and versioned endpoints.
- Database models for users, profiles, licensed titles, watch progress, favorites, and admin audit events.
- Authentication with secure sessions, password reset, account deletion, and role-based authorization.
- Admin content management for authorized metadata and media references.
- Object storage/CDN integration for owned or licensed media, signed URLs, quotas, and lifecycle policies.
- Privacy controls, retention policy, backups, migrations, and operational monitoring.
- Payments/subscriptions only if explicitly required, with provider-side verification and no client-trusted entitlements.

### 6. Security and operations
- Keep secrets in GitHub/Vercel environment settings; commit only documented variable names in an example file.
- Apply least privilege, dependency updates, secret scanning, and code scanning.
- Add security headers, restrictive CORS, request validation, and abuse controls appropriate to each route.
- Add privacy-respecting telemetry only with a documented purpose and consent where required.
- Provide incident response, rollback, backup restore, and release procedures.

### 7. Release and acceptance
- CI must build all supported modules and run automated tests.
- Validate the deployed web app on mobile and desktop, including keyboard-only navigation.
- Test authorized playback against controlled MP4/HLS fixtures, including seeking and failure paths.
- Verify artifact manifests match files actually published.
- Publish a changelog, known issues, configuration guide, and reproducible release artifacts.
- Mark a feature complete only after code, tests, build, and relevant device/browser verification are recorded.

## Suggested delivery sequence

1. Baseline audit and reproducible CI.
2. Fix existing build, manifest, frontend, and gateway defects.
3. Improve extension consistency and provider tests.
4. Complete web directory and authorized media-player UX.
5. Add optional first-party backend only after its product requirements and licensed content model are agreed.
6. Security review, device/browser validation, documentation, and tagged release.

## Configuration inventory

Document required variables such as `ALLOWED_STREAM_HOSTS` with safe examples. Never commit actual secrets or signed media URLs. Production deployments should use explicit allowlisted hostnames and should not use wildcard entries unless the operational need and subdomain trust boundary are reviewed.

## Completion status

This is a roadmap, not a claim that the listed features have already been implemented. Track each workstream with commits, CI results, and test evidence.
