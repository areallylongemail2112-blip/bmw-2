import { MAX_TOOL_RESULT_CHARS } from "./config.js";

export function jsonResult(value: unknown, extraNote?: string): string {
  const body = typeof value === "string" ? value : JSON.stringify(value, null, 2);
  if (body.length <= MAX_TOOL_RESULT_CHARS) {
    return extraNote ? `${extraNote}\n\n${body}` : body;
  }
  const truncated = body.slice(0, MAX_TOOL_RESULT_CHARS);
  const omitted = body.length - MAX_TOOL_RESULT_CHARS;
  const notice = `\n\n… truncated ${omitted} characters to stay under Claude's tool-result limit. Fetch a smaller slice (single agent, single run, or wait=false).`;
  return extraNote ? `${extraNote}\n\n${truncated}${notice}` : `${truncated}${notice}`;
}

export function agentDashboardUrl(agentId: string): string | undefined {
  if (agentId.startsWith("bc-")) {
    return `https://cursor.com/agents/${agentId}`;
  }
  return undefined;
}

export function isCloudAgentId(agentId: string): boolean {
  return agentId.startsWith("bc-");
}

export function clampTimeoutSeconds(value: number | undefined, fallback: number): number {
  const n = value ?? fallback;
  if (!Number.isFinite(n)) return fallback;
  return Math.min(240, Math.max(5, Math.round(n)));
}

export function asErrorMessage(err: unknown): { message: string; retryable?: boolean; name?: string } {
  if (err && typeof err === "object") {
    const obj = err as {
      message?: unknown;
      isRetryable?: unknown;
      name?: unknown;
      constructor?: { name?: string };
    };
    const message = typeof obj.message === "string" ? obj.message : String(err);
    return {
      message,
      retryable: obj.isRetryable === true,
      name: typeof obj.name === "string" ? obj.name : obj.constructor?.name,
    };
  }
  return { message: String(err) };
}
