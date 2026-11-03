-- The slash commands each agent last reported in a folder (card #13). A broker restart and the New Session
-- launcher reuse them instead of asking the agent again; nothing ever prompts an agent to learn them.
-- `commands` is the registry's JSON ({"commands": [...], "projectIds"?: [...]}).
CREATE TABLE agent_commands (
  agent      TEXT NOT NULL,
  workdir    TEXT NOT NULL,
  commands   TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (agent, workdir)
);
CREATE INDEX agent_commands_recent ON agent_commands(agent, updated_at);
