const MAX_REDIRECTS = 2;

function corsHeaders(origin, allowedOrigin) {
  const allow = origin && origin === allowedOrigin ? origin : allowedOrigin;
  return {
    "Access-Control-Allow-Origin": allow,
    "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
    "Access-Control-Allow-Headers": "Range, Content-Type, If-None-Match, If-Modified-Since",
    "Access-Control-Expose-Headers": "Accept-Ranges, Content-Length, Content-Range, Content-Type, ETag, Last-Modified",
    "Vary": "Origin",
  };
}

function allowedHost(hostname, configured) {
  const host = hostname.toLowerCase().replace(/\.$/, "");
  return configured.some(item => {
    const rule = item.toLowerCase().replace(/^https?:\/\//, "").split("/")[0].replace(/\.$/, "");
    return rule && (host === rule || host.endsWith(`.${rule}`));
  });
}

function isPrivateAddress(hostname) {
  const host = hostname.toLowerCase().replace(/^\[|\]$/g, "");
  if (host === "localhost" || host === "ip6-localhost" || host === "0.0.0.0" || host === "::1" || host.endsWith(".local")) return true;
  const parts = host.split(".").map(Number);
  if (parts.length === 4 && parts.every(Number.isInteger) && parts.every(n => n >= 0 && n <= 255)) {
    return parts[0] === 10 || parts[0] === 127 || parts[0] === 0 || (parts[0] === 169 && parts[1] === 254) || (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31) || (parts[0] === 192 && parts[1] === 168);
  }
  return host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80:");
}

function targetUrl(raw, configured) {
  let target;
  try { target = new URL(raw); } catch { return null; }
  if (!["https:", "http:"].includes(target.protocol) || target.username || target.password || !["", "80", "443"].includes(target.port)) return null;
  if (isPrivateAddress(target.hostname) || !allowedHost(target.hostname, configured)) return null;
  return target;
}

function manifestLike(response, target) {
  const type = response.headers.get("content-type") || "";
  return /mpegurl|vnd\.apple\.mpegurl/i.test(type) || /\.m3u8(?:$|[?#])/i.test(target.pathname);
}

function rewriteManifest(text, requestUrl, configured) {
  const base = new URL(requestUrl);
  const rewrite = raw => {
    const value = raw.trim();
    if (!value || value.startsWith("#")) return value;
    const absolute = new URL(value, base).href;
    const safe = targetUrl(absolute, configured);
    return safe ? `${base.origin}/stream?url=${encodeURIComponent(safe.href)}` : value;
  };
  return text.split(/(\r?\n)/).map(line => {
    if (/^#EXT-X-(KEY|MAP|MEDIA|I-FRAME-STREAM-INF)/i.test(line)) return line.replace(/URI="([^"]+)"/i, (_, uri) => `URI="${rewrite(uri)}"`);
    if (/^\s*#/.test(line) || !line.trim()) return line;
    return rewrite(line);
  }).join("");
}

async function relay(request, env, raw, redirects = 0) {
  const configured = String(env.ALLOWED_STREAM_HOSTS || "").split(",").map(x => x.trim()).filter(Boolean);
  const target = targetUrl(raw, configured);
  if (!target) return new Response(JSON.stringify({error: "Target is not an authorized public media host"}), {status: 403, headers: {"Content-Type": "application/json", ...corsHeaders(request.headers.get("Origin"), env.APP_ORIGIN)}});
  const headers = new Headers();
  for (const name of ["range", "if-none-match", "if-modified-since"]) if (request.headers.get(name)) headers.set(name, request.headers.get(name));
  const upstream = await fetch(target.href, {method: request.method, headers, redirect: "manual"});
  if (upstream.status >= 300 && upstream.status < 400) {
    if (redirects >= MAX_REDIRECTS) return new Response("Too many upstream redirects", {status: 502});
    const location = upstream.headers.get("Location");
    const next = location && targetUrl(new URL(location, target).href, configured);
    if (!next) return new Response("Upstream redirect leaves the authorized host allowlist", {status: 502});
    return relay(request, env, next.href, redirects + 1);
  }
  const baseHeaders = {...corsHeaders(request.headers.get("Origin"), env.APP_ORIGIN), "X-Content-Type-Options": "nosniff", "Cache-Control": "no-store"};
  if (!manifestLike(upstream, target) || request.method === "HEAD") {
    const responseHeaders = new Headers(baseHeaders);
    for (const name of ["accept-ranges", "content-length", "content-range", "content-type", "etag", "last-modified"]) if (upstream.headers.get(name)) responseHeaders.set(name, upstream.headers.get(name));
    return new Response(upstream.body, {status: upstream.status, headers: responseHeaders});
  }
  const rewritten = rewriteManifest(await upstream.text(), target.href, configured);
  return new Response(rewritten, {status: upstream.status, headers: {...baseHeaders, "Content-Type": "application/vnd.apple.mpegurl; charset=utf-8"}});
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const cors = corsHeaders(request.headers.get("Origin"), env.APP_ORIGIN);
    if (request.method === "OPTIONS") return new Response(null, {status: 204, headers: cors});
    if (url.pathname === "/health") return new Response(JSON.stringify({ok: true, service: "moviecloud-authorized-gateway", policy: "allowlist-only", configuredHosts: String(env.ALLOWED_STREAM_HOSTS || "").split(",").filter(Boolean).length}), {headers: {"Content-Type": "application/json", ...cors}});
    if (url.pathname !== "/stream" || !["GET", "HEAD"].includes(request.method)) return new Response(JSON.stringify({error: "Use GET /stream?url=<authorized-media-url>"}), {status: 404, headers: {"Content-Type": "application/json", ...cors}});
    const raw = url.searchParams.get("url");
    if (!raw) return new Response(JSON.stringify({error: "Missing url"}), {status: 400, headers: {"Content-Type": "application/json", ...cors}});
    try { return await relay(request, env, raw); } catch { return new Response(JSON.stringify({error: "Upstream media request failed"}), {status: 502, headers: {"Content-Type": "application/json", ...cors}}); }
  }
};
