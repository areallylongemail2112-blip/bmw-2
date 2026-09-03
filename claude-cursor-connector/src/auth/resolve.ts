import type { IncomingMessage } from "node:http";
import type { ConnectorConfig } from "../config.js";
import { looksLikeCursorApiKey } from "../config.js";
import type { AuthContext } from "../types.js";
import type { OAuthStore } from "./store.js";

export class AuthError extends Error {
  constructor(
    message: string,
    readonly status = 401,
  ) {
    super(message);
    this.name = "AuthError";
  }
}

export function extractBearer(req: IncomingMessage): string | undefined {
  const header = headerValue(req, "authorization");
  if (header?.toLowerCase().startsWith("bearer ")) {
    return header.slice(7).trim();
  }
  const apiKey = headerValue(req, "x-api-key") ?? headerValue(req, "x-auth-token");
  return apiKey?.trim() || undefined;
}

function headerValue(req: IncomingMessage, name: string): string | undefined {
  const raw = req.headers[name];
  if (Array.isArray(raw)) return raw[0];
  return raw;
}

export function resolveAuth(
  presented: string | undefined,
  config: ConnectorConfig,
  store?: OAuthStore,
): AuthContext {
  if (presented) {
    if (looksLikeCursorApiKey(presented)) {
      return { apiKey: presented, source: "cursor-api-key" };
    }
    if (config.connectorAuthToken && presented === config.connectorAuthToken) {
      if (!config.cursorApiKey) {
        throw new AuthError(
          "CONNECTOR_AUTH_TOKEN matched, but CURSOR_API_KEY is not set on the server.",
        );
      }
      return { apiKey: config.cursorApiKey, source: "connector-token" };
    }
    const oauth = store?.lookupAccess(presented);
    if (oauth) {
      return { apiKey: oauth.apiKey, source: "oauth" };
    }
    throw new AuthError("Invalid bearer token.");
  }

  if (config.allowUnauthenticated && config.cursorApiKey) {
    return { apiKey: config.cursorApiKey, source: "env" };
  }

  throw new AuthError(
    "Authentication required. Connect via OAuth, send Authorization: Bearer <cursor_api_key>, or set CONNECTOR_AUTH_TOKEN.",
  );
}

export function wwwAuthenticate(resourceMetadataUrl: string): string {
  return `Bearer realm="Claude Cursor Connector", resource_metadata="${resourceMetadataUrl}"`;
}
