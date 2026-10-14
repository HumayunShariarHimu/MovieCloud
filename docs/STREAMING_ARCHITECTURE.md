# MovieCloud streaming architecture

## Scope

MovieCloud supports public browser playback only for media that the project owns or is explicitly authorized to distribute. A CloudStream `.cs3` plugin is an Android/CloudStream extension; it is not a browser-side extractor. The web application therefore does not scrape provider pages, bypass DRM, or turn arbitrary third-party URLs into a public proxy.

## Delivery model

1. An authorized owner publishes an MP4 or HLS URL in `web/media-catalog.json`.
2. The source hostname is added to `ALLOWED_STREAM_HOSTS` in Vercel.
3. The browser requests `/api/stream?url=...`.
4. The gateway validates HTTP(S), rejects credentials and private-network targets, checks the allowlist, revalidates redirect targets, forwards safe range headers, and rewrites allowlisted HLS child resources back through the gateway.
5. The browser uses HLS.js where Media Source Extensions are available and native HLS where the browser provides it. MP4 uses the native `<video>` element.

For production movie delivery, use a video CDN rather than routing every segment through the Vercel function. Cloudflare Stream is the default integration target because it handles ingest, encoding and delivery and can return HLS manifests for a playback ID. Public playback is appropriate only when the license allows it; otherwise enable signed playback tokens. Cloudflare documents both public playback and short-lived or self-signed token options. [4] Cloudflare R2 with a custom domain is suitable for public objects when you own the encoded files and need cache/WAF controls. [5] AWS CloudFront is an alternative for S3-backed HLS with signed URLs and trusted key groups. [6]

Cloudflare Stream catalog entries can avoid committing a raw origin URL:

```json
{
  "label": "Adaptive HD",
  "type": "HLS",
  "delivery": "cloudflare-stream",
  "playbackId": "VIDEO_UID"
}
```

Set `CLOUDFLARE_STREAM_CUSTOMER_CODE` in Vercel. The catalog API then resolves the playback ID to the official Stream manifest URL. For public licensed videos this works without viewer login. For protected titles, the next step is a server-side entitlement check and short-lived signed token endpoint; the private signing key must never be placed in the browser or repository.

HLS is a playlist plus media segments delivered over ordinary HTTP. A player reads the index, requests the listed media files, and continues refreshing a live playlist when there is no `EXT-X-ENDLIST` marker. [1] All HLS resources also need CORS permission for browser playback when they are fetched by a JavaScript player. [2]

## Implemented player features

- MP4 and HLS playback through the authorized gateway
- HLS.js with native HLS fallback
- HLS child playlist, segment and key URL rewriting for allowlisted hosts
- Range and conditional request forwarding for seeking and caching behavior
- Resume position stored locally per media URL
- Playback speed, fullscreen and reset-resume controls
- M3U/M3U8 playlist parsing with safe URL validation
- PWA shell caching that explicitly excludes `/api/stream` media responses
- Provider search, category filtering, availability filtering, A–Z/status/recent sorting, favorites and recent history

## Security decisions

The gateway fails closed when the allowlist is empty. It blocks localhost, `.local`, private IPv4 ranges, IPv4-mapped private IPv6, loopback, link-local and unique-local IPv6 targets. Redirects are fetched manually and each destination is checked again. This follows OWASP's recommendation to use an allowlist when the application only needs to call identified trusted systems, to validate URL targets, and to disable unsafe automatic redirects. [3]

## Deployment checklist

```env
APP_ORIGIN=https://moviecloudproject.vercel.app
ALLOWED_STREAM_HOSTS=media.example.com,cdn.example.com
```

- Put only owned or licensed hosts in `ALLOWED_STREAM_HOSTS`.
- Publish only direct `.mp4`/`.m3u8` sources in `web/media-catalog.json`.
- Ensure the origin/CDN serves HLS manifests, segments, keys and subtitles with suitable CORS headers.
- Confirm byte-range requests work for MP4.
- Test a controlled VOD fixture and a controlled live HLS fixture before publishing.
- Never commit signed URLs, credentials, private tokens or unrestricted wildcard hosts.

## References

[1]: https://developer.apple.com/documentation/http-live-streaming "Apple HTTP Live Streaming documentation"
[2]: https://github.com/video-dev/hls.js/ "hls.js browser support and CORS documentation"
[3]: https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html "OWASP Server-Side Request Forgery Prevention Cheat Sheet"
[4]: https://developers.cloudflare.com/stream/viewing-videos/securing-your-stream/ "Cloudflare Stream secure signed playback documentation"
[5]: https://developers.cloudflare.com/r2/buckets/public-buckets/ "Cloudflare R2 public buckets and custom domains"
[6]: https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-signed-urls.html "Amazon CloudFront signed URLs documentation"
