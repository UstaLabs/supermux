# Speech models for the dictation stream

Vendored so the broker never downloads a model at runtime; `speech-models-worker.ts` imports both
`with { type: "file" }`, so a compiled binary embeds them.

| File | What | Source | License | SHA-256 |
|---|---|---|---|---|
| `silero_vad_v5.onnx` | Silero VAD v5 — is this 32 ms frame speech? | [snakers4/silero-vad](https://github.com/snakers4/silero-vad), copied from `@ricky0123/vad-web@0.0.31/dist/silero_vad_v5.onnx` | MIT | `2623a2953f6ff3d2c1e61740c6cdb7168133479b267dfef114a4a3cc5bdd788f` |
| `smart-turn-v3.2-cpu.onnx` | Smart Turn v3.2, quantised CPU checkpoint — has the speaker finished? | [pipecat-ai/smart-turn-v3](https://huggingface.co/pipecat-ai/smart-turn-v3/resolve/main/smart-turn-v3.2-cpu.onnx) | BSD-2-Clause | `2bb026316b14a660486a75b1733cd3fbab8c2fd0314dc9af7be49f8cca967e4f` |

Wire shapes (checked 2026-10-31):

- Silero: `input` float32 `[1, 576]` (the previous frame's last 64 samples + 512 new ones at
  16 kHz), `state` float32 `[2, 1, 128]`, `sr` int64 scalar → `output` `[1, 1]`, `stateN`.
- Smart Turn: `input_features` float32 `[1, 80, 800]` (Whisper log-mel of the last 8 s, from
  `@micdrop/smart-turn`'s `extractFeatures`) → `logits` `[1, 1]`, already a probability.
