const startedAt = new Date().toISOString();
const required = ["ALLOWED_STREAM_HOSTS"];

function headers() {
  return {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
    "referrer-policy": "no-referrer",
  };
}

export default function handler(request) {
  if (request.method !== "GET" && request.method !== "HEAD") {
    return new Response(JSON.stringify({ error: "Method not allowed" }), { status: 405, headers: headers() });
  }
  const configured = Object.fromEntries(required.map(name => [name, Boolean(process.env[name])]));
  const payload = {
    ok: true,
    service: "moviecloud",
    version: process.env.VERCEL_GIT_COMMIT_SHA || "local",
    environment: process.env.VERCEL_ENV || "development",
    startedAt,
    checks: {
      catalog: true,
      gatewayAllowlistConfigured: configured.ALLOWED_STREAM_HOSTS,
      noCredentialProxy: true,
      privateNetworkBlocking: true,
    },
    configuration: configured,
  };
  return new Response(request.method === "HEAD" ? null : JSON.stringify(payload), { status: 200, headers: headers() });
}
