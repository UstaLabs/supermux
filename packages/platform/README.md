# @supermux/platform

What supermux needs from the operating system that Node and Bun do not give it, one rule per gap, tested on
Linux, macOS and Windows. Anything the runtimes already do right is called directly instead.

- `proc.isAlive(pid)`: whether a process exists. `EPERM` (someone else's process) counts as alive.

- `lock.singleInstance(name, scope)`: one instance per name and state directory, held by the kernel.
- `paths.isInside(path, root)` / `paths.isStrictlyInside`: containment by the runtime's `relative()`.

Private and never published. The broker and the tests use its source through the workspace; `@supermux/core`'s
npm build carries a copy (`dist/vendor/platform`, see core's `scripts/vendor-platform.mjs`), so the published core
does not depend on it.
