export const config = { runtime: "nodejs", maxDuration: 300 };

const ALLOWED_HOST_PATTERNS = String(process.env.ALLOWED_STREAM_HOSTS || "")
  .split(",").map(v => v.trim().toLowerCase()).filter(Boolean);

export function isAllowedHost(url, patterns = ALLOWED_HOST_PATTERNS) {
  const host = url.hostname.toLowerCase();
  return patterns.some(pattern => {
    if (pattern.startsWith("*.")) {
      const suffix = pattern.slice(1);
      return host.endsWith(suffix) && host.length > suffix.length;
    }
    return host === pattern;
  });
}

const HOP_BY_HOP = new Set([
  "connection","keep-alive","proxy-authenticate","proxy-authorization",
  "te","trailer","transfer-encoding","upgrade"
]);

const PASS_HEADERS = [
  "range","if-range","accept","accept-language","if-none-match","if-modified-since"
];

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store"
    }
  });
}

function gatewayUrl(target) {
  return "/api/stream?url=" + encodeURIComponent(target.href);
}

export function rewriteM3U8(body, baseUrl, patterns = ALLOWED_HOST_PATTERNS) {
  const rewrite = (raw) => {
    try {
      const absolute = new URL(raw, baseUrl);
      if (!["http:","https:"].includes(absolute.protocol) || !isAllowedHost(absolute, patterns)) return raw;
      return gatewayUrl(absolute);
    } catch {
      return raw;
    }
  };

  return body
    .replace(/URI="([^"]+)"/g, (_, uri) => 'URI="' + rewrite(uri) + '"')
    .split(/\r?\n/)
    .map(line => {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith("#")) return line;
      return rewrite(trimmed);
    })
    .join("\n");
}

async function fetchAllowed(url, req, depth = 0) {
  if (depth > 3 || !isAllowedHost(url)) {
    throw new Error("Redirect target is not allowlisted");
  }

  const headers = new Headers();
  for (const name of PASS_HEADERS) {
    const value = req.headers.get(name);
    if (value) headers.set(name, value);
  }
  headers.set("user-agent", "MovieCloud-MediaGateway/1.1");

  const upstream = await fetch(url, {
    method: req.method,
    headers,
    redirect: "manual",
    signal: req.signal
  });

  if ([301,302,303,307,308].includes(upstream.status)) {
    const location = upstream.headers.get("location");
    if (!location) return upstream;
    return fetchAllowed(new URL(location, url), req, depth + 1);
  }

  return upstream;
}

export default async function handler(req) {
  if (req.method === "OPTIONS") {
    return new Response(null, {
      status: 204,
      headers: {
        "access-control-allow-origin": "*",
        "access-control-allow-methods": "GET, HEAD, OPTIONS",
        "access-control-allow-headers": "Range, Content-Type, If-Range"
      }
    });
  }

  if (req.method !== "GET" && req.method !== "HEAD") {
    return json({ error: "Method not allowed" }, 405);
  }

  const raw = new URL(req.url).searchParams.get("url");
  if (!raw) return json({ error: "Missing url" }, 400);

  let target;
  try { target = new URL(raw); } catch {
    return json({ error: "Invalid url" }, 400);
  }

  if (!["http:","https:"].includes(target.protocol)) {
    return json({ error: "Only HTTP(S) media sources are supported" }, 400);
  }

  if (target.username || target.password) {
    return json({ error: "Credential-bearing media URLs are not accepted" }, 400);
  }

  if (!isAllowedHost(target)) {
    return json({
      error: "Source host is not allowlisted",
      hint: "Set ALLOWED_STREAM_HOSTS in Vercel to domains you own or are authorized to relay."
    }, 403);
  }

  let upstream;
  try {
    upstream = await fetchAllowed(target, req);
  } catch (error) {
    return json({ error: "Upstream request failed" }, 502);
  }

  const contentType = (upstream.headers.get("content-type") || "").toLowerCase();
  const looksLikeHls = /\.m3u8(?:$|[?#])/i.test(target.pathname + target.search)
    || contentType.includes("application/vnd.apple.mpegurl")
    || contentType.includes("application/x-mpegurl");

  const out = new Headers();
  for (const [name, value] of upstream.headers) {
    if (!HOP_BY_HOP.has(name.toLowerCase())) out.set(name, value);
  }

  out.set("access-control-allow-origin", "*");
  out.set("access-control-expose-headers",
    "Accept-Ranges,Content-Length,Content-Range,Content-Type,ETag,Last-Modified");
  out.set("cache-control", "private, no-store");
  out.set("x-content-type-options", "nosniff");

  if (looksLikeHls && req.method === "GET" && upstream.ok) {
    try {
      const text = await upstream.text();
      const rewritten = rewriteM3U8(text, target);
      out.set("content-type", "application/vnd.apple.mpegurl; charset=utf-8");
      out.delete("content-length");
      return new Response(rewritten, {
        status: upstream.status,
        statusText: upstream.statusText,
        headers: out
      });
    } catch {
      // Fall through to the original stream if the manifest cannot be decoded.
    }
  }

  if (!out.has("accept-ranges")) out.set("accept-ranges", "bytes");

  return new Response(req.method === "HEAD" ? null : upstream.body, {
    status: upstream.status,
    statusText: upstream.statusText,
    headers: out
  });
}
