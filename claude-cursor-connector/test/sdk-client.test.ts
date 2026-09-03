import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  send: vi.fn(),
  dispose: vi.fn(async () => undefined),
  create: vi.fn(),
  resume: vi.fn(),
  get: vi.fn(),
  list: vi.fn(),
  listRuns: vi.fn(),
  getRun: vi.fn(),
  cancelRun: vi.fn(),
  archive: vi.fn(),
  unarchive: vi.fn(),
  deleteAgent: vi.fn(),
  getUsage: vi.fn(),
  me: vi.fn(),
  listModels: vi.fn(),
  listRepos: vi.fn(),
  listArtifacts: vi.fn(async () => [{ path: "out.txt", sizeBytes: 4 }]),
}));

vi.mock("@cursor/sdk", () => ({
  Agent: {
    create: mocks.create,
    resume: mocks.resume,
    get: mocks.get,
    list: mocks.list,
    listRuns: mocks.listRuns,
    getRun: mocks.getRun,
    cancelRun: mocks.cancelRun,
    archive: mocks.archive,
    unarchive: mocks.unarchive,
    delete: mocks.deleteAgent,
    getUsage: mocks.getUsage,
  },
  Cursor: {
    me: mocks.me,
    models: { list: mocks.listModels },
    repositories: { list: mocks.listRepos },
  },
  CursorAgentError: class CursorAgentError extends Error {
    isRetryable = false;
  },
}));

import { SdkCursorClient } from "../src/cursor/sdk-client.js";

function fakeRun(overrides: Record<string, unknown> = {}) {
  return {
    id: "run-1",
    agentId: "bc-1",
    status: "running",
    result: undefined,
    error: undefined,
    durationMs: undefined,
    model: { id: "composer-2.5" },
    git: undefined,
    usage: undefined,
    wait: vi.fn(async () => ({
      id: "run-1",
      status: "finished" as const,
      result: "done",
    })),
    cancel: vi.fn(async () => undefined),
    conversation: vi.fn(async () => [{ type: "user" }]),
    supports: vi.fn((op: string) => ["wait", "cancel", "conversation"].includes(op)),
    unsupportedReason: vi.fn(() => undefined),
    ...overrides,
  };
}

function fakeAgent(agentId: string) {
  return {
    agentId,
    send: mocks.send,
    listArtifacts: mocks.listArtifacts,
    [Symbol.asyncDispose]: mocks.dispose,
  };
}

describe("SdkCursorClient", () => {
  const client = new SdkCursorClient();
  const apiKey = "cursor_abcdefghijk";

  beforeEach(() => {
    mocks.send.mockReset();
    mocks.dispose.mockClear();
    mocks.create.mockReset();
    mocks.resume.mockReset();
    mocks.get.mockReset();
    mocks.list.mockReset();
    mocks.listRuns.mockReset();
    mocks.getRun.mockReset();
    mocks.cancelRun.mockReset();
    mocks.archive.mockReset();
    mocks.unarchive.mockReset();
    mocks.deleteAgent.mockReset();
    mocks.getUsage.mockReset();
    mocks.me.mockReset();
    mocks.listModels.mockReset();
    mocks.listRepos.mockReset();
    mocks.listArtifacts.mockClear();

    const run = fakeRun();
    mocks.send.mockResolvedValue(run);
    mocks.create.mockResolvedValue(fakeAgent("bc-created"));
    mocks.resume.mockResolvedValue(fakeAgent("bc-resumed"));
    mocks.get.mockResolvedValue({
      agentId: "bc-1",
      name: "demo",
      summary: "working",
      status: "running",
      runtime: "cloud",
      repos: ["https://github.com/org/repo"],
      lastModified: 1,
    });
    mocks.list.mockResolvedValue({ items: [], nextCursor: undefined });
    mocks.listRuns.mockResolvedValue({ items: [], nextCursor: undefined });
    mocks.getRun.mockResolvedValue(run);
    mocks.me.mockResolvedValue({ userEmail: "dev@example.com" });
    mocks.listModels.mockResolvedValue([{ id: "composer-2.5", displayName: "Composer 2.5" }]);
    mocks.listRepos.mockResolvedValue([{ url: "https://github.com/org/repo" }]);
    mocks.archive.mockResolvedValue(undefined);
    mocks.unarchive.mockResolvedValue(undefined);
    mocks.deleteAgent.mockResolvedValue(undefined);
    mocks.cancelRun.mockResolvedValue(undefined);
    mocks.getUsage.mockResolvedValue({ usage: { totalTokens: 1 } });
  });

  it("launches a cloud agent, sends the prompt, and does not wait by default", async () => {
    const result = await client.launchAgent(apiKey, {
      prompt: "Fix auth.ts",
      repositoryUrl: "https://github.com/org/repo",
      startingRef: "main",
      autoCreatePr: true,
      name: "auth-fix",
    });

    expect(mocks.create).toHaveBeenCalledOnce();
    const options = mocks.create.mock.calls[0][0];
    expect(options.apiKey).toBe(apiKey);
    expect(options.model).toEqual({ id: "composer-2.5" });
    expect(options.name).toBe("auth-fix");
    expect(options.cloud).toMatchObject({
      repos: [{ url: "https://github.com/org/repo", startingRef: "main" }],
      autoCreatePR: true,
      skipReviewerRequest: true,
    });
    expect(options.local).toBeUndefined();
    expect(mocks.send).toHaveBeenCalledWith("Fix auth.ts");
    expect(result.agentId).toBe("bc-created");
    expect(result.runId).toBe("run-1");
    expect(result.url).toBe("https://cursor.com/agents/bc-created");
    const createdRun = await mocks.send.mock.results[0].value;
    expect(createdRun.wait).not.toHaveBeenCalled();
    expect(mocks.dispose).toHaveBeenCalledOnce();
  });

  it("waits when wait=true", async () => {
    const result = await client.launchAgent(apiKey, {
      prompt: "summarize",
      wait: true,
      timeoutSeconds: 30,
    });
    const run = await mocks.send.mock.results[0].value;
    expect(run.wait).toHaveBeenCalledOnce();
    expect(result.status).toBe("finished");
    expect(result.result).toBe("done");
    expect(result.note).toBe("Run completed.");
  });

  it("resumes a cloud agent for follow-up", async () => {
    await client.followUp(apiKey, { agentId: "bc-abc", prompt: "also tests", wait: false });
    expect(mocks.resume).toHaveBeenCalledWith(
      "bc-abc",
      expect.objectContaining({ apiKey }),
    );
    expect(mocks.send).toHaveBeenCalledWith("also tests");
  });

  it("lists, fetches, cancels, archives, and deletes agents through the SDK", async () => {
    await client.getAgent(apiKey, "bc-1");
    expect(mocks.get).toHaveBeenCalledWith("bc-1", { apiKey, cwd: undefined });

    await client.listAgents(apiKey, { runtime: "cloud", includeArchived: true, limit: 5 });
    expect(mocks.list).toHaveBeenCalledWith({
      runtime: "cloud",
      apiKey,
      includeArchived: true,
      limit: 5,
    });

    const run = fakeRun();
    mocks.getRun.mockResolvedValue(run);
    await client.cancelRun(apiKey, "run-1", { agentId: "bc-1" });
    expect(mocks.getRun).toHaveBeenCalledWith("run-1", { runtime: "cloud", agentId: "bc-1", apiKey });
    expect(run.cancel).toHaveBeenCalledOnce();

    await client.archiveAgent(apiKey, "bc-1");
    expect(mocks.archive).toHaveBeenCalledWith("bc-1", { apiKey });
    await client.unarchiveAgent(apiKey, "bc-1");
    expect(mocks.unarchive).toHaveBeenCalledWith("bc-1", { apiKey });
    await client.deleteAgent(apiKey, "bc-1");
    expect(mocks.deleteAgent).toHaveBeenCalledWith("bc-1", { apiKey });
  });

  it("requires agent_id to load a cloud run", async () => {
    await expect(client.getRun(apiKey, "run-1", {})).rejects.toThrow(/require agent_id/);
  });

  it("reads account, models, and repos from Cursor.*", async () => {
    await expect(client.whoami(apiKey)).resolves.toMatchObject({ userEmail: "dev@example.com" });
    expect(mocks.me).toHaveBeenCalledWith({ apiKey });
    await client.listModels(apiKey);
    expect(mocks.listModels).toHaveBeenCalledWith({ apiKey });
    await client.listRepositories(apiKey);
    expect(mocks.listRepos).toHaveBeenCalledWith({ apiKey });
  });
});
