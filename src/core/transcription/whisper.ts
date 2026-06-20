import { spawn as bunSpawn } from "bun"
import { readFile, unlink } from "fs/promises"
import { join } from "path"
import { tmpdir, homedir } from "os"
import { makeLogger } from "../../shared/log"

const log = makeLogger("whisper")
const DEFAULT_MODEL = process.env.MUX_WHISPER_MODEL ?? join(homedir(), ".cache", "whisper-models", "ggml-small.bin")
const WHISPER_BIN = process.env.MUX_WHISPER_BIN ?? join(homedir(), ".local", "bin", "whisper-cli")
const FFMPEG_BIN = process.env.MUX_FFMPEG_BIN ?? "ffmpeg"

export interface TranscribeOpts {
  model?: string
  lang?: string
  spawn?: (cmd: string, args: string[]) => { exited: Promise<number> }
  readText?: () => Promise<string>
  tmpWav?: string
  tmpOut?: string
}

export async function transcribeAudio(audioPath: string, opts: TranscribeOpts = {}): Promise<{ text: string }> {
  const model = opts.model ?? DEFAULT_MODEL
  const lang = opts.lang ?? "auto"
  const stamp = `${process.pid}-${audioPath.length}`
  const wav = opts.tmpWav ?? join(tmpdir(), `mux-stt-${stamp}.wav`)
  const outBase = opts.tmpOut ?? join(tmpdir(), `mux-stt-${stamp}`)
  const run = opts.spawn ?? ((cmd: string, args: string[]) => bunSpawn([cmd, ...args], { stdout: "ignore", stderr: "ignore" }))
  try {
    log.info("transcoding audio", { audioPath, wav })
    const ff = run(FFMPEG_BIN, ["-y", "-i", audioPath, "-ac", "1", "-ar", "16000", "-f", "wav", wav])
    if ((await ff.exited) !== 0) throw new Error("ffmpeg failed")
    log.info("running whisper-cli", { wav, outBase, model, lang })
    const wc = run(WHISPER_BIN, ["-m", model, "-f", wav, "-otxt", "-of", outBase, "-nt", "-np", "-l", lang])
    if ((await wc.exited) !== 0) throw new Error("whisper-cli failed")
    const raw = opts.readText ? await opts.readText() : await readFile(`${outBase}.txt`, "utf8")
    return { text: raw.trim() }
  } finally {
    if (!opts.readText) {
      await unlink(wav).catch(() => {})
      await unlink(`${outBase}.txt`).catch(() => {})
    }
  }
}
