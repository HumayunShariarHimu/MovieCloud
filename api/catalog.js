import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const catalogPath = fileURLToPath(new URL("../web/media-catalog.json", import.meta.url));
const allowedOrigin = process.env.APP_ORIGIN || "https://moviecloudproject.vercel.app";

function setHeaders(res) {
  res.setHeader("Content-Type", "application/json; charset=utf-8");
  res.setHeader("Cache-Control", "no-store");
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("Referrer-Policy", "no-referrer");
  res.setHeader("Access-Control-Allow-Origin", allowedOrigin);
  res.setHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type");
}

export default async function handler(req, res) {
  setHeaders(res);
  if (req.method === "OPTIONS") return res.status(204).end();
  if (req.method !== "GET" && req.method !== "HEAD") return res.status(405).json({ error: "Method not allowed" });
  try {
    const catalog = JSON.parse(await readFile(catalogPath, "utf8"));
    const payload = {
      ...catalog,
      policy: "Only owned or explicitly authorized media URLs may be added. DRM bypass and provider scraping are not supported.",
      generatedAt: new Date().toISOString(),
    };
    if (req.method === "HEAD") return res.status(200).end();
    return res.status(200).json(payload);
  } catch {
    return res.status(503).json({ error: "Catalog unavailable" });
  }
}
