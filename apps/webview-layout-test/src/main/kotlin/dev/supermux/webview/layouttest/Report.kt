package dev.supermux.webview.layouttest

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

enum class Verdict { PASS, FAIL, INCONCLUSIVE, INFO, NOT_RUN }

/** The run's results: RESULT and NOTE lines on stdout and in summary.txt, and every result in results.json. */
class Report(private val dir: File) {
    private val entries = ArrayList<JsonObject>()
    private val summary = File(dir, "summary.txt")

    fun add(layout: String, criterion: String, name: String, verdict: Verdict, detail: String) {
        write("RESULT $layout $criterion $name $verdict $detail")
        entries += buildJsonObject {
            put("layout", layout)
            put("criterion", criterion)
            put("name", name)
            put("verdict", verdict.name)
            put("detail", detail)
        }
    }

    fun note(text: String) = write("NOTE $text")

    /** Writes results.json; the last line, LAYOUT-TEST DONE, is what mac/run.sh waits for. */
    fun finish() {
        File(dir, "results.json").writeText(JsonArray(entries).toString())
        write("LAYOUT-TEST DONE")
    }

    private fun write(line: String) {
        println(line)
        summary.appendText(line + "\n")
    }
}
