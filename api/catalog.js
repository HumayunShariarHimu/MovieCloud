import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const catalogPath = fileURLToPath(new URL("../web/media-catalog.json", import.meta.url));
const allowedOrigin = process.env.APP_ORIGIN || "https://moviecloudproject.vercel.app";

function headers() {
  return {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
    "referrer-policy": "no-referrer",
    "access-control-allow-origin": allowedOrigin,
    "access-control-allow-methods": "GET, HEAD, OPTIONS",
    "access-control-allow-headers": "Content-Type",
  };
}

function response(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: headers() });
}

export default async function handler(request) {
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: headers() });
  if (!["GET", "HEAD"].includes(request.method)) return response({ error: "Method not allowed" }, 405);
  try {
    const catalog = JSON.parse(await readFile(catalogPath, "utf8"));
    const payload = {
      ...catalog,
      policy: "Only owned or explicitly authorized media URLs may be added. DRM bypass and provider scraping are not supported.",
      generatedAt: new Date().toISOString(),
    };
    return new Response(request.method === "HEAD" ? null : JSON.stringify(payload), { status: 200, headers: headers() });
  } catch {
    return response({ error: "Catalog unavailable" }, 503);
  }
}
