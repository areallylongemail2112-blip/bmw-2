# Claude Cursor Connector

A [Claude custom connector](https://claude.com/docs/connectors/custom/remote-mcp): a remote MCP server that lets Claude launch and drive [Cursor agents](https://cursor.com/docs/api/sdk/typescript) via `@cursor/sdk`.

Claude stays the conversational front-end. Cursor is the coding agent — it clones the repo (or uses a local working tree), edits files, runs tests, and can open pull requests.

```
Claude.ai / Claude Code / Claude Desktop
        │  MCP (Streamable HTTP or stdio)
        ▼
claude-cursor-connector
        │  @cursor/sdk
        ▼
Cursor cloud agent (bc-…)  or  local agent
```

Claude.ai and Claude Desktop time out MCP tools at **5 minutes**. Cloud Cursor runs often take longer, so this connector **starts cloud agents without waiting** and returns `agentId` / `runId` / dashboard URL. Claude then polls `cursor_get_agent` or `cursor_wait_for_run`.

## Add it to Claude.ai / Claude Desktop / Cowork

The connector must be reachable on the public internet (Anthropic connects from `160.79.104.0/21`, not from your laptop).

1. Deploy this server with HTTPS. The MCP URL is `https://your-host/mcp`.
2. Set `PUBLIC_BASE_URL` to that origin (or to the full `/mcp` URL).
3. In Claude: **Customize → Connectors → Add custom connector**.
4. Name: `Cursor`. URL: `https://your-host/mcp`.
5. Authentication:
   - **OAuth (recommended):** leave auth as the default OAuth flow. Claude opens this connector's consent page; paste a Cursor API key minted at [cursor.com/dashboard/cloud-agents](https://cursor.com/dashboard/cloud-agents). The key never goes to Anthropic — Claude only stores an OAuth access token.
   - **Request headers (beta):** Authentication = None, then add `Authorization` = `Bearer cursor_…` (include the `Bearer ` prefix) or `x-api-key` = `cursor_…`.
6. Enable the connector for the chat with **+ → Connectors**.

If you host a shared team key on the server instead:

```bash
CURSOR_API_KEY=cursor_…
CONNECTOR_AUTH_TOKEN=a-long-random-secret
```

Then tell Claude to send `Authorization: Bearer a-long-random-secret` (or configure that as a request header). The server maps the secret to `CURSOR_API_KEY`.

## Add it to Claude Code (stdio, local machine)

```bash
cd claude-cursor-connector
cp .env.example .env   # set CURSOR_API_KEY
npm install
npx tsx src/index.ts --stdio
```

Or register it:

```bash
claude mcp add cursor-agent --env CURSOR_API_KEY=cursor_… -- npx tsx /path/to/claude-cursor-connector/src/index.ts --stdio
```

Stdio can use `runtime=local` against a checkout on this machine. Claude.ai custom connectors run in Anthropic's cloud, so they should use `runtime=cloud`.

## Run locally (HTTP)

Requires Node.js 22.13+.

```bash
cd claude-cursor-connector
npm install
export CURSOR_API_KEY=cursor_…
export PUBLIC_BASE_URL=http://127.0.0.1:8787
npm run dev
```

Health check: `GET http://127.0.0.1:8787/health`.

Claude.ai cannot reach `127.0.0.1`. Tunnel it (`cloudflared`, `ngrok`) and set `PUBLIC_BASE_URL` to the public HTTPS origin before adding the connector.

## Docker

```bash
docker build -t claude-cursor-connector .
docker run --rm -p 8787:8787 \
  -e CURSOR_API_KEY \
  -e PUBLIC_BASE_URL=https://cursor-connector.example.com \
  -e OAUTH_STORE_PATH=/data/oauth-store.json \
  -v connector-data:/data \
  claude-cursor-connector
```

## Tools Claude gets

| Tool | Purpose |
| --- | --- |
| `cursor_whoami` | Confirm which Cursor account the key belongs to |
| `cursor_list_models` | Discover model ids (`composer-2.5`, `auto-smart`, …) |
| `cursor_list_repositories` | GitHub repos connected to that Cursor account |
| `cursor_launch_agent` | Create an agent and send the first prompt |
| `cursor_follow_up` | Resume `agent_id` and send another prompt |
| `cursor_get_agent` / `cursor_list_agents` | Status / list |
| `cursor_list_runs` / `cursor_get_run` / `cursor_wait_for_run` | Poll a run |
| `cursor_cancel_run` | Cancel an in-flight run |
| `cursor_list_artifacts` / `cursor_get_usage` | Cloud artifacts and billed usage |
| `cursor_archive_agent` / `cursor_unarchive_agent` / `cursor_delete_agent` | Lifecycle |

Prompts: `implement_with_cursor`, `review_with_cursor`.

## Environment

| Variable | Meaning |
| --- | --- |
| `CURSOR_API_KEY` | Server-side Cursor key (optional if every user pastes their own via OAuth or Bearer) |
| `PUBLIC_BASE_URL` | Origin Claude uses, e.g. `https://cursor-connector.example.com` |
| `HOST` / `PORT` | Bind address (default `0.0.0.0:8787`) |
| `CONNECTOR_AUTH_TOKEN` | Shared secret that maps to `CURSOR_API_KEY` |
| `ALLOW_UNAUTHENTICATED` | Use `CURSOR_API_KEY` with no client credential (private networks only) |
| `OAUTH_STORE_PATH` | Persist OAuth clients/tokens across restarts |
| `OAUTH_ACCESS_TTL_SECONDS` | Access token lifetime (default 8 hours) |
| `OAUTH_REFRESH_TTL_SECONDS` | Refresh token lifetime (default 30 days) |
| `CURSOR_DEFAULT_RUNTIME` | `cloud` (default) or `local` for stdio |

## Security notes

- Treat Cursor API keys like passwords. Prefer OAuth or per-user Bearer keys over a shared unauthenticated HTTP endpoint.
- Cloud agents run with the GitHub access of the Cursor account behind the key. Connect GitHub in the Cursor dashboard or cloud launches fail with `ERROR_GITHUB_NO_USER_CREDENTIALS`.
- `cursor_delete_agent` is permanent. `cursor_launch_agent` with `auto_create_pr=true` opens real pull requests.
- This connector does not log API keys. OAuth access tokens are stored in `OAUTH_STORE_PATH` if set.

## Development

```bash
npm test
npm run build
```
