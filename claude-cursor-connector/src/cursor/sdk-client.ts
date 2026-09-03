import {
  Agent,
  Cursor,
  CursorAgentError,
  type AgentOptions,
  type Run,
  type SDKAgent,
} from "@cursor/sdk";
import { DEFAULT_MODEL } from "../config.js";
import { agentDashboardUrl, asErrorMessage, clampTimeoutSeconds, isCloudAgentId } from "../result.js";
import type {
  AgentRuntime,
  CursorClient,
  CursorWhoami,
  FollowUpInput,
  LaunchAgentInput,
  SerializedAgent,
  SerializedRun,
} from "../types.js";

const DEFAULT_WAIT_CLOUD = 90;
const DEFAULT_WAIT_LOCAL = 180;

function serializeAgent(info: {
  agentId: string;
  name?: string;
  summary?: string;
  status?: string;
  runtime?: string;
  repos?: string[];
  lastModified?: number;
  createdAt?: number;
  archived?: boolean;
}): SerializedAgent {
  return {
    agentId: info.agentId,
    name: info.name,
    summary: info.summary,
    status: info.status,
    runtime: info.runtime,
    repos: info.repos,
    lastModified: info.lastModified,
    createdAt: info.createdAt,
    archived: info.archived,
    url: agentDashboardUrl(info.agentId),
  };
}

function serializeRun(run: {
  id: string;
  agentId: string;
  status: string;
  result?: string;
  error?: { message: string; code?: string };
  durationMs?: number;
  model?: unknown;
  git?: unknown;
  usage?: unknown;
  timedOut?: boolean;
  note?: string;
}): SerializedRun {
  return {
    agentId: run.agentId,
    runId: run.id,
    status: run.status,
    result: run.result,
    error: run.error,
    durationMs: run.durationMs,
    model: run.model,
    git: run.git,
    usage: run.usage,
    url: agentDashboardUrl(run.agentId),
    timedOut: run.timedOut,
    note: run.note,
  };
}

async function waitWithTimeout(
  run: Run,
  timeoutSeconds: number,
): Promise<{ timedOut: boolean; result?: Awaited<ReturnType<Run["wait"]>> }> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<{ timedOut: true }>((resolve) => {
    timer = setTimeout(() => resolve({ timedOut: true }), timeoutSeconds * 1000);
  });
  try {
    const raced = await Promise.race([
      run.wait().then((result) => ({ timedOut: false as const, result })),
      timeout,
    ]);
    if (raced.timedOut) {
      return {
        timedOut: true,
      };
    }
    return raced;
  } finally {
    if (timer) clearTimeout(timer);
  }
}

function launchNote(runtime: AgentRuntime, waited: boolean, timedOut: boolean): string {
  if (timedOut) {
    return "The Cursor run is still in progress. Claude's connector timeout is 5 minutes, so poll with cursor_get_agent or cursor_wait_for_run instead of launching again.";
  }
  if (!waited) {
    return runtime === "cloud"
      ? "Cloud agent started. It keeps running on Cursor's infrastructure. Poll cursor_get_agent / cursor_wait_for_run, or open the dashboard URL."
      : "Local agent started. Poll cursor_get_agent or cursor_wait_for_run until status is finished/error/cancelled.";
  }
  return "Run completed.";
}

function buildCreateOptions(apiKey: string, input: LaunchAgentInput): AgentOptions {
  const runtime: AgentRuntime = input.runtime ?? (input.cwd ? "local" : "cloud");
  const modelId = input.model?.trim() || DEFAULT_MODEL;
  const options: AgentOptions = {
    apiKey,
    model: { id: modelId },
    name: input.name,
  };

  if (runtime === "local") {
    options.local = { cwd: input.cwd || process.cwd() };
  } else {
    const repos = input.repositoryUrl
      ? [
          {
            url: input.repositoryUrl,
            startingRef: input.startingRef,
            prUrl: input.prUrl,
          },
        ]
      : [];
    options.cloud = {
      repos,
      autoCreatePR: input.autoCreatePr ?? false,
      skipReviewerRequest: input.skipReviewerRequest ?? true,
      workOnCurrentBranch: input.workOnCurrentBranch ?? false,
      metadata: input.metadata,
    };
  }
  return options;
}

export class SdkCursorClient implements CursorClient {
  async whoami(apiKey: string): Promise<CursorWhoami> {
    return (await Cursor.me({ apiKey })) as CursorWhoami;
  }

  async listModels(apiKey: string): Promise<unknown[]> {
    const models = await Cursor.models.list({ apiKey });
    return models.map((model) => ({
      id: model.id,
      displayName: "displayName" in model ? model.displayName : undefined,
      parameters: "parameters" in model ? model.parameters : undefined,
    }));
  }

  async listRepositories(apiKey: string): Promise<unknown[]> {
    return Cursor.repositories.list({ apiKey });
  }

  async launchAgent(apiKey: string, input: LaunchAgentInput): Promise<SerializedRun> {
    const runtime: AgentRuntime = input.runtime ?? (input.cwd ? "local" : "cloud");
    const shouldWait = input.wait ?? runtime === "local";
    const timeoutSeconds = clampTimeoutSeconds(
      input.timeoutSeconds,
      runtime === "local" ? DEFAULT_WAIT_LOCAL : DEFAULT_WAIT_CLOUD,
    );
    const options = buildCreateOptions(apiKey, { ...input, runtime });
    await using agent = await Agent.create(options);
    return this.sendAndMaybeWait(agent, input.prompt, shouldWait, timeoutSeconds, runtime);
  }

  async followUp(apiKey: string, input: FollowUpInput): Promise<SerializedRun> {
    const runtime: AgentRuntime = isCloudAgentId(input.agentId) ? "cloud" : "local";
    const shouldWait = input.wait ?? runtime === "local";
    const timeoutSeconds = clampTimeoutSeconds(
      input.timeoutSeconds,
      runtime === "local" ? DEFAULT_WAIT_LOCAL : DEFAULT_WAIT_CLOUD,
    );
    const resumeOptions: AgentOptions = { apiKey };
    if (input.model) resumeOptions.model = { id: input.model };
    if (runtime === "local") {
      resumeOptions.model = { id: input.model?.trim() || DEFAULT_MODEL };
      resumeOptions.local = { cwd: input.cwd || process.cwd() };
    }
    await using agent = await Agent.resume(input.agentId, resumeOptions);
    return this.sendAndMaybeWait(agent, input.prompt, shouldWait, timeoutSeconds, runtime);
  }

  private async sendAndMaybeWait(
    agent: SDKAgent,
    prompt: string,
    shouldWait: boolean,
    timeoutSeconds: number,
    runtime: AgentRuntime,
  ): Promise<SerializedRun> {
    const run = await agent.send(prompt);
    if (!shouldWait) {
      return serializeRun({
        id: run.id,
        agentId: agent.agentId,
        status: run.status,
        result: run.result,
        note: launchNote(runtime, false, false),
      });
    }
    const waited = await waitWithTimeout(run, timeoutSeconds);
    if (waited.timedOut) {
      return serializeRun({
        id: run.id,
        agentId: agent.agentId,
        status: run.status,
        result: run.result,
        timedOut: true,
        note: launchNote(runtime, true, true),
      });
    }
    const result = waited.result!;
    return serializeRun({
      id: result.id,
      agentId: agent.agentId,
      status: result.status,
      result: result.result,
      error: result.error,
      durationMs: result.durationMs,
      model: result.model,
      git: result.git,
      usage: result.usage,
      note: launchNote(runtime, true, false),
    });
  }

  async getAgent(apiKey: string, agentId: string, cwd?: string): Promise<SerializedAgent> {
    const info = await Agent.get(agentId, {
      apiKey,
      cwd,
    });
    return serializeAgent(info);
  }

  async listAgents(
    apiKey: string,
    options: { runtime: AgentRuntime; cwd?: string; includeArchived?: boolean; limit?: number },
  ): Promise<{ items: SerializedAgent[]; nextCursor?: string }> {
    const listed =
      options.runtime === "local"
        ? await Agent.list({
            runtime: "local",
            cwd: options.cwd || process.cwd(),
            limit: options.limit,
          })
        : await Agent.list({
            runtime: "cloud",
            apiKey,
            includeArchived: options.includeArchived,
            limit: options.limit,
          });
    return {
      items: listed.items.map((item) => serializeAgent(item)),
      nextCursor: listed.nextCursor,
    };
  }

  async listRuns(
    apiKey: string,
    agentId: string,
    options?: { cwd?: string; limit?: number },
  ): Promise<{ items: SerializedRun[]; nextCursor?: string }> {
    const listed = isCloudAgentId(agentId)
      ? await Agent.listRuns(agentId, { runtime: "cloud", apiKey, limit: options?.limit })
      : await Agent.listRuns(agentId, {
          runtime: "local",
          cwd: options?.cwd || process.cwd(),
          limit: options?.limit,
        });
    return {
      items: listed.items.map((run) =>
        serializeRun({
          id: run.id,
          agentId: run.agentId,
          status: run.status,
          result: run.result,
          error: run.error,
          durationMs: run.durationMs,
          model: run.model,
          git: run.git,
          usage: run.usage,
        }),
      ),
      nextCursor: listed.nextCursor,
    };
  }

  async getRun(
    apiKey: string,
    runId: string,
    options: { agentId?: string; cwd?: string; includeConversation?: boolean },
  ): Promise<SerializedRun & { conversation?: unknown }> {
    const run = await this.loadRun(apiKey, runId, options);
    const serialized = serializeRun({
      id: run.id,
      agentId: run.agentId,
      status: run.status,
      result: run.result,
      error: run.error,
      durationMs: run.durationMs,
      model: run.model,
      git: run.git,
      usage: run.usage,
    });
    if (options.includeConversation && run.supports("conversation")) {
      return { ...serialized, conversation: await run.conversation() };
    }
    return serialized;
  }

  async waitForRun(
    apiKey: string,
    runId: string,
    options: { agentId?: string; cwd?: string; timeoutSeconds?: number },
  ): Promise<SerializedRun> {
    const run = await this.loadRun(apiKey, runId, options);
    if (!run.supports("wait")) {
      return serializeRun({
        id: run.id,
        agentId: run.agentId,
        status: run.status,
        result: run.result,
        error: run.error,
        note: run.unsupportedReason("wait") ?? "wait is not supported on this run handle; use cursor_get_agent instead.",
      });
    }
    const timeoutSeconds = clampTimeoutSeconds(options.timeoutSeconds, DEFAULT_WAIT_CLOUD);
    const waited = await waitWithTimeout(run, timeoutSeconds);
    if (waited.timedOut) {
      return serializeRun({
        id: run.id,
        agentId: run.agentId,
        status: run.status,
        result: run.result,
        timedOut: true,
        note: launchNote(isCloudAgentId(run.agentId) ? "cloud" : "local", true, true),
      });
    }
    const result = waited.result!;
    return serializeRun({
      id: result.id,
      agentId: run.agentId,
      status: result.status,
      result: result.result,
      error: result.error,
      durationMs: result.durationMs,
      model: result.model,
      git: result.git,
      usage: result.usage,
      note: launchNote(isCloudAgentId(run.agentId) ? "cloud" : "local", true, false),
    });
  }

  async cancelRun(
    apiKey: string,
    runId: string,
    options?: { agentId?: string; cwd?: string },
  ): Promise<{ cancelled: boolean; agentId?: string; runId: string }> {
    const run = await this.loadRun(apiKey, runId, options ?? {});
    if (run.supports("cancel")) {
      await run.cancel();
      return { cancelled: true, agentId: run.agentId, runId };
    }
    await Agent.cancelRun(runId, isCloudAgentId(run.agentId)
      ? { runtime: "cloud", agentId: run.agentId, apiKey }
      : { runtime: "local", cwd: options?.cwd || process.cwd() });
    return { cancelled: true, agentId: run.agentId, runId };
  }

  async listArtifacts(apiKey: string, agentId: string): Promise<unknown[]> {
    await using agent = await Agent.resume(agentId, {
      apiKey,
      local: isCloudAgentId(agentId) ? undefined : { cwd: process.cwd() },
    });
    return agent.listArtifacts();
  }

  async getUsage(apiKey: string, agentId: string, runId?: string): Promise<unknown> {
    return Agent.getUsage(agentId, { apiKey, runId });
  }

  async archiveAgent(apiKey: string, agentId: string): Promise<{ archived: true; agentId: string }> {
    await Agent.archive(agentId, { apiKey });
    return { archived: true, agentId };
  }

  async unarchiveAgent(
    apiKey: string,
    agentId: string,
  ): Promise<{ unarchived: true; agentId: string }> {
    await Agent.unarchive(agentId, { apiKey });
    return { unarchived: true, agentId };
  }

  async deleteAgent(apiKey: string, agentId: string): Promise<{ deleted: true; agentId: string }> {
    await Agent.delete(agentId, { apiKey });
    return { deleted: true, agentId };
  }

  private async loadRun(
    apiKey: string,
    runId: string,
    options: { agentId?: string; cwd?: string },
  ): Promise<Run> {
    if (options.agentId && isCloudAgentId(options.agentId)) {
      return Agent.getRun(runId, { runtime: "cloud", agentId: options.agentId, apiKey });
    }
    if (options.agentId && !isCloudAgentId(options.agentId)) {
      return Agent.getRun(runId, { runtime: "local", cwd: options.cwd || process.cwd() });
    }
    // Cloud run IDs are not bc- prefixed; prefer cloud when agentId is omitted.
    try {
      if (options.agentId) {
        return Agent.getRun(runId, { runtime: "cloud", agentId: options.agentId, apiKey });
      }
    } catch {
      // fall through
    }
    throw new Error("Cloud cursor_get_run / cursor_wait_for_run / cursor_cancel_run require agent_id.");
  }
}

export function isCursorAgentError(err: unknown): err is CursorAgentError {
  return err instanceof CursorAgentError;
}

export { asErrorMessage };
