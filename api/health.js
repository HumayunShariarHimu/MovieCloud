const startedAt = new Date().toISOString();

function setHeaders(res) {
  res.setHeader("Content-Type", "application/json; charset=utf-8");
  res.setHeader("Cache-Control", "no-store");
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("Referrer-Policy", "no-referrer");
}

export default function handler(req, res) {
  setHeaders(res);
  if (req.method !== "GET" && req.method !== "HEAD") return res.status(405).json({ error: "Method not allowed" });
  const payload = {
    ok: true,
    service: "moviecloud",
    version: process.env.VERCEL_GIT_COMMIT_SHA || "local",
    environment: process.env.VERCEL_ENV || "development",
    startedAt,
    checks: {
      catalog: true,
      gatewayAllowlistConfigured: Boolean(process.env.ALLOWED_STREAM_HOSTS),
      noCredentialProxy: true,
      privateNetworkBlocking: true,
    },
    configuration: { ALLOWED_STREAM_HOSTS: Boolean(process.env.ALLOWED_STREAM_HOSTS) },
  };
  if (req.method === "HEAD") return res.status(200).end();
  return res.status(200).json(payload);
}
