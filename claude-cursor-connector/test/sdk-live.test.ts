import { describe, expect, it } from "vitest";
import { AuthenticationError, Cursor, CursorAgentError } from "@cursor/sdk";

describe("live @cursor/sdk", () => {
  it("rejects a bogus API key with AuthenticationError", async () => {
    await expect(Cursor.me({ apiKey: "cursor_invalidkeyxx" })).rejects.toSatisfy((err: unknown) => {
      return err instanceof AuthenticationError && err instanceof CursorAgentError && err.isRetryable === false;
    });
  });
});
