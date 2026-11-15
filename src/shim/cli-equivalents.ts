/**
 * The MCP tools the `supermux` CLI also does (#156), with the command an agent should use instead. While the two run
 * side by side, these tools' descriptions lead with the command and their results end with it, so a session that
 * started before the CLI learns it at the moment it uses the tool. Tools that stay on MCP (message_session, show_html,
 * attach, council_report, the rpc pair) and the ones the CLI does not have (react, edit_message, set_active, …) are
 * not listed.
 */
export const CLI_EQUIVALENT: Readonly<Record<string, string>> = {
  list_sessions: "supermux session ls",
  find_sessions: "supermux session find QUERY",
  read_session: "supermux session read SESSION",
  spawn_session: "supermux session spawn DIR",
  kill_session: "supermux session kill SESSION",
  rename_session: "supermux session rename NAME",
  list_models: "supermux session models",
  delegate: "supermux delegate TASK --agent A --access ID",
  council: "supermux council start QUESTION --member AGENT:ACCESS",
  council_round: "supermux council round COUNCIL FOCUS",
  council_add: "supermux council add COUNCIL --member AGENT:ACCESS",
  council_remove: "supermux council remove COUNCIL LETTER",
  council_end: "supermux council end COUNCIL VERDICT",
  download_attachment: "supermux file get FILE_ID",
  expose_port: "supermux port expose PORT",
  unexpose_port: "supermux port unexpose SLUG",
  set_proxy_public: "supermux port public|private SLUG",
  list_devices: "supermux device ls",
  start_display: "supermux display start",
  stop_display: "supermux display stop ID",
  memory_search: "supermux memory search QUERY",
  browser_comments: "supermux comments ls",
  resolve_browser_comment: "supermux comments resolve ID",
  reply_browser_comment: "supermux comments reply ID TEXT",
  dismiss_browser_comment: "supermux comments dismiss ID",
  walkthrough: "supermux card walkthrough FILE.json",
}

/** The description lead of a tool the CLI also does. */
export const cliLead = (tool: string): string | undefined =>
  CLI_EQUIVALENT[tool] ? `Prefer the CLI in your shell: \`${CLI_EQUIVALENT[tool]}\` (\`supermux help\` is the whole reference). ` : undefined

/** The line ending a result of such a tool. */
export const cliTip = (tool: string): string | undefined =>
  CLI_EQUIVALENT[tool] ? `\n\n(next time: \`${CLI_EQUIVALENT[tool]}\` in your shell does this; \`supermux help\` lists everything)` : undefined
