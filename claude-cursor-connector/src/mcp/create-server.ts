import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { CONNECTOR_NAME, CONNECTOR_VERSION, DEFAULT_MODEL } from "../config.js";
import { asErrorMessage, jsonResult } from "../result.js";
import type { AgentRuntime, AuthContext, CursorClient } from "../types.js";

const INSTRUCTIONS = `You are connected to Cursor via the Claude Cursor Connector.
Use these tools to delegate software-engineering work to a Cursor agent.

Cloud agents (runtime=cloud) clone a GitHub repo on a Cursor VM, can open pull requests, and keep running after this tool call returns. Claude.ai / Claude Desktop time out MCP tools at 5 minutes — always launch cloud agents with wait=false, then poll cursor_get_agent or cursor_wait_for_run. The dashboard URL is https://cursor.com/agents/<agentId>.

Local agents (runtime=local) only work when this connector is running on the machine that has the repo (stdio / Claude Code). Prefer cloud when talking to Claude.ai.

Typical flow:
1. cursor_list_repositories (or ask the user for a GitHub HTTPS URL)
2. cursor_launch_agent with the task, repository_url, auto_create_pr as requested
3. cursor_wait_for_run or cursor_get_agent until status is finished/error/cancelled
4. cursor_follow_up on the same agent_id for additional turns
`;

function ok(data: unknown) {
  return { content: [{ type: "text" as const, text: jsonResult(data) }] };
}

function fail(err: unknown) {
  const parsed = asErrorMessage(err);
  return {
    isError: true as const,
    content: [{ type: "text" as const, text: jsonResult({ error: parsed }) }],
  };
}

const metadataSchema = z.record(z.string(), z.string()).optional();

export function createConnectorServer(options: {
  auth: AuthContext;
  cursor: CursorClient;
  defaultRuntime: AgentRuntime;
}): McpServer {
  const { auth, cursor, defaultRuntime } = options;
  const apiKey = auth.apiKey;

  const server = new McpServer(
    { name: CONNECTOR_NAME, version: CONNECTOR_VERSION },
    { instructions: INSTRUCTIONS },
  );

  server.registerTool(
    "cursor_whoami",
    {
      title: "Cursor account",
      description: "Identify the Cursor API key currently used by this connector (name, email).",
      inputSchema: z.object({}),
      annotations: { readOnlyHint: true, idempotentHint: true, openWorldHint: true },
    },
    async () => {
      try {
        return ok({ authSource: auth.source, account: await cursor.whoami(apiKey) });
      } catch (err) {
        return fail(err);
      }
    },
  );

  server.registerTool(
    "cursor_list_models",
    {
      title: "List Cursor models",
      description:
        "List model ids available to this Cursor API key. Use an id from this list as model when launching an agent. composer-2.5 is the usual default; auto-smart is Cursor Router when enabled.",
      inputSchema: z.object({}),
      annotations: { readOnlyHint: true, idempotentHint: true, openWorldHint: true },
    },
    async () => {
      try {
        return ok(await cursor.listModels(apiKey));
      } catch (err) {
        return fail(err);
      }
    },
  );

  server.registerTool(
    "cursor_list_repositories",
    {
      title: "List connected GitHub repos",
      description:
        "List GitHub repositories the Cursor account has connected. Use a url from this list as repository_url for cursor_launch_agent.",
      inputSchema: z.object({}),
      annotations: { readOnlyHint: true, idempotentHint: true, openWorldHint: true },
    },
    async () => {
      try {
        return ok(await cursor.listRepositories(apiKey));
      } catch (err) {
        return fail(err);
      }
    },
  );

  server.registerTool(
    "cursor_launch_agent",
    {
      title: "Launch a Cursor agent",
      description:
        "Start a Cursor agent and send it a prompt. For Claude.ai always use runtime=cloud and wait=false (default for cloud) so the tool returns before the 5 minute connector timeout. The agent keeps running on Cursor; poll with cursor_get_agent or cursor_wait_for_run. Cloud agents can open a PR when auto_create_pr is true. Omit repository_url for a no-repo cloud agent (research-only). Local runtime requires this connector to be running next to the repo (stdio).",
      inputSchema: z.object({
        prompt: z.string().min(1).describe("Task for the Cursor agent."),
        runtime: z
          .enum(["cloud", "local"])
          .optional()
          .describe(`cloud or local. Defaults to ${defaultRuntime}.`),
        repository_url: z
          .string()
          .optional()
          .describe("GitHub HTTPS repo URL to clone for a cloud agent, e.g. https://github.com/org/repo."),
        starting_ref: z.string().optional().describe("Branch, tag, or SHA to start from. Defaults to the repo default branch."),
        pr_url: z.string().optional().describe("Attach the agent to an existing pull request."),
        auto_create_pr: z.boolean().optional().describe("Open a PR when the cloud run finishes. Default false."),
        skip_reviewer_request: z
          .boolean()
          .optional()
          .describe("Skip requesting the caller as a PR reviewer. Default true."),
        work_on_current_branch: z
          .boolean()
          .optional()
          .describe("Push to the existing branch instead of creating a new one. Default false."),
        model: z.string().optional().describe(`Model id from cursor_list_models. Default ${DEFAULT_MODEL}.`),
        name: z.string().optional().describe("Human-readable agent name shown in Cursor."),
        wait: z
          .boolean()
          .optional()
          .describe("Wait for the run to finish. Default false for cloud, true for local. Prefer false on Claude.ai."),
        timeout_seconds: z
          .number()
          .optional()
          .describe("Max seconds to wait if wait=true. Capped at 240. Does not cancel the run on timeout."),
        cwd: z.string().optional().describe("Working directory for local agents only."),
        metadata: metadataSchema.describe("Optional string tags stored on the cloud agent."),
      }),
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: true },
    },
    async (args) => {
      try {
        const result = await cursor.launchAgent(apiKey, {
          prompt: args.prompt,
          runtime: args.runtime ?? defaultRuntime,
          repositoryUrl: args.repository_url,
          startingRef: args.starting_ref,
          prUrl: args.pr_url,
          autoCreatePr: args.auto_create_pr,
          skipReviewerRequest: args.skip_reviewer_request,
          workOnCurrentBranch: args.work_on_current_branch,
          model: args.model,
          name: args.name,
          wait: args.wait,
          timeoutSeconds: args.timeout_seconds,
          cwd: args.cwd,
          metadata: args.metadata as Record<string, string> | undefined,
        });
        return ok(result);
      } catch (err) {
        return fail(err);
      }
    },
  );

  server.registerTool(
    "cursor_follow_up",
    {
      title: "Send a follow-up to a Cursor agent",
      description:
        "Resume an existing Cursor agent by agent_id and send another prompt. Cloud IDs start with bc-. Use wait=false on Claude.ai, then poll.",
      inputSchema: z.object({
        agent_id: z.string().min(1).describe("Agent id from a previous launch (bc-… for cloud)."),
        prompt: z.string().min(1).describe("Follow-up instructions."),
        wait: z.boolean().optional(),
        timeout_seconds: z.number().optional(),
        model: z.string().optional(),
        cwd: z.string().optional().describe("Required for local agents if cwd is not the connector process cwd."),
      }),
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: true },
    },
    async (args) => {
      try {
        return ok(
          await cursor.followUp(apiKey, {
            agentId: args.agent_id,
            prompt: args.prompt,
            wait: args.wait,
            timeoutSeconds: args.timeout_seconds,
            model: args.model,
            cwd: args.cwd,
          }),
        );
      } catch (err) {
        return fail(err);
      }
    },
  );

  server.registerTool(
    "cursor_get_agent",
    {
      title: "Get Cursor agent status",
      description: "Fetch metadata and status for a Cursor agent. Use this to poll a cloud agent after launch.",
      inputSchema: z.object({
        agent_id: z.string().min(1),
        cwd: z.string().optional(),
      }),
      annotations: { readOnlyHint: true, idempotentHint: true, openWorldHint: true },
    },
    async (args) => {
      try {
        return ok(await cursor.getAgent(apiKey, args.agent_id, args.cwd));
      } catch (err) {
        return fail(err);
      }
    },
  );

  server.registerTool(
    "cursor_list_agents",
    {
      title: "List Cursor agents",
      description: "List recent Cursor agents for this API key.",
      inputSchema: z.object({
        runtime: z.enum(["cloud", "local"]).optional(),
        include_archived: z.boolean().optional(),
        limit: z.number().optional(),
        cwd: z.string().optional(),
      }),
      annotations: { readOnlyHint: true, idempotentHint: true, openWorldHint: true },
    },
    async (args) => {
      try {
        return ok(
          await cursor.listAgents(apiKey, {
            runtime: args.runtime ?? defaultRuntime,
            includeArchived: args.include_archived,
            limit: args.limit,
            cwd: args.cwd,
          }),
        );
      } catch (err) {
        return fail(err);
      }
    },
  );

  server.registerTool(
    "cursor_list_runs",
    {
      title: "List runs on a Cursor agent",
      description: "List prompt runs for one agent.",
      inputSchema: z.object({
        agent_id: z.string().min(1),
        limit: z.number().optional(),
        cwd: z.string().optional(),
      }),
      annotations: { readOnlyHint: true, idempotentHint: true, openWorldHint: true },
    },
    async (args) => {
      try {
        return ok(await cursor.listRuns(apiKey, args.agent_id, { limit: args.limit, cwd: args.cwd }));
      } catch (err) {
        return fail(err);
      }
    },
  );

  server.registerTool(
    "cursor_get_run",
    {
      title: "Get a Cursor run",
      description: "Fetch one run by id. Cloud runs also need agent_id. Set include_conversation=true for a transcript snippet.",
      inputSchema: z.object({
        run_id: z.string().min(1),
        agent_id: z.string().optional(),
        include_conversation: z.boolean().optional(),
        cwd: z.string().optional(),
      }),
      annotations: { readOnlyHint: true, idempotentHint: true, openWorldHint: true },
    },
    async (args) => {
      try {
        return ok(
          await cursor.getRun(apiKey, args.run_id, {
            agentId: args.agent_id,
            includeConversation: args.include_conversation,
            cwd: args.cwd,
          }),
        );
      } catch (err) {
        return fail(err);
      }
    },
  );

  server.registerTool(
    "cursor_wait_for_run",
    {
      title: "Wait for a Cursor run",
      description:
        "Wait up to timeout_seconds (max 240) for a run to finish. Returns timedOut=true if still running so you can call again. Prefer this over blocking cursor_launch_agent on Claude.ai.",
      inputSchema: z.object({
        run_id: z.string().min(1),
        agent_id: z.string().optional().describe("Required for cloud runs."),
        timeout_seconds: z.number().optional(),
        cwd: z.string().optional(),
      }),
      annotations: { readOnlyHint: true, idempotentHint: false, openWorldHint: true },
    },
    async (args) => {
      try {
        return ok(
          await cursor.waitForRun(apiKey, args.run_id, {
            agentId: args.agent_id,
            timeoutSeconds: args.timeout_seconds,
            cwd: args.cwd,
          }),
        );
      } catch (err) {
        return fail(err);
      }
    },
  );

  server.registerTool(
    "cursor_cancel_run",
    {
      title: "Cancel a Cursor run",
      description: "Cancel an in-progress Cursor run. Cloud runs need agent_id.",
      inputSchema: z.object({
        run_id: z.string().min(1),
        agent_id: z.string().optional(),
        cwd: z.string().optional(),
      }),
      annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: true, openWorldHint: true },
    },
    async (args) => {
      try {
        return ok(
          await cursor.cancelRun(apiKey, args.run_id, { agentId: args.agent_id, cwd: args.cwd }),
        );
      } catch (err) {
        return fail(err);
      }
    },
  );

  server.registerTool(
    "cursor_list_artifacts",
    {
      title: "List Cursor artifacts",
      description: "List artifact files produced by a cloud agent (test reports, generated assets). Local agents return an empty list.",
      inputSchema: z.object({
        agent_id: z.string().min(1),
      }),
      annotations: { readOnlyHint: true, idempotentHint: true, openWorldHint: true },
    },
    async (args) => {
      try {
        return ok(await cursor.listArtifacts(apiKey, args.agent_id));
      } catch (err) {
        return fail(err);
      }
    },
  );

  server.registerTool(
    "cursor_get_usage",
    {
      title: "Cursor agent usage",
      description: "Fetch billed token usage and dollar cost for an agent, optionally for one run.",
      inputSchema: z.object({
        agent_id: z.string().min(1),
        run_id: z.string().optional(),
      }),
      annotations: { readOnlyHint: true, idempotentHint: true, openWorldHint: true },
    },
    async (args) => {
      try {
        return ok(await cursor.getUsage(apiKey, args.agent_id, args.run_id));
      } catch (err) {
        return fail(err);
      }
    },
  );

  server.registerTool(
    "cursor_archive_agent",
    {
      title: "Archive a Cursor agent",
      description: "Hide a cloud agent from the default list. History stays readable. Use cursor_list_agents with include_archived=true to find it again.",
      inputSchema: z.object({
        agent_id: z.string().min(1),
      }),
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: true },
    },
    async (args) => {
      try {
        return ok(await cursor.archiveAgent(apiKey, args.agent_id));
      } catch (err) {
        return fail(err);
      }
    },
  );

  server.registerTool(
    "cursor_unarchive_agent",
    {
      title: "Unarchive a Cursor agent",
      description: "Restore an archived cloud agent.",
      inputSchema: z.object({
        agent_id: z.string().min(1),
      }),
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: true },
    },
    async (args) => {
      try {
        return ok(await cursor.unarchiveAgent(apiKey, args.agent_id));
      } catch (err) {
        return fail(err);
      }
    },
  );

  server.registerTool(
    "cursor_delete_agent",
    {
      title: "Delete a Cursor agent",
      description: "Permanently delete a Cursor agent. This cannot be undone.",
      inputSchema: z.object({
        agent_id: z.string().min(1),
      }),
      annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: true, openWorldHint: true },
    },
    async (args) => {
      try {
        return ok(await cursor.deleteAgent(apiKey, args.agent_id));
      } catch (err) {
        return fail(err);
      }
    },
  );

  server.registerPrompt(
    "implement_with_cursor",
    {
      description: "Delegate an implementation task to a Cursor cloud agent and poll until it finishes.",
      argsSchema: {
        task: z.string().describe("What to implement."),
        repository_url: z.string().describe("GitHub HTTPS URL of the repo."),
        auto_create_pr: z.string().optional().describe("true or false. Default true."),
      },
    },
    ({ task, repository_url, auto_create_pr }) => ({
      messages: [
        {
          role: "user",
          content: {
            type: "text",
            text: `Use the Cursor connector to implement this in ${repository_url}.

Task:
${task}

Steps:
1. Optionally call cursor_list_models and cursor_whoami.
2. Call cursor_launch_agent with runtime=cloud, wait=false, repository_url=${repository_url}, auto_create_pr=${auto_create_pr ?? "true"}, skip_reviewer_request=true, and a detailed prompt that includes acceptance criteria.
3. Poll cursor_get_agent / cursor_wait_for_run until the run is finished, error, or cancelled. Do not launch a second agent for the same task unless the first failed to start.
4. Summarize the result, PR URL if any, and remaining risks for me.`,
          },
        },
      ],
    }),
  );

  server.registerPrompt(
    "review_with_cursor",
    {
      description: "Have a Cursor cloud agent review a pull request.",
      argsSchema: {
        pr_url: z.string().describe("Pull request URL."),
        repository_url: z.string().describe("GitHub HTTPS URL of the repo."),
      },
    },
    ({ pr_url, repository_url }) => ({
      messages: [
        {
          role: "user",
          content: {
            type: "text",
            text: `Use the Cursor connector to review ${pr_url} in ${repository_url}.
Launch a cloud agent with wait=false, pr_url set, auto_create_pr=false. Ask it to review correctness, security, and regressions, and to post inline review comments for concrete issues. Poll until finished, then summarize findings.`,
          },
        },
      ],
    }),
  );

  server.registerResource(
    "cursor-connector-guide",
    "cursor://guide",
    {
      title: "How to use the Cursor connector",
      description: "Operating notes for delegating work to Cursor agents from Claude.",
      mimeType: "text/plain",
    },
    async () => ({
      contents: [{ uri: "cursor://guide", text: INSTRUCTIONS, mimeType: "text/plain" }],
    }),
  );

  return server;
}
