# Authorized playback resource audit

**Audit date:** 2026-10-14  
**Repository:** `HumayunShariarHimu/MovieCloud`

## Result

No provider reviewed in this audit supplied a direct playback URL that was simultaneously:

1. publicly reachable and stable;
2. an actual MP4, HLS, Cloudflare Stream, or similarly supported playback resource; and
3. independently evidenced as authorized for MovieCloud to redistribute or relay.

Therefore `web/media-catalog.json` remains intentionally empty. This is a safe and correct production state, not a missing implementation. A CloudStream extractor can discover a URL at runtime, but extractor output is not a copyright license and is not automatically a browser-safe public source.

## Findings

| Provider | Finding | Production decision |
|---|---|---|
| BasPlayFTP | Source resolves catalog/player pages and references private `10.20.30.40`; no public licensed media source found. | Do not proxy or publish. |
| CinePlexFTP | Extractor discovers MP4/HLS candidates from pages; the configured host and authorization were not independently verified. | Do not proxy or publish. |
| CTGFTP | Runtime `url`/`hls_url` fields are extractor output; no licensing or stable authorized media endpoint found. | Do not proxy or publish. |
| DhakaFTP | Configured library roots include private `172.16.50.x` addresses; no authorized public media endpoint found. | Do not proxy or publish. |
| DiscoveryFTP | Extractor derives CDN candidates; no official licensing statement or independently authorized direct source found. | Do not proxy or publish. |
| HiAnime | Official pages describe a link/indexing service and provide a DMCA process, not redistribution authorization. | Do not proxy or publish. |
| KhulnaPlex | Watch-page extraction and download/iframe fallbacks are not licensing evidence. | Do not proxy or publish. |
| MojaLoss | Player data contains dynamic/tokenized candidates; the site did not provide title-specific redistribution authorization. | Do not proxy or publish. |
| MovieHaat | API/media candidates are dynamically resolved; no stable authorization or verified licensed source found. | Do not proxy or publish. |
| MovieLinkBD | Runtime signed/tokenized CDN URLs are not stable public sources and no redistribution license was found. | Do not proxy or publish. |
| OnlineMovies | Public site claims free access to premium content but provides no verifiable rights-holder authorization. | Do not proxy or publish. |
| YouTube | Official integration is the YouTube IFrame Player/API. Stable arbitrary MP4/HLS URLs are not provided for redistribution. | Use official embed/API only when a video ID is supplied; never proxy extractor URLs. |
| YouTube Kids | Same limitation as YouTube; use the official player/app, not extracted media files. | Official player only. |
| Zoryva | Runtime source probing and a separate media host are not authorization evidence. | Do not proxy or publish. |
| MovieBox | The repository module is a placeholder; the official terms describe link navigation to third-party sites, not licensed playback. | Do not proxy or publish. |

## Evidence reviewed

- [MovieCloud repository](https://github.com/HumayunShariarHimu/MovieCloud)
- [MovieCloud license](https://github.com/HumayunShariarHimu/MovieCloud/blob/main/LICENSE)
- [MovieCloud streaming architecture](./STREAMING_ARCHITECTURE.md)
- [MovieCloud provider metadata](../web/provider-data.js)
- [MovieCloud authorized catalog](../web/media-catalog.json)
- [YouTube IFrame API](https://developers.google.com/youtube/iframe_api_reference)
- [YouTube player parameters](https://developers.google.com/youtube/player_parameters)
- [YouTube Terms](https://www.youtube.com/t/terms)
- [YouTube API Services Terms](https://developers.google.com/youtube/terms/api-services-terms-of-service)
- [YouTube Developer Policies](https://developers.google.com/youtube/terms/developer-policies)
- [CloudStream provider source examples](https://github.com/HumayunShariarHimu/MovieCloud/tree/main)

The detailed evidence set also included each provider's configured source file and public provider/terms/DMCA pages where available. The important distinction is consistent across providers: a page that links to media, an extractor that finds a URL, or a repository license does not grant MovieCloud permission to redistribute the underlying films or streams.

## How to add a real source

A source may be added only when the operator has all of the following:

- a written license or clear owner authorization covering web playback and relay;
- a stable official MP4/HLS URL, Cloudflare Stream playback ID, or owner-controlled CDN endpoint;
- permission to use the source hostname in the MovieCloud gateway;
- CORS/range support appropriate for the player; and
- no embedded credentials, private-network target, DRM circumvention, or token-bypass logic.

Add it to `web/media-catalog.json`, set `live: true` for a live HLS channel, and add the exact hostname to both Vercel and the isolated Cloudflare Worker allowlists. Do not commit short-lived signed URLs or secret keys.

## Official embed exception

YouTube can be integrated through its official IFrame Player/API using a supplied video ID. That is a player integration, not a direct media proxy. It should be implemented as a separate provider-specific embed flow and must respect YouTube's API terms, branding requirements, privacy settings, and content owner restrictions. No video IDs were supplied or authorized in this audit, so none were added to the catalog.
