export const DEFAULT_MODEL = "composer-2.5";
export const MAX_TOOL_RESULT_CHARS = 140_000;
export const CLAUDE_TOOL_TIMEOUT_SECONDS = 300;
export const DEFAULT_WAIT_TIMEOUT_SECONDS = 90;
export const MAX_WAIT_TIMEOUT_SECONDS = 240;
export const CONNECTOR_NAME = "claude-cursor-connector";
export const CONNECTOR_VERSION = "1.0.0";

export interface ConnectorConfig {
  cursorApiKey?: string;
  connectorAuthToken?: string;
  allowUnauthenticated: boolean;
  publicBaseUrl: string;
  host: string;
  port: number;
  oauthStorePath?: string;
  accessTtlSeconds: number;
  refreshTtlSeconds: number;
}

function envFlag(name: string, fallback: boolean): boolean {
  const raw = process.env[name];
  if (raw === undefined || raw === "") return fallback;
  return ["1", "true", "yes", "on"].includes(raw.toLowerCase());
}

function envNumber(name: string, fallback: number): number {
  const raw = process.env[name];
  if (!raw) return fallback;
  const n = Number(raw);
  return Number.isFinite(n) ? n : fallback;
}

export function loadConfig(overrides: Partial<ConnectorConfig> = {}): ConnectorConfig {
  const port = overrides.port ?? envNumber("PORT", 8787);
  const publicBaseUrl = (
    overrides.publicBaseUrl ??
    process.env.PUBLIC_BASE_URL ??
    `http://127.0.0.1:${port}`
  ).replace(/\/$/, "");

  const base: ConnectorConfig = {
    cursorApiKey: process.env.CURSOR_API_KEY?.trim() || undefined,
    connectorAuthToken: process.env.CONNECTOR_AUTH_TOKEN?.trim() || undefined,
    allowUnauthenticated: envFlag("ALLOW_UNAUTHENTICATED", false),
    publicBaseUrl,
    host: process.env.HOST?.trim() || "0.0.0.0",
    port,
    oauthStorePath: process.env.OAUTH_STORE_PATH?.trim() || undefined,
    accessTtlSeconds: envNumber("OAUTH_ACCESS_TTL_SECONDS", 28_800),
    refreshTtlSeconds: envNumber("OAUTH_REFRESH_TTL_SECONDS", 2_592_000),
  };
  return { ...base, ...overrides, publicBaseUrl, port };
}

export function mcpResourceUrl(config: ConnectorConfig): string {
  const base = config.publicBaseUrl;
  return base.endsWith("/mcp") ? base : `${base}/mcp`;
}

export function originUrl(config: ConnectorConfig): string {
  return config.publicBaseUrl.replace(/\/mcp\/?$/, "");
}

export function looksLikeCursorApiKey(value: string): boolean {
  return /^cursor_[A-Za-z0-9_-]{8,}$/.test(value.trim());
}
