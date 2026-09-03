import { createServer } from "node:http";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { sha256Base64Url } from "../src/auth/store.js";
import { OAuthStore } from "../src/auth/store.js";
import { loadConfig } from "../src/config.js";
import { createHttpApp } from "../src/http/app.js";
import { FakeCursorClient } from "./fake-cursor.js";

async function listen(app: Awaited<ReturnType<typeof createHttpApp>>): Promise<{
  url: string;
  close: () => Promise<void>;
}> {
  const server = createServer(app);
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const addr = server.address();
  if (!addr || typeof addr === "string") throw new Error("no address");
  return {
    url: `http://127.0.0.1:${addr.port}`,
    close: () =>
      new Promise((resolve, reject) => server.close((err) => (err ? reject(err) : resolve()))),
  };
}

describe("OAuth + HTTP connector", () => {
  const store = new OAuthStore();
  const cursor = new FakeCursorClient();
  let base = "";
  let close: () => Promise<void> = async () => undefined;

  beforeAll(async () => {
    const config = loadConfig({
      publicBaseUrl: "http://127.0.0.1",
      allowUnauthenticated: false,
      cursorApiKey: undefined,
      host: "127.0.0.1",
      port: 0,
    });
    const app = await createHttpApp({ config, cursor, store });
    const listening = await listen(app);
    base = listening.url;
    close = listening.close;
  });

  afterAll(async () => {
    await close();
  });

  it("serves health without auth", async () => {
    const res = await fetch(`${base}/health`);
    expect(res.status).toBe(200);
    const body = (await res.json()) as { ok: boolean };
    expect(body.ok).toBe(true);
  });

  it("advertises protected resource and authorization server metadata", async () => {
    const pr = await fetch(`${base}/.well-known/oauth-protected-resource`);
    expect(pr.status).toBe(200);
    const prBody = (await pr.json()) as { authorization_servers: string[] };
    expect(prBody.authorization_servers[0]).toContain("http://");

    const as = await fetch(`${base}/.well-known/oauth-authorization-server`);
    expect(as.status).toBe(200);
    const asBody = (await as.json()) as {
      code_challenge_methods_supported: string[];
      token_endpoint_auth_methods_supported: string[];
    };
    expect(asBody.code_challenge_methods_supported).toContain("S256");
    expect(asBody.token_endpoint_auth_methods_supported).toContain("none");
  });

  it("returns 401 with resource_metadata on unauthenticated MCP", async () => {
    const res = await fetch(`${base}/mcp`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        accept: "application/json, text/event-stream",
      },
      body: JSON.stringify({
        jsonrpc: "2.0",
        id: 1,
        method: "initialize",
        params: {
          protocolVersion: "2025-03-26",
          capabilities: {},
          clientInfo: { name: "test", version: "1.0.0" },
        },
      }),
    });
    expect(res.status).toBe(401);
    const www = res.headers.get("www-authenticate") ?? "";
    expect(www).toContain("resource_metadata=");
  });

  it("returns 405 on GET /mcp without a session (Claude probe)", async () => {
    const res = await fetch(`${base}/mcp`);
    expect(res.status).toBe(405);
  });

  it("completes PKCE authorization_code + lists Cursor tools", async () => {
    const register = await fetch(`${base}/register`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        client_name: "test",
        redirect_uris: ["http://127.0.0.1/callback"],
      }),
    });
    expect(register.status).toBe(201);
    const client = (await register.json()) as { client_id: string };

    const verifier = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    const challenge = sha256Base64Url(verifier);
    const authorize = await fetch(
      `${base}/authorize?client_id=${client.client_id}&redirect_uri=${encodeURIComponent("http://127.0.0.1/callback")}&response_type=code&code_challenge=${challenge}&code_challenge_method=S256&state=xyz`,
      { redirect: "manual" },
    );
    expect(authorize.status).toBe(200);
    const html = await authorize.text();
    const requestId = html.match(/name="request_id" value="([^"]+)"/)?.[1];
    expect(requestId).toBeTruthy();

    const submit = await fetch(`${base}/authorize`, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      redirect: "manual",
      body: new URLSearchParams({
        request_id: requestId!,
        cursor_api_key: "cursor_abcdefghijk",
      }),
    });
    expect(submit.status).toBe(302);
    const location = submit.headers.get("location") ?? "";
    const code = new URL(location).searchParams.get("code");
    expect(code).toBeTruthy();
    expect(new URL(location).searchParams.get("state")).toBe("xyz");

    const tokenRes = await fetch(`${base}/token`, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "authorization_code",
        code: code!,
        redirect_uri: "http://127.0.0.1/callback",
        code_verifier: verifier,
        client_id: client.client_id,
      }),
    });
    expect(tokenRes.status).toBe(200);
    const tokens = (await tokenRes.json()) as { access_token: string; refresh_token: string };
    expect(tokens.access_token).toBeTruthy();

    const init = await mcpCall(base, tokens.access_token, {
      jsonrpc: "2.0",
      id: 1,
      method: "initialize",
      params: {
        protocolVersion: "2025-03-26",
        capabilities: {},
        clientInfo: { name: "test", version: "1.0.0" },
      },
    });
    expect(init.status).toBe(200);
    const sessionId = init.headers.get("mcp-session-id");
    expect(sessionId).toBeTruthy();
    const initBody = await readMcpJson(init);
    expect(initBody.result.serverInfo.name).toBe("claude-cursor-connector");

    await mcpCall(
      base,
      tokens.access_token,
      {
        jsonrpc: "2.0",
        method: "notifications/initialized",
      },
      sessionId!,
    );

    const listed = await mcpCall(
      base,
      tokens.access_token,
      { jsonrpc: "2.0", id: 2, method: "tools/list" },
      sessionId!,
    );
    const listBody = await readMcpJson(listed);
    const names = (listBody.result.tools as { name: string }[]).map((t) => t.name);
    expect(names).toContain("cursor_launch_agent");
    expect(names).toContain("cursor_follow_up");
    expect(names).toContain("cursor_wait_for_run");

    const launched = await mcpCall(
      base,
      tokens.access_token,
      {
        jsonrpc: "2.0",
        id: 3,
        method: "tools/call",
        params: {
          name: "cursor_launch_agent",
          arguments: {
            prompt: "Fix the flaky test in src/auth.ts",
            repository_url: "https://github.com/example/repo",
            wait: false,
          },
        },
      },
      sessionId!,
    );
    const launchBody = await readMcpJson(launched);
    const text = launchBody.result.content[0].text as string;
    expect(text).toContain("bc-launched-1");
    expect(text).toContain("run-launched-1");
    expect(cursor.launched[0]?.prompt).toBe("Fix the flaky test in src/auth.ts");
    expect(cursor.launched[0]?.wait).toBe(false);
  });
});

async function mcpCall(
  base: string,
  token: string,
  body: unknown,
  sessionId?: string,
): Promise<Response> {
  const headers: Record<string, string> = {
    "content-type": "application/json",
    accept: "application/json, text/event-stream",
    authorization: `Bearer ${token}`,
  };
  if (sessionId) headers["mcp-session-id"] = sessionId;
  return fetch(`${base}/mcp`, { method: "POST", headers, body: JSON.stringify(body) });
}

async function readMcpJson(res: Response): Promise<{ result: Record<string, any> }> {
  const ctype = res.headers.get("content-type") ?? "";
  if (ctype.includes("application/json")) {
    return (await res.json()) as { result: Record<string, any> };
  }
  const raw = await res.text();
  const dataLine = raw
    .split("\n")
    .map((l) => l.trim())
    .find((l) => l.startsWith("data:"));
  if (!dataLine) throw new Error(`No SSE data in: ${raw.slice(0, 500)}`);
  return JSON.parse(dataLine.slice(5).trim()) as { result: Record<string, any> };
}
