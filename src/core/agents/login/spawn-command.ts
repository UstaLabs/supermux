export interface LoginSpawnCommand {
  cmd: string
  args: string[]
  detached?: boolean
}

/**
 * Claude's browser login needs a pseudo-terminal. `script` has incompatible
 * command-line syntaxes on macOS (BSD) and Linux (util-linux), so keep the
 * platform split explicit instead of relying on the Linux-only `-c` flag.
 */
export function claudeLoginSpawnCommand(platform = process.platform): LoginSpawnCommand {
  const shellCommand = "stty cols 600; exec claude auth login"
  if (platform === "darwin") {
    return {
      cmd: "/bin/sh",
      // Node/Bun implements child stdin with a socketpair on macOS. BSD
      // `script` calls tcgetattr() on stdin and aborts on that socket with
      // EOPNOTSUPP. `cat | script` converts it to a real POSIX pipe while
      // keeping the stream writable for the OAuth code the user pastes later.
      // Run `script` through a supervising shell: once Claude/script exits,
      // kill the sibling `cat` that would otherwise wait on the still-open
      // socket forever and prevent the broker from observing process exit.
      // Pass the Claude command as $1 instead of interpolating shell text.
      args: [
        "-c",
        `cat | /bin/sh -c 'trap "/usr/bin/pkill -TERM -P \\"$PPID\\" -x cat 2>/dev/null || true" EXIT; /usr/bin/script -q /dev/null /bin/sh -c "$1"' supermux-claude-script "$1"`,
        "supermux-claude-login",
        shellCommand,
      ],
      // The wrapper is a shell pipeline. Put it in its own process group so a
      // cancelled/retried login terminates cat, script, and Claude together.
      detached: true,
    }
  }
  return {
    cmd: "script",
    args: ["-qec", shellCommand, "/dev/null"],
  }
}
