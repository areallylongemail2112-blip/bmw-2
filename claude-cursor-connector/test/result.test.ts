import { describe, expect, it } from "vitest";
import { agentDashboardUrl, clampTimeoutSeconds, jsonResult } from "../src/result.js";
import { looksLikeCursorApiKey } from "../src/config.js";

describe("jsonResult", () => {
  it("pretty-prints objects", () => {
    expect(jsonResult({ ok: true })).toContain('"ok": true');
  });

  it("truncates huge payloads", () => {
    const huge = "x".repeat(200_000);
    const out = jsonResult(huge);
    expect(out.length).toBeLessThan(200_000);
    expect(out).toContain("truncated");
  });
});

describe("helpers", () => {
  it("builds dashboard URLs only for cloud agents", () => {
    expect(agentDashboardUrl("bc-abc")).toBe("https://cursor.com/agents/bc-abc");
    expect(agentDashboardUrl("agent-local")).toBeUndefined();
  });

  it("clamps wait timeouts into the Claude-safe window", () => {
    expect(clampTimeoutSeconds(1, 90)).toBe(5);
    expect(clampTimeoutSeconds(999, 90)).toBe(240);
    expect(clampTimeoutSeconds(undefined, 90)).toBe(90);
  });

  it("recognizes Cursor API keys", () => {
    expect(looksLikeCursorApiKey("cursor_abcdefghijk")).toBe(true);
    expect(looksLikeCursorApiKey("sk-ant-123")).toBe(false);
    expect(looksLikeCursorApiKey("cursor_short")).toBe(false);
  });
});
