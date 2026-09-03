#!/usr/bin/env node
import { loadConfig } from "./config.js";
import { OAuthStore } from "./auth/store.js";
import { SdkCursorClient } from "./cursor/sdk-client.js";
import { listenHttp } from "./http/app.js";
import { runStdio } from "./stdio.js";

async function main(): Promise<void> {
  const args = new Set(process.argv.slice(2));
  if (args.has("--stdio") && args.has("--http")) {
    console.error("Pass either --stdio or --http, not both.");
    process.exit(1);
  }
  const stdio = args.has("--stdio");
  const config = loadConfig(stdio ? { allowUnauthenticated: true } : {});
  const cursor = new SdkCursorClient();

  if (stdio) {
    await runStdio({ config, cursor });
    return;
  }

  const store = await OAuthStore.load(config.oauthStorePath);
  const { close } = await listenHttp({ config, cursor, store });

  const shutdown = async () => {
    await close();
    await store.persist();
    process.exit(0);
  };
  process.on("SIGINT", () => void shutdown());
  process.on("SIGTERM", () => void shutdown());
}

main().catch((err: unknown) => {
  console.error(err);
  process.exit(1);
});
