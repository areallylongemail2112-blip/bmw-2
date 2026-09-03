import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { describe, expect, it } from "vitest";
import { createConnectorServer } from "../src/mcp/create-server.js";
import { FakeCursorClient } from "./fake-cursor.js";

async function connect(cursor = new FakeCursorClient()) {
  const server = createConnectorServer({
    auth: { apiKey: "cursor_abcdefghijk", source: "cursor-api-key" },
    cursor,
    defaultRuntime: "cloud",
  });
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  const client = new Client({ name: "test-client", version: "1.0.0" });
  await Promise.all([server.connect(serverTransport), client.connect(clientTransport)]);
  return { client, cursor };
}

describe("MCP tools", () => {
  it("lists the Cursor agent tools and instructions", async () => {
    const { client } = await connect();
    const tools = await client.listTools();
    const names = tools.tools.map((t) => t.name);
    expect(names).toEqual(
      expect.arrayContaining([
        "cursor_whoami",
        "cursor_launch_agent",
        "cursor_follow_up",
        "cursor_get_agent",
        "cursor_wait_for_run",
        "cursor_cancel_run",
      ]),
    );
    const prompts = await client.listPrompts();
    expect(prompts.prompts.map((p) => p.name)).toContain("implement_with_cursor");
  });

  it("returns account identity", async () => {
    const { client } = await connect();
    const result = await client.callTool({ name: "cursor_whoami", arguments: {} });
    const text = (result.content as { type: string; text: string }[])[0].text;
    expect(text).toContain("dev@example.com");
    expect(result.isError).toBeFalsy();
  });

  it("surfaces Cursor API errors as tool errors", async () => {
    const cursor = new FakeCursorClient();
    cursor.failWhoami = true;
    const { client } = await connect(cursor);
    const result = await client.callTool({ name: "cursor_whoami", arguments: {} });
    expect(result.isError).toBe(true);
    const text = (result.content as { type: string; text: string }[])[0].text;
    expect(text).toContain("Invalid API key");
  });

  it("launches a cloud agent without waiting", async () => {
    const { client, cursor } = await connect();
    const result = await client.callTool({
      name: "cursor_launch_agent",
      arguments: {
        prompt: "Add a README",
        repository_url: "https://github.com/example/repo",
        auto_create_pr: true,
      },
    });
    const text = (result.content as { type: string; text: string }[])[0].text;
    expect(text).toContain("bc-launched-1");
    expect(cursor.launched[0]?.runtime).toBe("cloud");
    expect(cursor.launched[0]?.autoCreatePr).toBe(true);
    expect(cursor.launched[0]?.wait).toBeUndefined();
  });

  it("follows up, waits, and cancels", async () => {
    const { client } = await connect();
    const follow = await client.callTool({
      name: "cursor_follow_up",
      arguments: { agent_id: "bc-test-1", prompt: "also add tests", wait: false },
    });
    expect((follow.content as { text: string }[])[0].text).toContain("run-follow-1");

    const waited = await client.callTool({
      name: "cursor_wait_for_run",
      arguments: { run_id: "run-1", agent_id: "bc-test-1" },
    });
    expect((waited.content as { text: string }[])[0].text).toContain("all done");

    const cancelled = await client.callTool({
      name: "cursor_cancel_run",
      arguments: { run_id: "run-1", agent_id: "bc-test-1" },
    });
    expect((cancelled.content as { text: string }[])[0].text).toContain('"cancelled": true');
  });
});
