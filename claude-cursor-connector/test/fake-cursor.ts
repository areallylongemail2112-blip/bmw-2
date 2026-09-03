import type {
  AgentRuntime,
  CursorClient,
  CursorWhoami,
  FollowUpInput,
  LaunchAgentInput,
  SerializedAgent,
  SerializedRun,
} from "../src/types.js";

export class FakeCursorClient implements CursorClient {
  whoamiImpl: () => Promise<CursorWhoami> = async () => ({
    apiKeyName: "test-key",
    userEmail: "dev@example.com",
  });
  models: unknown[] = [{ id: "composer-2.5" }, { id: "auto-smart" }];
  repos: unknown[] = [{ url: "https://github.com/example/repo" }];
  launched: LaunchAgentInput[] = [];
  followUps: FollowUpInput[] = [];
  agents = new Map<string, SerializedAgent>([
    [
      "bc-test-1",
      {
        agentId: "bc-test-1",
        name: "test agent",
        status: "running",
        runtime: "cloud",
        repos: ["https://github.com/example/repo"],
        url: "https://cursor.com/agents/bc-test-1",
      },
    ],
  ]);
  runs = new Map<string, SerializedRun>([
    [
      "run-1",
      {
        agentId: "bc-test-1",
        runId: "run-1",
        status: "running",
        url: "https://cursor.com/agents/bc-test-1",
      },
    ],
  ]);
  failWhoami = false;

  async whoami(_apiKey: string): Promise<CursorWhoami> {
    if (this.failWhoami) throw Object.assign(new Error("Invalid API key"), { isRetryable: false, name: "AuthenticationError" });
    return this.whoamiImpl();
  }
  async listModels(_apiKey: string): Promise<unknown[]> {
    return this.models;
  }
  async listRepositories(_apiKey: string): Promise<unknown[]> {
    return this.repos;
  }
  async launchAgent(_apiKey: string, input: LaunchAgentInput): Promise<SerializedRun> {
    this.launched.push(input);
    const agentId = "bc-launched-1";
    const runId = "run-launched-1";
    this.agents.set(agentId, {
      agentId,
      name: input.name ?? "launched",
      status: input.wait ? "finished" : "running",
      runtime: input.runtime ?? "cloud",
      repos: input.repositoryUrl ? [input.repositoryUrl] : [],
      url: `https://cursor.com/agents/${agentId}`,
    });
    const run: SerializedRun = {
      agentId,
      runId,
      status: input.wait ? "finished" : "running",
      result: input.wait ? "done" : undefined,
      url: `https://cursor.com/agents/${agentId}`,
      note: input.wait
        ? "Run completed."
        : "Cloud agent started. It keeps running on Cursor's infrastructure. Poll cursor_get_agent / cursor_wait_for_run, or open the dashboard URL.",
    };
    this.runs.set(runId, run);
    return run;
  }
  async followUp(_apiKey: string, input: FollowUpInput): Promise<SerializedRun> {
    this.followUps.push(input);
    return {
      agentId: input.agentId,
      runId: "run-follow-1",
      status: input.wait ? "finished" : "running",
      url: `https://cursor.com/agents/${input.agentId}`,
    };
  }
  async getAgent(_apiKey: string, agentId: string): Promise<SerializedAgent> {
    const found = this.agents.get(agentId);
    if (!found) throw new Error(`Agent ${agentId} not found`);
    return found;
  }
  async listAgents(
    _apiKey: string,
    options: { runtime: AgentRuntime; cwd?: string; includeArchived?: boolean; limit?: number },
  ): Promise<{ items: SerializedAgent[]; nextCursor?: string }> {
    const items = [...this.agents.values()].filter((a) => !options.runtime || a.runtime === options.runtime);
    return { items: items.slice(0, options.limit ?? items.length) };
  }
  async listRuns(
    _apiKey: string,
    agentId: string,
  ): Promise<{ items: SerializedRun[]; nextCursor?: string }> {
    return { items: [...this.runs.values()].filter((r) => r.agentId === agentId) };
  }
  async getRun(
    _apiKey: string,
    runId: string,
    options: { agentId?: string; cwd?: string; includeConversation?: boolean },
  ): Promise<SerializedRun & { conversation?: unknown }> {
    const found = this.runs.get(runId);
    if (!found) throw new Error(`Run ${runId} not found`);
    if (options.includeConversation) return { ...found, conversation: [{ type: "user", text: "hi" }] };
    return found;
  }
  async waitForRun(
    _apiKey: string,
    runId: string,
    options: { agentId?: string; cwd?: string; timeoutSeconds?: number },
  ): Promise<SerializedRun> {
    const found = this.runs.get(runId);
    if (!found) throw new Error(`Run ${runId} not found`);
    const finished: SerializedRun = {
      ...found,
      status: "finished",
      result: "all done",
      durationMs: 1200,
      timedOut: false,
      note: "Run completed.",
    };
    this.runs.set(runId, finished);
    const agent = this.agents.get(found.agentId);
    if (agent) this.agents.set(found.agentId, { ...agent, status: "finished" });
    void options;
    return finished;
  }
  async cancelRun(
    _apiKey: string,
    runId: string,
  ): Promise<{ cancelled: boolean; agentId?: string; runId: string }> {
    const found = this.runs.get(runId);
    if (found) this.runs.set(runId, { ...found, status: "cancelled" });
    return { cancelled: true, agentId: found?.agentId, runId };
  }
  async listArtifacts(_apiKey: string, _agentId: string): Promise<unknown[]> {
    return [{ path: "report.txt", sizeBytes: 12 }];
  }
  async getUsage(_apiKey: string, agentId: string, runId?: string): Promise<unknown> {
    return { agentId, runId, usage: { totalTokens: 42 }, cost: { chargedCents: 0 } };
  }
  async archiveAgent(_apiKey: string, agentId: string): Promise<{ archived: true; agentId: string }> {
    const found = this.agents.get(agentId);
    if (found) this.agents.set(agentId, { ...found, archived: true });
    return { archived: true, agentId };
  }
  async unarchiveAgent(
    _apiKey: string,
    agentId: string,
  ): Promise<{ unarchived: true; agentId: string }> {
    const found = this.agents.get(agentId);
    if (found) this.agents.set(agentId, { ...found, archived: false });
    return { unarchived: true, agentId };
  }
  async deleteAgent(_apiKey: string, agentId: string): Promise<{ deleted: true; agentId: string }> {
    this.agents.delete(agentId);
    return { deleted: true, agentId };
  }
}
