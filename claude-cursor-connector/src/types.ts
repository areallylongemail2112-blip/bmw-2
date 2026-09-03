export interface AuthContext {
  apiKey: string;
  source: "cursor-api-key" | "connector-token" | "oauth" | "env";
}

export type AgentRuntime = "cloud" | "local";

export interface LaunchAgentInput {
  prompt: string;
  runtime?: AgentRuntime;
  repositoryUrl?: string;
  startingRef?: string;
  prUrl?: string;
  autoCreatePr?: boolean;
  skipReviewerRequest?: boolean;
  workOnCurrentBranch?: boolean;
  model?: string;
  name?: string;
  wait?: boolean;
  timeoutSeconds?: number;
  cwd?: string;
  metadata?: Record<string, string>;
}

export interface FollowUpInput {
  agentId: string;
  prompt: string;
  wait?: boolean;
  timeoutSeconds?: number;
  model?: string;
  cwd?: string;
}

export interface SerializedRun {
  agentId: string;
  runId: string;
  status: string;
  result?: string;
  error?: { message: string; code?: string };
  durationMs?: number;
  model?: unknown;
  git?: unknown;
  usage?: unknown;
  url?: string;
  timedOut?: boolean;
  note?: string;
}

export interface SerializedAgent {
  agentId: string;
  name?: string;
  summary?: string;
  status?: string;
  runtime?: string;
  repos?: string[];
  lastModified?: number;
  createdAt?: number;
  archived?: boolean;
  url?: string;
}

export interface CursorWhoami {
  apiKeyName?: string;
  userEmail?: string;
  createdAt?: string | number;
  [key: string]: unknown;
}

export interface CursorClient {
  whoami(apiKey: string): Promise<CursorWhoami>;
  listModels(apiKey: string): Promise<unknown[]>;
  listRepositories(apiKey: string): Promise<unknown[]>;
  launchAgent(apiKey: string, input: LaunchAgentInput): Promise<SerializedRun>;
  followUp(apiKey: string, input: FollowUpInput): Promise<SerializedRun>;
  getAgent(apiKey: string, agentId: string, cwd?: string): Promise<SerializedAgent>;
  listAgents(
    apiKey: string,
    options: { runtime: AgentRuntime; cwd?: string; includeArchived?: boolean; limit?: number },
  ): Promise<{ items: SerializedAgent[]; nextCursor?: string }>;
  listRuns(
    apiKey: string,
    agentId: string,
    options?: { cwd?: string; limit?: number },
  ): Promise<{ items: SerializedRun[]; nextCursor?: string }>;
  getRun(
    apiKey: string,
    runId: string,
    options: { agentId?: string; cwd?: string; includeConversation?: boolean },
  ): Promise<SerializedRun & { conversation?: unknown }>;
  waitForRun(
    apiKey: string,
    runId: string,
    options: { agentId?: string; cwd?: string; timeoutSeconds?: number },
  ): Promise<SerializedRun>;
  cancelRun(
    apiKey: string,
    runId: string,
    options?: { agentId?: string; cwd?: string },
  ): Promise<{ cancelled: boolean; agentId?: string; runId: string }>;
  listArtifacts(apiKey: string, agentId: string): Promise<unknown[]>;
  getUsage(apiKey: string, agentId: string, runId?: string): Promise<unknown>;
  archiveAgent(apiKey: string, agentId: string): Promise<{ archived: true; agentId: string }>;
  unarchiveAgent(apiKey: string, agentId: string): Promise<{ unarchived: true; agentId: string }>;
  deleteAgent(apiKey: string, agentId: string): Promise<{ deleted: true; agentId: string }>;
}
