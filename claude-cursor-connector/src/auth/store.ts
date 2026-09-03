import { createHmac, randomBytes, createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname } from "node:path";

export interface OAuthClient {
  clientId: string;
  clientName?: string;
  redirectUris: string[];
  createdAt: number;
}

export interface AuthCode {
  code: string;
  clientId: string;
  redirectUri: string;
  codeChallenge: string;
  apiKey: string;
  expiresAt: number;
  scope?: string;
}

export interface AccessToken {
  token: string;
  apiKey: string;
  clientId: string;
  expiresAt: number;
  refreshToken?: string;
}

export interface RefreshToken {
  token: string;
  apiKey: string;
  clientId: string;
  expiresAt: number;
}

export interface PendingAuthorization {
  id: string;
  clientId: string;
  redirectUri: string;
  state?: string;
  codeChallenge: string;
  scope?: string;
  expiresAt: number;
}

export interface OAuthSnapshot {
  clients: OAuthClient[];
  authCodes: AuthCode[];
  accessTokens: AccessToken[];
  refreshTokens: RefreshToken[];
  pending: PendingAuthorization[];
}

function now(): number {
  return Date.now();
}

export function randomToken(bytes = 32): string {
  return randomBytes(bytes).toString("base64url");
}

export function sha256Base64Url(value: string): string {
  return createHash("sha256").update(value).digest("base64url");
}

export function hmacSign(secret: string, value: string): string {
  return createHmac("sha256", secret).update(value).digest("base64url");
}

export class OAuthStore {
  clients = new Map<string, OAuthClient>();
  authCodes = new Map<string, AuthCode>();
  accessTokens = new Map<string, AccessToken>();
  refreshTokens = new Map<string, RefreshToken>();
  pending = new Map<string, PendingAuthorization>();
  private persistTimer: ReturnType<typeof setTimeout> | undefined;

  constructor(private readonly filePath?: string) {}

  static async load(filePath?: string): Promise<OAuthStore> {
    const store = new OAuthStore(filePath);
    if (!filePath) return store;
    try {
      const raw = await readFile(filePath, "utf8");
      const snapshot = JSON.parse(raw) as OAuthSnapshot;
      for (const client of snapshot.clients ?? []) store.clients.set(client.clientId, client);
      for (const code of snapshot.authCodes ?? []) store.authCodes.set(code.code, code);
      for (const token of snapshot.accessTokens ?? []) store.accessTokens.set(token.token, token);
      for (const token of snapshot.refreshTokens ?? []) store.refreshTokens.set(token.token, token);
      for (const pending of snapshot.pending ?? []) store.pending.set(pending.id, pending);
      store.gc();
    } catch {
      // missing file is fine
    }
    return store;
  }

  gc(): void {
    const t = now();
    for (const [k, v] of this.authCodes) if (v.expiresAt <= t) this.authCodes.delete(k);
    for (const [k, v] of this.accessTokens) if (v.expiresAt <= t) this.accessTokens.delete(k);
    for (const [k, v] of this.refreshTokens) if (v.expiresAt <= t) this.refreshTokens.delete(k);
    for (const [k, v] of this.pending) if (v.expiresAt <= t) this.pending.delete(k);
  }

  lookupAccess(token: string): AccessToken | undefined {
    this.gc();
    const found = this.accessTokens.get(token);
    if (!found || found.expiresAt <= now()) return undefined;
    return found;
  }

  private schedulePersist(): void {
    if (!this.filePath) return;
    if (this.persistTimer) return;
    this.persistTimer = setTimeout(() => {
      this.persistTimer = undefined;
      void this.persist();
    }, 50);
  }

  async persist(): Promise<void> {
    if (!this.filePath) return;
    this.gc();
    const snapshot: OAuthSnapshot = {
      clients: [...this.clients.values()],
      authCodes: [...this.authCodes.values()],
      accessTokens: [...this.accessTokens.values()],
      refreshTokens: [...this.refreshTokens.values()],
      pending: [...this.pending.values()],
    };
    await mkdir(dirname(this.filePath), { recursive: true }).catch(() => undefined);
    await writeFile(this.filePath, JSON.stringify(snapshot, null, 2));
  }

  touch(): void {
    this.schedulePersist();
  }
}
