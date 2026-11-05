export { connectKeeper, detachedKeeperHooks, keeperAgentRunning } from './client.js'
export { agentExitError, exitCodeText } from './exit.js'
export type { StopKeeperLimits } from './client.js'
export type { ConnectKeeperOptions, KeeperConnection, KeeperExitStatus, KeeperFrameEvent } from './client.js'
export type {
  FrameShape, KeeperLimits, KeeperSpec, KeeperStatus, JournalEntry,
  ClientMessage, KeeperMessage, KeeperWelcome,
} from './protocol.js'
export { requestId, responseId } from './protocol.js'
