#!/bin/sh
# Docker build-time check (#126): the bundled Claude CLI accepts EVERY flag the broker can launch it with
# (packages/supermux-core/src/claude/index.ts, argv()). The image pinned claude-code 2.1.162 for months, which
# rejects --await-initialize, so every Claude session in the published image failed at its first spawn.
# No account is needed: an unknown option is refused while parsing, before any login or network call.
# tests/claude-cli-smoke.test.ts keeps this flag list in step with argv().
set -u
# --session-id (a new conversation) and --resume (an existing one) are exclusive: check both forms.
for last in --session-id=00000000-0000-4000-8000-000000000000 --resume=00000000-0000-4000-8000-000000000000; do
  out=$(timeout 30 claude \
    --print --output-format stream-json --verbose --input-format stream-json --await-initialize \
    --tools "" --permission-prompts host --permission-prompt-tool stdio --permission-mode acceptEdits \
    --allow-dangerously-skip-permissions --allowedTools Read --disallowedTools Bash \
    --model sonnet --effort high --settings '{"fastMode":true}' --include-partial-messages \
    "$last" </dev/null 2>&1)
  echo "$out" | head -3
  if echo "$out" | grep -qiE "unknown option|error: option|too many arguments"; then
    echo "claude-cli-smoke: the bundled Claude CLI ($(claude --version 2>&1)) rejects a flag the broker uses (${last%%=*})" >&2
    exit 1
  fi
done
echo "claude-cli-smoke: ok ($(claude --version 2>&1))"
