import express, { type Request, type Response } from "express";
import { randomUUID } from "node:crypto";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { isInitializeRequest } from "@modelcontextprotocol/sdk/types.js";
import type { ConnectorConfig } from "../config.js";
import { mcpResourceUrl, originUrl } from "../config.js";
import { mountOAuth } from "../auth/oauth.js";
import { AuthError, extractBearer, resolveAuth, wwwAuthenticate } from "../auth/resolve.js";
import { OAuthStore } from "../auth/store.js";
import type { CursorClient } from "../types.js";
import { createConnectorServer } from "../mcp/create-server.js";
import { CONNECTOR_NAME, CONNECTOR_VERSION } from "../config.js";

interface Session {
  transport: StreamableHTTPServerTransport;
}

export async function createHttpApp(options: {
  config: ConnectorConfig;
  cursor: CursorClient;
  store: OAuthStore;
}): Promise<express.Express> {
  const { config, cursor, store } = options;
  const app = express();
  app.disable("x-powered-by");
  app.use(express.json({ limit: "4mb" }));
  app.use(express.urlencoded({ extended: false }));

  app.use((req, res, next) => {
    res.setHeader("Access-Control-Allow-Origin", req.headers.origin ?? "*");
    res.setHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
    res.setHeader(
      "Access-Control-Allow-Headers",
      "Authorization, Content-Type, Accept, Mcp-Session-Id, Mcp-Protocol-Version, Last-Event-ID, x-api-key, x-auth-token",
    );
    res.setHeader("Access-Control-Expose-Headers", "Mcp-Session-Id");
    if (req.method === "OPTIONS") {
      res.status(204).end();
      return;
    }
    next();
  });

  mountOAuth(app, config, store);

  app.get("/health", (_req, res) => {
    res.json({
      ok: true,
      name: CONNECTOR_NAME,
      version: CONNECTOR_VERSION,
      mcp: mcpResourceUrl(config),
    });
  });

  const sessions = new Map<string, Session>();

  const requireAuth = (req: Request, res: Response): ReturnType<typeof resolveAuth> | undefined => {
    try {
      return resolveAuth(extractBearer(req), config, store);
    } catch (err) {
      const message = err instanceof AuthError ? err.message : "Unauthorized";
      res.setHeader("WWW-Authenticate", wwwAuthenticate(`${originUrl(config)}/.well-known/oauth-protected-resource`));
      res.status(401).json({ error: "unauthorized", error_description: message });
      return undefined;
    }
  };

  const handleMcp = async (req: Request, res: Response) => {
    const sessionId = req.header("mcp-session-id") ?? undefined;

    if (sessionId && sessions.has(sessionId)) {
      if (!requireAuth(req, res)) return;
      await sessions.get(sessionId)!.transport.handleRequest(req, res, req.body);
      return;
    }

    if (req.method === "GET" && !sessionId) {
      res.setHeader("Allow", "POST");
      res.status(405).json({ error: "method_not_allowed", error_description: "Use POST /mcp for Streamable HTTP." });
      return;
    }

    if (req.method !== "POST" || !isInitializeRequest(req.body)) {
      if (!sessionId) {
        res.status(400).json({
          jsonrpc: "2.0",
          error: { code: -32000, message: "Bad Request: No valid session ID provided" },
          id: null,
        });
        return;
      }
      res.status(404).json({
        jsonrpc: "2.0",
        error: { code: -32001, message: "Session not found" },
        id: null,
      });
      return;
    }

    const auth = requireAuth(req, res);
    if (!auth) return;

    const transport = new StreamableHTTPServerTransport({
      sessionIdGenerator: () => randomUUID(),
      onsessioninitialized: (id) => {
        sessions.set(id, { transport });
      },
    });
    transport.onclose = () => {
      if (transport.sessionId) sessions.delete(transport.sessionId);
    };

    const server = createConnectorServer({
      auth,
      cursor,
      defaultRuntime: "cloud",
    });
    await server.connect(transport);
    await transport.handleRequest(req, res, req.body);
  };

  app.all("/mcp", (req, res) => {
    void handleMcp(req, res).catch((err: unknown) => {
      console.error("MCP request failed", err);
      if (!res.headersSent) {
        res.status(500).json({ jsonrpc: "2.0", error: { code: -32603, message: "Internal error" }, id: null });
      }
    });
  });

  return app;
}

export async function listenHttp(options: {
  config: ConnectorConfig;
  cursor: CursorClient;
  store: OAuthStore;
}): Promise<{ close: () => Promise<void> }> {
  const app = await createHttpApp(options);
  const server = await new Promise<import("node:http").Server>((resolve) => {
    const s = app.listen(options.config.port, options.config.host, () => resolve(s));
  });
  const addr = server.address();
  const shown =
    typeof addr === "object" && addr
      ? `${addr.address}:${addr.port}`
      : `${options.config.host}:${options.config.port}`;
  console.error(`Claude Cursor Connector listening on http://${shown}`);
  console.error(`MCP endpoint: ${mcpResourceUrl(options.config)}`);
  console.error("Add this URL in Claude → Customize → Connectors → Add custom connector.");
  return {
    close: () =>
      new Promise((resolve, reject) => {
        server.close((err) => (err ? reject(err) : resolve()));
      }),
  };
}
