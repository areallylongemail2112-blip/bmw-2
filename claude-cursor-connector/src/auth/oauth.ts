import type { Express, Request, Response } from "express";
import type { ConnectorConfig } from "../config.js";
import { looksLikeCursorApiKey, mcpResourceUrl, originUrl } from "../config.js";
import { OAuthStore, randomToken, sha256Base64Url, type PendingAuthorization } from "./store.js";

const CLAUDE_WEB_CALLBACK = "https://claude.ai/api/mcp/auth_callback";

export function isLoopbackCallback(uri: string): boolean {
  try {
    const url = new URL(uri);
    const hostOk = url.hostname === "localhost" || url.hostname === "127.0.0.1";
    return url.protocol === "http:" && hostOk && (url.pathname === "/callback" || url.pathname === "/");
  } catch {
    return false;
  }
}

export function isAllowedRedirect(uri: string, registered: string[]): boolean {
  if (registered.includes(uri)) return true;
  if (uri === CLAUDE_WEB_CALLBACK) return true;
  return isLoopbackCallback(uri);
}

function loginPage(pending: PendingAuthorization, error?: string): string {
  const err = error
    ? `<p class="err">${escapeHtml(error)}</p>`
    : "";
  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Connect Cursor to Claude</title>
  <style>
    :root { color-scheme: light dark; }
    body { font-family: ui-sans-serif, system-ui, sans-serif; margin: 0; background: #0b0c10; color: #e8eaed; }
    main { max-width: 32rem; margin: 8vh auto; padding: 2rem; background: #15171c; border: 1px solid #2a2e37; border-radius: 16px; }
    h1 { font-size: 1.35rem; margin: 0 0 .5rem; }
    p { line-height: 1.5; color: #c5c9d1; }
    label { display: block; margin: 1.25rem 0 .4rem; font-weight: 600; }
    input { width: 100%; box-sizing: border-box; padding: .7rem .8rem; border-radius: 8px; border: 1px solid #3a3f4b; background: #0b0c10; color: inherit; }
    button { margin-top: 1.25rem; width: 100%; padding: .8rem; border: 0; border-radius: 8px; background: #f54e00; color: white; font-weight: 700; cursor: pointer; }
    .err { color: #ffb4a8; }
    a { color: #8ab4f8; }
    .hint { font-size: .9rem; }
  </style>
</head>
<body>
  <main>
    <h1>Connect Cursor as a Claude agent</h1>
    <p>Paste a Cursor API key so Claude can launch Cursor cloud agents on your behalf. Mint one at
      <a href="https://cursor.com/dashboard/cloud-agents">cursor.com/dashboard/cloud-agents</a>.
    </p>
    ${err}
    <form method="post" action="/authorize">
      <input type="hidden" name="request_id" value="${escapeHtml(pending.id)}" />
      <label for="cursor_api_key">Cursor API key</label>
      <input id="cursor_api_key" name="cursor_api_key" type="password" autocomplete="off" required placeholder="cursor_..." />
      <p class="hint">The key stays on this connector. Claude receives an OAuth access token, not the key itself.</p>
      <button type="submit">Authorize Claude</button>
    </form>
  </main>
</body>
</html>`;
}

function escapeHtml(value: string): string {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function json(res: Response, status: number, body: unknown): void {
  res.status(status).json(body);
}

export function mountOAuth(app: Express, config: ConnectorConfig, store: OAuthStore): void {
  const origin = originUrl(config);
  const resource = mcpResourceUrl(config);

  const metadata = () => ({
    resource,
    authorization_servers: [origin],
    bearer_methods_supported: ["header"],
    scopes_supported: ["cursor:agent"],
  });

  const asMetadata = () => ({
    issuer: origin,
    authorization_endpoint: `${origin}/authorize`,
    token_endpoint: `${origin}/token`,
    registration_endpoint: `${origin}/register`,
    response_types_supported: ["code"],
    grant_types_supported: ["authorization_code", "refresh_token"],
    code_challenge_methods_supported: ["S256"],
    token_endpoint_auth_methods_supported: ["none"],
    scopes_supported: ["cursor:agent", "offline_access"],
    client_id_metadata_document_supported: true,
    revocation_endpoint: `${origin}/revoke`,
  });

  app.get("/.well-known/oauth-protected-resource", (_req, res) => {
    res.json(metadata());
  });
  app.get("/.well-known/oauth-protected-resource/mcp", (_req, res) => {
    res.json(metadata());
  });
  app.get("/.well-known/oauth-authorization-server", (_req, res) => {
    res.json(asMetadata());
  });
  app.get("/.well-known/openid-configuration", (_req, res) => {
    res.json(asMetadata());
  });

  app.post("/register", (req, res) => {
    const body = (req.body ?? {}) as {
      client_name?: string;
      redirect_uris?: string[];
      grant_types?: string[];
      response_types?: string[];
      token_endpoint_auth_method?: string;
    };
    const redirectUris = Array.isArray(body.redirect_uris) ? body.redirect_uris : [CLAUDE_WEB_CALLBACK];
    const clientId = randomToken(18);
    store.clients.set(clientId, {
      clientId,
      clientName: body.client_name,
      redirectUris,
      createdAt: Date.now(),
    });
    store.touch();
    json(res, 201, {
      client_id: clientId,
      client_name: body.client_name ?? "Claude",
      redirect_uris: redirectUris,
      grant_types: body.grant_types ?? ["authorization_code", "refresh_token"],
      response_types: body.response_types ?? ["code"],
      token_endpoint_auth_method: "none",
      client_id_issued_at: Math.floor(Date.now() / 1000),
    });
  });

  app.get("/authorize", (req, res) => {
    const clientId = String(req.query.client_id ?? "");
    const redirectUri = String(req.query.redirect_uri ?? "");
    const state = req.query.state ? String(req.query.state) : undefined;
    const challenge = String(req.query.code_challenge ?? "");
    const method = String(req.query.code_challenge_method ?? "");
    const scope = req.query.scope ? String(req.query.scope) : "cursor:agent";

    if (!clientId || !redirectUri || !challenge) {
      res.status(400).send("Missing client_id, redirect_uri, or code_challenge.");
      return;
    }
    if (method && method !== "S256") {
      res.status(400).send("Only S256 PKCE is supported.");
      return;
    }
    const client = store.clients.get(clientId);
    const registered = client?.redirectUris ?? [CLAUDE_WEB_CALLBACK];
    if (!isAllowedRedirect(redirectUri, registered)) {
      res.status(400).send("redirect_uri is not allowed for this client.");
      return;
    }
    const pending: PendingAuthorization = {
      id: randomToken(16),
      clientId,
      redirectUri,
      state,
      codeChallenge: challenge,
      scope,
      expiresAt: Date.now() + 10 * 60 * 1000,
    };
    store.pending.set(pending.id, pending);
    store.touch();
    res.type("html").send(loginPage(pending));
  });

  app.post("/authorize", (req, res) => {
    const body = req.body as { request_id?: string; cursor_api_key?: string };
    const pending = body.request_id ? store.pending.get(body.request_id) : undefined;
    if (!pending || pending.expiresAt <= Date.now()) {
      res.status(400).type("html").send("<p>This authorization request expired. Start again from Claude.</p>");
      return;
    }
    const apiKey = body.cursor_api_key?.trim() ?? "";
    if (!looksLikeCursorApiKey(apiKey)) {
      res.status(400).type("html").send(loginPage(pending, "That does not look like a Cursor API key (cursor_…)."));
      return;
    }
    store.pending.delete(pending.id);
    const code = randomToken(24);
    store.authCodes.set(code, {
      code,
      clientId: pending.clientId,
      redirectUri: pending.redirectUri,
      codeChallenge: pending.codeChallenge,
      apiKey,
      expiresAt: Date.now() + 5 * 60 * 1000,
      scope: pending.scope,
    });
    store.touch();
    const redirect = new URL(pending.redirectUri);
    redirect.searchParams.set("code", code);
    if (pending.state) redirect.searchParams.set("state", pending.state);
    res.redirect(redirect.toString());
  });

  app.post("/token", (req, res) => {
    const body = req.body as Record<string, string | undefined>;
    const grantType = body.grant_type;
    if (grantType === "authorization_code") {
      const code = body.code ?? "";
      const verifier = body.code_verifier ?? "";
      const redirectUri = body.redirect_uri ?? "";
      const record = store.authCodes.get(code);
      store.authCodes.delete(code);
      if (!record || record.expiresAt <= Date.now()) {
        json(res, 400, { error: "invalid_grant", error_description: "Authorization code is invalid or expired." });
        return;
      }
      if (record.redirectUri !== redirectUri) {
        json(res, 400, { error: "invalid_grant", error_description: "redirect_uri mismatch." });
        return;
      }
      if (sha256Base64Url(verifier) !== record.codeChallenge) {
        json(res, 400, { error: "invalid_grant", error_description: "PKCE verification failed." });
        return;
      }
      json(res, 200, issueTokens(store, config, record.clientId, record.apiKey));
      return;
    }
    if (grantType === "refresh_token") {
      const presented = body.refresh_token ?? "";
      const refresh = store.refreshTokens.get(presented);
      store.refreshTokens.delete(presented);
      if (!refresh || refresh.expiresAt <= Date.now()) {
        json(res, 400, { error: "invalid_grant", error_description: "Refresh token is invalid or expired." });
        return;
      }
      json(res, 200, issueTokens(store, config, refresh.clientId, refresh.apiKey));
      return;
    }
    json(res, 400, { error: "unsupported_grant_type" });
  });

  app.post("/revoke", (req, res) => {
    const token = String((req.body as { token?: string }).token ?? "");
    store.accessTokens.delete(token);
    store.refreshTokens.delete(token);
    store.touch();
    res.status(200).send();
  });
}

function issueTokens(
  store: OAuthStore,
  config: ConnectorConfig,
  clientId: string,
  apiKey: string,
): {
  access_token: string;
  token_type: "bearer";
  expires_in: number;
  refresh_token: string;
  scope: string;
} {
  const access = randomToken(32);
  const refresh = randomToken(32);
  const now = Date.now();
  store.accessTokens.set(access, {
    token: access,
    apiKey,
    clientId,
    expiresAt: now + config.accessTtlSeconds * 1000,
    refreshToken: refresh,
  });
  store.refreshTokens.set(refresh, {
    token: refresh,
    apiKey,
    clientId,
    expiresAt: now + config.refreshTtlSeconds * 1000,
  });
  store.touch();
  return {
    access_token: access,
    token_type: "bearer",
    expires_in: config.accessTtlSeconds,
    refresh_token: refresh,
    scope: "cursor:agent",
  };
}
