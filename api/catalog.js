import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const catalogPath = fileURLToPath(new URL("../web/media-catalog.json", import.meta.url));
const allowedOrigin = process.env.APP_ORIGIN || "https://moviecloudproject.vercel.app";
const streamCustomerCode = process.env.CLOUDFLARE_STREAM_CUSTOMER_CODE || "";

function setHeaders(res) {
  res.setHeader("Content-Type", "application/json; charset=utf-8");
  res.setHeader("Cache-Control", "no-store");
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("Referrer-Policy", "no-referrer");
  res.setHeader("Access-Control-Allow-Origin", allowedOrigin);
  res.setHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type");
}

function validHttpUrl(value) {
  try { const url = new URL(value); return ["http:", "https:"].includes(url.protocol) && !url.username && !url.password; } catch { return false; }
}

export function validateCatalog(catalog) {
  if (!catalog || typeof catalog !== "object" || !Number.isInteger(catalog.version) || !catalog.providers || typeof catalog.providers !== "object" || !Array.isArray(catalog.titles)) return false;
  const sourceValid = source => source && typeof source === "object" && typeof source.label === "string" && ["MP4", "HLS"].includes(source.type) && (validHttpUrl(source.url) || source.delivery === "cloudflare-stream" && typeof source.playbackId === "string" && /^[A-Za-z0-9_-]{6,128}$/.test(source.playbackId)) && (!source.subtitles || Array.isArray(source.subtitles) && source.subtitles.every(track => typeof track?.label === "string" && validHttpUrl(track.url)));
  for (const provider of Object.values(catalog.providers)) if (!provider || !Array.isArray(provider.sources) || !provider.sources.every(sourceValid)) return false;
  return catalog.titles.every(title => typeof title?.id === "string" && /^[a-z0-9][a-z0-9-]{1,79}$/.test(title.id) && typeof title.title === "string" && Array.isArray(title.sources) && title.sources.length > 0 && title.sources.every(sourceValid));
}

function resolveSource(source) {
  if (source.delivery !== "cloudflare-stream" || !streamCustomerCode) return source;
  return {...source, type: "HLS", url: `https://customer-${streamCustomerCode}.cloudflarestream.com/${encodeURIComponent(source.playbackId)}/manifest/video.m3u8`};
}

function hydrateCatalog(catalog) {
  const map = provider => ({...provider, sources: provider.sources.map(resolveSource)});
  return {...catalog, providers: Object.fromEntries(Object.entries(catalog.providers).map(([name, provider]) => [name, map(provider)])), titles: catalog.titles.map(title => ({...title, sources: title.sources.map(resolveSource)}))};
}

export default async function handler(req, res) {
  setHeaders(res);
  if (req.method === "OPTIONS") return res.status(204).end();
  if (req.method !== "GET" && req.method !== "HEAD") return res.status(405).json({ error: "Method not allowed" });
  try {
    const catalog = JSON.parse(await readFile(catalogPath, "utf8"));
    if (!validateCatalog(catalog)) return res.status(503).json({ error: "Catalog schema is invalid" });
    const payload = {
      ...hydrateCatalog(catalog),
      gateway: process.env.MEDIA_GATEWAY_URL || "",
      policy: "Only owned or explicitly authorized media URLs may be added. DRM bypass and provider scraping are not supported.",
      generatedAt: new Date().toISOString(),
    };
    if (req.method === "HEAD") return res.status(200).end();
    return res.status(200).json(payload);
  } catch {
    return res.status(503).json({ error: "Catalog unavailable" });
  }
}
