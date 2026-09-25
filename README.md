# 🎬 MovieCloud

> **A multi-source CloudStream extension repository for discovering movies, series, anime and online video content.**

![MovieCloud](https://img.shields.io/badge/MovieCloud-CloudStream-7c3aed?style=for-the-badge)
![Platform](https://img.shields.io/badge/Platform-CloudStream-111827?style=for-the-badge)
![Language](https://img.shields.io/badge/Language-Kotlin-7F52FF?style=for-the-badge)
![Repository](https://img.shields.io/badge/Repository-GitHub-181717?style=for-the-badge)

## 👤 Creator & Credit

**Created and maintained by Humayun Shariar Himu (হুমায়ূন সাহরিয়ার হিমু).**

- **GitHub:** [@MyselfHumayunShariarHimu](https://github.com/MyselfHumayunShariarHimu)
- **Repository:** [MovieCloud](https://github.com/MyselfHumayunShariarHimu/MovieCloud)

> © Humayun Shariar Himu. See the repository license and individual source files for applicable third-party notices.

---

## 🌟 About

**MovieCloud** is a collection of CloudStream extensions organized into a single repository. The project brings multiple entertainment providers and content sources together so users can manage compatible extensions from one repository.

The repository is designed around a simple idea:

**One repository → multiple extensions → one convenient CloudStream experience.**

The included extensions cover different categories and sources, including movies, TV/series, anime, online video and regional entertainment sources.

---

## ✨ Highlights

- 🎬 Multiple independent CloudStream extensions
- 📺 Movies and TV/series discovery
- 🎞️ Web-series and entertainment sources
- 🇧🇩 Bangladesh-focused sources
- 🇮🇳 South Asian content sources
- 🌏 International entertainment sources
- 🇯🇵 Anime and related content
- ▶️ Online video platforms
- 🧩 Extensions organized by provider
- 🔎 Provider-specific search and loading logic
- ⚡ Designed for CloudStream's extension ecosystem
- 🛠️ Gradle/Kotlin-based project structure
- ➕ Expandable architecture for future providers

---

## 🧩 Included Extensions

The current project package contains the following extension modules:

| Extension | Module |
|---|---|
| 🎬 BasPlay FTP | `BasPlayFTP` |
| 🎬 CTG FTP | `CTGFTP` |
| 🎥 CinePlex FTP | `CinePlexFTP` |
| 🎬 Dhaka FTP | `DhakaFTP` |
| 🔎 Discovery FTP | `DiscoveryFTP` |
| 🍿 HiAnime | `HiAnime` |
| 🎬 KhulnaPlex | `KhulnaPlex` |
| 🎞️ MojaLoss | `MojaLoss` |
| 🎥 MovieBox | `MovieBox` |
| 🎬 MovieHaat | `MovieHaat` |
| 🎞️ MovieLinkBD | `MovieLinkBD` |
| 🎬 Online Movies | `OnlineMovies` |
| ▶️ YouTube | `YouTube` |
| 👨‍👩‍👧 YouTube Kids | `YouTubeKids` |
| 🎞️ Zoryva | `Zoryva` |

The repository also contains shared Gradle configuration, repository metadata and GitHub Actions configuration.

---

## 🏗️ Repository Structure

```text
MovieCloud/
├── .github/
│   └── workflows/
├── BasPlayFTP/
├── CTGFTP/
├── CinePlexFTP/
├── DhakaFTP/
├── DiscoveryFTP/
├── HiAnime/
├── KhulnaPlex/
├── MojaLoss/
├── MovieBox/
├── MovieHaat/
├── MovieLinkBD/
├── OnlineMovies/
├── YouTube/
├── YouTubeKids/
├── Zoryva/
├── build.gradle.kts
├── gradle.properties
├── repo.json
├── settings.gradle.kts
├── LICENSE
└── README.md
```

Each provider is kept in its own module so that source-specific logic can evolve independently.

---

## 🌐 MovieCloud Web App & Media Player

The repository also contains a static web interface under `web/` plus a Vercel-compatible server function under `api/stream.js`.

The web app provides:

- 📦 Live artifact status for CS3/JAR packages
- 🔎 Provider search and filtering
- 📱 Responsive mobile UI
- ▶️ Native HTML5 media playback for **authorized direct MP4/HLS sources**
- ⏩ Range-aware media requests for seeking/resume
- 🛡️ Server-side host allowlisting
- 🚫 No provider webpage iframe is required for the media-player path
- 🔗 External provider fallback when a browser-compatible direct media URL is not available

### Vercel media gateway

For a Vercel deployment, configure the environment variable:

`ALLOWED_STREAM_HOSTS=media.example.com,cdn.example.com`

Only hosts explicitly listed in this variable can be relayed by `/api/stream`. This is intentionally an allowlist rather than an open proxy.

The player expects a **direct media URL** such as an authorized MP4 or HLS manifest. A provider webpage URL is not a media URL.

> The gateway is intended for media that you own or are authorized to relay. It does not scrape provider pages, bypass access controls, defeat DRM, or turn third-party websites into an unrestricted proxy.

## 🚀 Using MovieCloud with CloudStream

1. Open your CloudStream-compatible application.
2. Open the repository/extension settings.
3. Add the MovieCloud repository using its GitHub repository address.
4. Refresh or load the repository.
5. Select the extensions you want to install.
6. Enable the required extension and use it from CloudStream.

> The exact repository installation UI can vary between CloudStream builds and forks.

---

## 🛠️ Development

MovieCloud is primarily structured as a Kotlin/Gradle CloudStream extension repository.

### Requirements

- Git
- JDK compatible with the project's CloudStream/Gradle requirements
- Gradle wrapper or a compatible Gradle environment
- Android/CloudStream extension development environment when required by the target build

### Build

From the repository root, use the project's Gradle configuration to build the available modules.

Typical workflow:

```bash
git clone https://github.com/MyselfHumayunShariarHimu/MovieCloud.git
cd MovieCloud

./gradlew build
```

On Windows:

```powershell
gradlew.bat build
```

If a specific extension is being developed, build/test that module according to its Gradle configuration.

---

## 🔄 Continuous Integration

The repository includes GitHub Actions configuration under:

```text
.github/workflows/
```

CI can be used to validate builds and help catch extension or dependency issues before releases.

---

## 🧱 Architecture

MovieCloud follows a modular provider-based approach.

### Provider modules

Each provider generally contains:

- CloudStream provider implementation
- Provider metadata
- Search logic
- Catalog/category logic where supported
- Detail-page parsing
- Episode/season handling where applicable
- Source/link extraction
- Provider-specific helper utilities
- Gradle module configuration

This modular design makes it easier to add, maintain, update or remove individual providers without redesigning the whole repository.

---

## ➕ Adding a New Extension

A new provider can be added as an independent module.

Recommended process:

1. Create a new module directory.
2. Add its Gradle configuration.
3. Implement the CloudStream provider.
4. Add provider metadata.
5. Add the module to the root Gradle settings.
6. Update repository metadata where required.
7. Build the project.
8. Test the extension in CloudStream.
9. Update this README with the new provider.
10. Commit the change with a clear message.

---

## 🔐 Security & Configuration

Do **not** commit:

- API keys
- Private tokens
- Passwords
- Personal credentials
- Private cookies/session data
- Production secrets

Use environment variables or an appropriate secret-management mechanism when a development workflow requires sensitive configuration.

---

## ⚠️ Content & Legal Disclaimer

MovieCloud is an **extension repository**. It is not intended to host or distribute copyrighted media files itself.

The extensions may interact with third-party websites or services. Availability, metadata, streams, links and provider behavior can change without notice.

Users are responsible for:

- Following applicable local laws and regulations.
- Respecting copyright and intellectual-property rights.
- Following the terms of the third-party services they access.
- Using the extensions only for lawful purposes.

**MovieCloud does not claim ownership of third-party content merely because an extension can access or parse information from a third-party source.**

For licensing questions concerning a particular provider, consult that provider's own terms, policies and applicable rights holders.

---

## 📌 Project Status

**Status: Active / Expandable**

MovieCloud is structured so that additional extensions and improvements can be introduced over time.

Potential future work includes:

- ➕ Additional providers
- 🔎 Search and parsing improvements
- ⚡ Performance improvements
- 🧪 More automated build validation
- 🧹 Provider maintenance and cleanup
- 📝 Better documentation
- 🛡️ Security and reliability improvements
- 🆕 New CloudStream-compatible extensions

---

## 🤝 Contributions

Contributions, fixes and improvements are welcome where they comply with the project's license and applicable third-party rights.

When contributing:

1. Keep provider-specific changes isolated where practical.
2. Avoid committing secrets.
3. Keep metadata accurate.
4. Test builds before submitting changes.
5. Document meaningful architectural or provider changes.
6. Respect the licenses and terms of dependencies and third-party services.

---

## ❤️ Credits

### Project Creator

**Humayun Shariar Himu**  
**হুমায়ূন সাহরিয়ার হিমু**

This repository is maintained under the GitHub account:

**[@MyselfHumayunShariarHimu](https://github.com/MyselfHumayunShariarHimu)**

### CloudStream

MovieCloud is developed for the CloudStream extension ecosystem. CloudStream and its associated projects remain the property of their respective authors and contributors.

### Third-Party Providers

Individual providers and third-party services remain responsible for their own websites, content, trademarks, copyrights and policies.

---

## 📜 License

Please review the repository's [LICENSE](LICENSE) file for the applicable license terms.

Individual extension modules may also include code or dependencies subject to their own licenses or notices. Those terms remain applicable where required.

---

## ⭐ Support

If you find MovieCloud useful, you can support the project by:

- ⭐ Starring the repository
- 🐛 Reporting reproducible issues
- 💡 Suggesting improvements
- 🧩 Contributing provider fixes
- 📖 Improving documentation

---

## 🎬 MovieCloud

**Multiple Sources • Multiple Extensions • One CloudStream Repository**

### Created & maintained by **Humayun Shariar Himu**
### হুমায়ূন সাহরিয়ার হিমু

