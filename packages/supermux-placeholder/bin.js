#!/usr/bin/env node
// This package only reserves the name. The real supermux CLI installs from supermux.dev, so this command
// says so on stderr and fails: a script that runs `supermux` must never take this for the tool.
console.error('This npm package only reserves the name "supermux".')
console.error("The supermux CLI installs with: curl -fsSL https://supermux.dev/install.sh | sh")
console.error("The library is on npm: npm i @supermux/core")
process.exitCode = 1
