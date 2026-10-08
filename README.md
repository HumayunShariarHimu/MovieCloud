# MovieCloud

> A premium CloudStream extension hub and authorized media workspace.

**Created and maintained by [Humayun Shariar Himu](https://github.com/MyselfHumayunShariarHimu).**

[![CloudStream](https://img.shields.io/badge/CloudStream-extension-8b5cf6?style=flat-square)](https://github.com/recloudstream)
[![Vercel](https://img.shields.io/badge/Web-Vercel-000?style=flat-square&logo=vercel)](https://moviecloudproject.vercel.app/)
[![Tests](https://img.shields.io/badge/tests-7%20passing-22c55e?style=flat-square)](api/stream.test.js)

## Overview

MovieCloud brings the repository's CloudStream providers, generated artifacts and web workspace together in a focused dark-neon interface. The web app is intentionally lightweight, responsive and iframe-free.

- 15 CloudStream provider modules
- Generated CS3 artifact discovery
- Provider search and filtering
- Provider source details
- Native MP4/HLS playback for authorized direct media
- HLS manifest rewriting through a secure Vercel gateway
- Favorites, recent providers and playback resume in browser storage
- PWA install support and offline UI shell
- GitHub Actions build and GitHub Pages deployment
- Vercel catalog and health APIs

## Live web app

- Production: <https://moviecloudproject.vercel.app/>
- Web route: <https://moviecloudproject.vercel.app/web/>
- Health: <https://moviecloudproject.vercel.app/api/health>
- Catalog: <https://moviecloudproject.vercel.app/api/catalog>

## Repository structure

```text
MovieCloud/
├── api/
│   ├── catalog.js          # Vercel catalog endpoint
│   ├── health.js           # Deployment diagnostics
│   └── stream.js           # Allowlisted media gateway
├── web/
│   ├── index.html          # Premium responsive web UI
│   ├── style.css           # Dark-neon design system
│   ├── app.js              # Provider directory and media workspace
│   ├── ui-enhancements.js  # Settings, menu and PWA interactions
│   ├── media-catalog.json  # Authorized direct media catalog
│   ├── manifest.json       # PWA manifest
│   └── sw.js               # UI-shell service worker
├── .github/workflows/
│   ├── build.yml           # CloudStream artifact build
│   └── pages.yml           # Static Pages deployment
├── vercel.json
└── README.md
```

The provider modules remain independent Kotlin/Gradle CloudStream extensions. The browser does not execute `.cs3` plugins or reproduce third-party extractors.

## Artifact installation

The current build publishes valid `.cs3` packages. A separate `.jar` is shown only when a real JAR exists in `builds/artifact-index.json`; files are never renamed or presented as a fake JAR.

Install through the repository manifest:

```text
https://raw.githubusercontent.com/HumayunShariarHimu/MovieCloud/builds/repo.json
```

## Authorized media playback

The gateway is deliberately allowlist-based and fails closed. Configure Vercel with domains you own or are explicitly authorized to relay:

```env
APP_ORIGIN=https://moviecloudproject.vercel.app
ALLOWED_STREAM_HOSTS=media.example.com,cdn.example.com
```

The player accepts direct `.mp4` or `.m3u8` URLs. It supports seeking, browser-native controls, HLS.js fallback, resume position and HLS child playlist rewriting.

The project does not scrape provider pages, bypass DRM, accept credential-bearing URLs, relay private-network targets or operate as an unrestricted proxy.

## Development

Requirements: Node.js 22+, JDK 17 and a compatible Android/CloudStream Gradle environment.

```bash
git clone https://github.com/HumayunShariarHimu/MovieCloud.git
cd MovieCloud
npm run check
```

### Local web preview

```bash
python3 -m http.server 4173
# open http://localhost:4173/web/
```

### API checks

The Vercel handlers can be syntax-checked locally with `npm run check`. Production diagnostics are available at `/api/health` and the read-only catalog is available at `/api/catalog`.

## CI/CD

A push to `main` runs JavaScript checks, gateway regression tests, CloudStream artifact generation and the Pages workflow. The Pages workflow assembles `web/` as a static artifact. Vercel deploys the connected GitHub project automatically.

## Security model

- Explicit HTTP(S) validation
- Credential-bearing URL rejection
- Empty allowlist fails closed
- Private IPv4/IPv6 and localhost blocking
- Redirect target revalidation
- Request timeout and HLS manifest size limit
- Security headers and no-store API responses
- No secrets or signed media URLs in the repository

## Legal boundary

MovieCloud is designed for lawful use. Only media that is owned by the project or explicitly authorized for distribution should be placed in `web/media-catalog.json` or relayed through the gateway. Third-party copyright, licenses, trademarks and service terms remain the responsibility of their respective owners.

## Credits

**Humayun Shariar Himu** is the creator and maintainer of MovieCloud.

CloudStream and third-party provider projects remain the property of their respective authors. See `LICENSE` and individual module notices for applicable terms.
