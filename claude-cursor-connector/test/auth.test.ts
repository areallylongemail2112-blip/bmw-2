import { IncomingMessage } from "node:http";
import { describe, expect, it } from "vitest";
import { AuthError, extractBearer, resolveAuth } from "../src/auth/resolve.js";
import { OAuthStore } from "../src/auth/store.js";
import { loadConfig } from "../src/config.js";

function fakeReq(headers: Record<string, string>): IncomingMessage {
  return { headers } as IncomingMessage;
}

describe("extractBearer", () => {
  it("reads Authorization Bearer", () => {
    expect(extractBearer(fakeReq({ authorization: "Bearer abc" }))).toBe("abc");
  });
  it("reads x-api-key", () => {
    expect(extractBearer(fakeReq({ "x-api-key": "k1" }))).toBe("k1");
  });
});

describe("resolveAuth", () => {
  it("accepts a Cursor API key directly", () => {
    const config = loadConfig({ cursorApiKey: undefined, allowUnauthenticated: false });
    const auth = resolveAuth("cursor_abcdefghijk", config);
    expect(auth.source).toBe("cursor-api-key");
    expect(auth.apiKey).toBe("cursor_abcdefghijk");
  });

  it("maps CONNECTOR_AUTH_TOKEN onto CURSOR_API_KEY", () => {
    const config = loadConfig({
      cursorApiKey: "cursor_serverkeyxxxx",
      connectorAuthToken: "shared-secret",
      allowUnauthenticated: false,
    });
    const auth = resolveAuth("shared-secret", config);
    expect(auth.source).toBe("connector-token");
    expect(auth.apiKey).toBe("cursor_serverkeyxxxx");
  });

  it("resolves OAuth access tokens", () => {
    const store = new OAuthStore();
    store.accessTokens.set("tok_abc", {
      token: "tok_abc",
      apiKey: "cursor_fromoauthxxx",
      clientId: "c1",
      expiresAt: Date.now() + 60_000,
    });
    const config = loadConfig({ allowUnauthenticated: false });
    const auth = resolveAuth("tok_abc", config, store);
    expect(auth.source).toBe("oauth");
    expect(auth.apiKey).toBe("cursor_fromoauthxxx");
  });

  it("uses env key when unauthenticated access is allowed", () => {
    const config = loadConfig({
      cursorApiKey: "cursor_envkeyxxxxxxx",
      allowUnauthenticated: true,
    });
    const auth = resolveAuth(undefined, config);
    expect(auth.source).toBe("env");
  });

  it("rejects missing credentials", () => {
    const config = loadConfig({ allowUnauthenticated: false, cursorApiKey: undefined });
    expect(() => resolveAuth(undefined, config)).toThrow(AuthError);
  });
});
