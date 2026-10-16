package dev.wckdboy.autobot.core.designsystem.markdown

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `shared/design/markdown-cases.json` is the chat markdown spec both apps share: each case's
 * markdown, its block/span tree and what read-aloud speaks. iOS runs the same file
 * (`MarkdownConformanceTests.swift`). Every run writes this parser's output for all cases to
 * `build/markdown-cases.actual.json`, ready to diff (or to copy over the fixture after a deliberate change).
 */
class MarkdownConformanceTest {
    private val file: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "shared/design/markdown-cases.json") }
        .first { it.isFile }

    @Test
    fun everyCaseParsesToItsTreeAndSpeech() {
        val cases = Json.parseToJsonElement(file.readText()).jsonArray.map { it.jsonObject }
        val actual = cases.map { case ->
            val md = case.getValue("md").jsonPrimitive.content
            buildJsonObject {
                put("name", case.getValue("name"))
                put("md", md)
                put("blocks", MdJson.blocks(LiteMarkdown.parse(md)))
                put("speak", LiteMarkdown.speakable(md))
            }
        }
        File("build").mkdirs()
        File("build/markdown-cases.actual.json").writeText(pretty.encodeToString(JsonArray.serializer(), JsonArray(actual)))
        val failures = cases.zip(actual).filter { (expected, got) ->
            expected["blocks"] != got["blocks"] || expected["speak"] != got["speak"]
        }
        assertEquals(
            "cases differ (see build/markdown-cases.actual.json): " + failures.joinToString { it.first["name"].toString() },
            emptyList<String>(),
            failures.map { it.first["name"].toString() },
        )
        assert(cases.size >= 80) { "the corpus shrank: ${cases.size}" }
    }

    /** Streaming (re-parsing only the tail) never changes the result: every prefix of every case. */
    @Test
    fun everyPrefixOfEveryCaseStreamsLikeAFullParse() {
        val cases = Json.parseToJsonElement(file.readText()).jsonArray.map { it.jsonObject.getValue("md").jsonPrimitive.content }
        for (md in cases) {
            var previous: LiteMarkdown.Parsed? = null
            for (end in 1..md.length) {
                val prefix = md.substring(0, end)
                previous = LiteMarkdown.parse(prefix, previous)
                assertEquals("prefix $end of ${md.take(40)}", LiteMarkdown.parse(prefix), previous.blocks)
            }
        }
    }

    private val pretty = Json { prettyPrint = true }
}

/** The fixture's tree format: text spans are strings, everything else a one-key object. */
object MdJson {
    fun blocks(blocks: List<MdBlock>): JsonArray = JsonArray(blocks.map(::block))

    private fun block(b: MdBlock): JsonElement = when (b) {
        is MdBlock.Paragraph -> buildJsonObject { put("p", spans(b.spans)) }
        is MdBlock.Heading -> buildJsonObject {
            put("h", b.level)
            put("spans", spans(b.spans))
        }
        is MdBlock.CodeBlock -> buildJsonObject {
            put("code", b.code)
            put("lang", b.language?.let(::JsonPrimitive) ?: JsonNull)
            put("closed", b.closed)
        }
        is MdBlock.Quote -> buildJsonObject { put("quote", blocks(b.blocks)) }
        is MdBlock.ListBlock -> buildJsonObject {
            put(
                "list",
                JsonArray(
                    b.items.map { item ->
                        buildJsonObject {
                            item.task?.let { put("task", it) }
                            put("blocks", blocks(item.blocks))
                        }
                    },
                ),
            )
            put("ordered", b.ordered)
            put("start", b.start)
        }
        MdBlock.Rule -> buildJsonObject { put("hr", true) }
        is MdBlock.Table -> buildJsonObject {
            put(
                "table",
                buildJsonObject {
                    put("align", JsonArray(b.align.map { JsonPrimitive(it.name.lowercase()) }))
                    put("header", JsonArray(b.header.map(::spans)))
                    put("rows", JsonArray(b.rows.map { row -> JsonArray(row.map(::spans)) }))
                },
            )
        }
    }

    fun spans(spans: List<MdSpan>): JsonArray = JsonArray(spans.map(::span))

    private fun span(s: MdSpan): JsonElement = when (s) {
        is MdSpan.Text -> JsonPrimitive(s.text)
        is MdSpan.InlineCode -> buildJsonObject { put("code", s.code) }
        is MdSpan.Math -> buildJsonObject { put("math", s.tex) }
        is MdSpan.Strong -> buildJsonObject { put("strong", spans(s.children)) }
        is MdSpan.Emphasis -> buildJsonObject { put("em", spans(s.children)) }
        is MdSpan.Strike -> buildJsonObject { put("del", spans(s.children)) }
        is MdSpan.Link -> buildJsonObject {
            put("link", s.url)
            put("spans", spans(s.children))
        }
        is MdSpan.Image -> buildJsonObject {
            put("image", s.url)
            put("alt", s.alt)
        }
    }
}

private fun JsonObject.getValue(key: String): JsonElement = get(key) ?: error("missing $key")
