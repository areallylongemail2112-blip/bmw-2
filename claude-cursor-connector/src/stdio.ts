import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import type { ConnectorConfig } from "./config.js";
import type { CursorClient } from "./types.js";
import { createConnectorServer } from "./mcp/create-server.js";

export async function runStdio(options: {
  config: ConnectorConfig;
  cursor: CursorClient;
}): Promise<void> {
  const apiKey = options.config.cursorApiKey;
  if (!apiKey) {
    console.error("CURSOR_API_KEY is required for stdio mode.");
    process.exit(1);
  }
  const defaultRuntime = process.env.CURSOR_DEFAULT_RUNTIME === "local" ? "local" : "cloud";
  const server = createConnectorServer({
    auth: { apiKey, source: "env" },
    cursor: options.cursor,
    defaultRuntime,
  });
  const transport = new StdioServerTransport();
  await server.connect(transport);
}
