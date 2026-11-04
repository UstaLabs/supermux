# @supermux/platform

What supermux needs from the operating system that Node and Bun do not give it, one rule per gap, tested on
Linux, macOS and Windows. Anything the runtimes already do right is called directly instead.

- `proc.isAlive(pid)`: whether a process exists. `EPERM` (someone else's process) counts as alive.

Private for now: only the broker uses it. It is published at `@supermux/core`'s version once core depends on it.
