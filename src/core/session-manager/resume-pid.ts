export function resumedSessionPid(
  runtimePid: number | null,
  storedPid: number | undefined,
  brokerPid = process.pid,
): number {
  return (runtimePid ?? storedPid) || brokerPid
}
