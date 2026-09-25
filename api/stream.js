export const config = { runtime: "nodejs" };

const ALLOWED_HOSTS = new Set(
  String(process.env.ALLOWED_STREAM_HOSTS || "")
    .split(",")
    .map(v => v.trim().toLowerCase())
    .filter(Boolean)
);

const HOP_BY_HOP = new Set([
  "connection","keep-alive","proxy-authenticate","proxy-authorization",
  "te","trailer","transfer-encoding","upgrade"
]);

function json(body, status=200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {"content-type":"application/json; charset=utf-8","cache-control":"no-store"}
  });
}

export default async function handler(req) {
  if (req.method === "OPTIONS") {
    return new Response(null, {
      status: 204,
      headers: {
        "access-control-allow-origin": "*",
        "access-control-allow-methods": "GET, HEAD, OPTIONS",
        "access-control-allow-headers": "Range, Content-Type"
      }
    });
  }

  if (req.method !== "GET" && req.method !== "HEAD") {
    return json({error:"Method not allowed"},405);
  }

  const { searchParams } = new URL(req.url);
  const raw = searchParams.get("url");
  if (!raw) return json({error:"Missing url"},400);

  let target;
  try {
    target = new URL(raw);
  } catch {
    return json({error:"Invalid url"},400);
  }

  if (!["http:","https:"].includes(target.protocol)) {
    return json({error:"Only HTTP(S) media sources are supported"},400);
  }

  const host = target.hostname.toLowerCase();
  if (!ALLOWED_HOSTS.has(host)) {
    return json({
      error:"Source host is not allowlisted",
      hint:"Set ALLOWED_STREAM_HOSTS in Vercel to domains you own or are authorized to relay."
    },403);
  }

  const headers = new Headers();
  for (const name of ["range","if-range","accept","accept-language"]) {
    const value = req.headers.get(name);
    if (value) headers.set(name,value);
  }
  headers.set("user-agent","MovieCloud-MediaGateway/1.0");

  const upstream = await fetch(target, {
    method: req.method,
    headers,
    redirect: "follow",
    signal: req.signal
  });

  const out = new Headers();
  for (const [name,value] of upstream.headers) {
    if (!HOP_BY_HOP.has(name.toLowerCase())) out.set(name,value);
  }

  out.set("access-control-allow-origin","*");
  out.set("access-control-expose-headers","Accept-Ranges,Content-Length,Content-Range,Content-Type,ETag,Last-Modified");
  out.set("cache-control","private, no-store");

  if (!out.has("accept-ranges")) out.set("accept-ranges","bytes");

  return new Response(req.method === "HEAD" ? null : upstream.body, {
    status: upstream.status,
    statusText: upstream.statusText,
    headers: out
  });
}
