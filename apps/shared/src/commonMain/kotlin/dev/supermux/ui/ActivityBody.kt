package dev.supermux.ui

import dev.supermux.proto.ActivityToolBody

/** Prefer structured body; fall back to medium input/output strings. */
fun resolveBashParts(
    body: ActivityToolBody?,
    resultBody: ActivityToolBody?,
    input: String?,
    output: String?,
    toolName: String?,
): BashParts? {
    val start = body?.takeIf { it.kind == "bash" }
    val end = resultBody?.takeIf { it.kind == "bash" }
    val isBash = toolName == "Bash" || start != null || end != null
    if (!isBash) return null
    val command = start?.command ?: input?.takeIf { toolName == "Bash" }
    val out = end?.output ?: start?.output ?: output
    val exit = end?.exitCode ?: start?.exitCode
    if (command.isNullOrBlank() && out.isNullOrBlank() && toolName != "Bash") return null
    return BashParts(command = command, output = out, exitCode = exit)
}

data class BashParts(
    val command: String?,
    val output: String?,
    val exitCode: Int?,
)

fun resolveEditParts(
    body: ActivityToolBody?,
    input: String?,
    toolName: String?,
): EditParts? {
    when (body?.kind) {
        "edit" -> return EditParts(
            path = body.path ?: "file",
            mode = body.mode,
            diff = body.diff,
            content = null,
        )
        "write" -> {
            val content = body.content
            val diff = content?.lineSequence()?.joinToString("\n") { "+$it" }
            return EditParts(
                path = body.path ?: "file",
                mode = "add",
                diff = diff,
                content = content,
            )
        }
    }
    if (toolName == "Edit" || toolName == "Write") {
        val path = input?.lineSequence()?.firstOrNull()
            ?.replace(Regex("^(update|add|delete|move)\\s+", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.ifBlank { "file" }
            ?: "file"
        val looksDiff = input != null && (input.contains("\n+") || input.contains("\n-") || input.contains("@@"))
        return EditParts(
            path = if (path.length > 120) path.take(120) else path,
            mode = if (toolName == "Write") "add" else "update",
            diff = if (looksDiff) input else null,
            content = if (!looksDiff && toolName == "Write") input else null,
        )
    }
    return null
}

data class EditParts(
    val path: String,
    val mode: String?,
    val diff: String?,
    val content: String?,
)
