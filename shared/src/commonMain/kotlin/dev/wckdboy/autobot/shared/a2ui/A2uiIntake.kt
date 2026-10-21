package dev.wckdboy.autobot.shared.a2ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What [A2uiIntake.accept] made of a model's `show_ui` argument: the messages to validate, as a
 * JSON array text ([text]), and one short line per repair made on the way ([repairs]). When
 * nothing could be read, [text] is null and [problem] says why in plain words.
 */
class A2uiIntakeResult internal constructor(
    val text: String?,
    val messages: JsonArray?,
    val repairs: List<String>,
    val problem: String?,
)

/**
 * The lenient front door of `show_ui`, in front of the strict [A2uiValidator]: it reads what a
 * small model sends and repairs only what has one obvious reading, recording each repair so the
 * model is told (and learns). The validator then judges the result exactly as the spec says.
 *
 * Repairs, in order:
 * - text: a code fence; a JSON string holding the JSON; brackets left open at the end (or before
 *   the final closer), or extra closers at the end, when that is the only parse problem;
 * - shape: one message object without the outer array; a bare components list (or one component
 *   tree) wrapped in a `createSurface`; a `createSurface` body sent without its envelope;
 * - envelope: `version` missing (or `1.0`, `v1`) becomes `v1.0`; `catalogId` missing or unknown
 *   becomes the chat's catalog when it has exactly one; a missing new `surfaceId` is generated;
 * - components: inlined child objects are hoisted into the flat list with generated ids; a
 *   Button whose `child` is label text (not an id) or that has a `label`/`text` gets a Text
 *   child; a ChoicePicker's literal string `value` (and a string at its bound data path) becomes
 *   a list.
 *
 * Pure (no state between calls) and bounded by [A2uiLimits]: the stream size, JSON depth and
 * components per surface.
 */
object A2uiIntake {
    /** Reads [input]: a JSON array of messages (the declared form), one message, or text (JSONL or JSON). */
    fun accept(
        input: JsonElement,
        catalogs: List<A2uiCatalog> = listOf(A2uiCatalog.basic),
        limits: A2uiLimits = A2uiLimits.standard,
        /** Surfaces shown earlier in the chat: id → its component ids. */
        prior: Map<String, Set<String>> = emptyMap(),
    ): A2uiIntakeResult {
        val notes = Notes()
        val parsed: JsonElement = when {
            input is JsonPrimitive && input.isString -> when (val r = readText(input.content, limits, notes)) {
                is Read.Ok -> r.value
                is Read.Fail -> return A2uiIntakeResult(null, null, notes.lines(), r.why)
            }
            input == JsonNull -> return A2uiIntakeResult(null, null, emptyList(), "messages is empty; send a JSON array of A2UI messages")
            else -> input
        }
        val shape = checkJsonShape(parsed.toJsonText(), limits.maxStreamChars, limits.maxJsonDepth + 2)
        if (shape != null) return A2uiIntakeResult(null, null, notes.lines(), shape)
        val items = topLevel(parsed, notes) ?: return A2uiIntakeResult(
            null, null, notes.lines(),
            "messages must be a JSON array of A2UI messages, got ${jsonTypeName(parsed)}",
        )
        if (items.size > limits.maxMessages) {
            return A2uiIntakeResult(null, null, notes.lines(), "${items.size} messages; the limit is ${limits.maxMessages}")
        }
        val out = Repairer(catalogs, limits, prior, notes).messages(items)
        val array = JsonArray(out)
        return A2uiIntakeResult(array.toJsonText(), array, notes.lines(), null)
    }

    /** [accept] for a text argument. */
    fun accept(text: String, catalogs: List<A2uiCatalog> = listOf(A2uiCatalog.basic), limits: A2uiLimits = A2uiLimits.standard): A2uiIntakeResult =
        accept(JsonPrimitive(text), catalogs, limits)

    // ---------------------------------------------------------------------------------------
    // Text

    private sealed interface Read {
        class Ok(val value: JsonElement) : Read
        class Fail(val why: String) : Read
    }

    private fun readText(raw: String, limits: A2uiLimits, notes: Notes, nested: Boolean = false): Read {
        if (raw.length > limits.maxStreamChars) return Read.Fail("messages is ${raw.length} characters; the limit is ${limits.maxStreamChars}")
        val body = stripFence(raw.trim())
        if (body.isEmpty()) return Read.Fail("messages is empty; send a JSON array of A2UI messages")
        checkJsonShape(body, limits.maxStreamChars, limits.maxJsonDepth + 2)?.let { return Read.Fail(it) }
        parse(body)?.let { e ->
            if (!nested && e is JsonPrimitive && e.isString) {
                notes.add("string", "messages was a string holding JSON text; send the array itself")
                return readText(e.content, limits, notes, nested = true)
            }
            return Read.Ok(e)
        }
        // JSON lines: every line a value.
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size > 1 && !body.startsWith("[")) {
            val values = lines.map { parse(it) }
            if (values.all { it != null }) return Read.Ok(JsonArray(values.filterNotNull()))
            if (parse(lines.first()) != null) {
                val bad = values.indexOfFirst { it == null }
                return closeOrExplain(lines[bad], notes, "line ${bad + 1}: ")?.let { fixed ->
                    Read.Ok(JsonArray(lines.mapIndexed { i, l -> if (i == bad) fixed else parse(l) ?: return Read.Fail(explain(l, "line ${i + 1}: ")) }))
                } ?: Read.Fail(explain(lines[bad], "line ${bad + 1}: "))
            }
        }
        return closeOrExplain(body, notes, "")?.let { Read.Ok(it) } ?: Read.Fail(explain(body, ""))
    }

    /** [text] with its brackets closed, parsed, when closing them is the only problem. */
    private fun closeOrExplain(text: String, notes: Notes, where: String): JsonElement? {
        val fix = closeBrackets(text) ?: return null
        val e = parse(fix.text) ?: return null
        notes.add("brackets", where + fix.note + " (close every { and [ you open)")
        return e
    }

    internal class BracketFix(val text: String, val note: String)

    /**
     * Closes brackets left open at the end, or right before the final closers, and drops extra
     * closers at the end. Null when the text has another problem (a string left open, a closer
     * in the middle that matches nothing, nothing to fix).
     */
    internal fun closeBrackets(text: String): BracketFix? {
        val s = text.trimEnd()
        val out = StringBuilder(s.length + 8)
        val stack = ArrayList<Char>()
        val inserted = ArrayList<Char>()
        var insertedBefore: Char? = null
        var dropped = 0
        var droppedChar = ' '
        var inString = false
        var escaped = false
        for (i in s.indices) {
            val c = s[i]
            if (inString) {
                out.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> stack += c
                '}', ']' -> {
                    val open = if (c == '}') '{' else '['
                    if (stack.lastOrNull() == open) {
                        stack.removeAt(stack.lastIndex)
                    } else {
                        if (!onlyClosers(s, i)) return null
                        val at = stack.lastIndexOf(open)
                        if (at < 0) {
                            dropped++
                            droppedChar = c
                            continue
                        }
                        while (stack.size > at + 1) {
                            val closer = closerOf(stack.removeAt(stack.lastIndex))
                            out.append(closer)
                            inserted += closer
                        }
                        if (insertedBefore == null) insertedBefore = c
                        stack.removeAt(stack.lastIndex)
                    }
                }
            }
            out.append(c)
        }
        if (inString) return null
        val atEnd = ArrayList<Char>()
        while (stack.isNotEmpty()) {
            val closer = closerOf(stack.removeAt(stack.lastIndex))
            out.append(closer)
            atEnd += closer
        }
        if (inserted.isEmpty() && atEnd.isEmpty() && dropped == 0) return null
        val parts = ArrayList<String>()
        if (inserted.isNotEmpty()) parts += "added ${count(inserted)} missing before the final '$insertedBefore'"
        if (atEnd.isNotEmpty()) parts += "added ${count(atEnd)} missing at the end"
        if (dropped > 0) parts += "removed $dropped extra '$droppedChar' at the end"
        return BracketFix(out.toString(), "the JSON was not closed: " + parts.joinToString("; "))
    }

    private fun count(chars: List<Char>): String =
        chars.groupingBy { it }.eachCount().entries.joinToString(" and ") { (c, n) -> "$n '$c'" }

    private fun closerOf(open: Char) = if (open == '{') '}' else ']'

    private fun onlyClosers(s: String, from: Int): Boolean {
        for (j in from until s.length) if (s[j] != '}' && s[j] != ']' && !s[j].isWhitespace()) return false
        return true
    }

    /** Why [text] is not JSON, in plain words, with a short excerpt around the place. */
    internal fun explain(text: String, where: String): String {
        val s = text.trimEnd()
        // Strings and brackets first: they say what to fix better than the parser's offset.
        var inString = false
        var escaped = false
        var stringStart = -1
        var curly = 0
        var square = 0
        for (i in s.indices) {
            val c = s[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> {
                    inString = true
                    stringStart = i
                }
                '{' -> curly++
                '}' -> curly--
                '[' -> square++
                ']' -> square--
            }
        }
        val lines = ArrayList<String>()
        if (inString) {
            lines += "a string is not closed: the one starting at char $stringStart (${excerpt(s, stringStart)}) has no closing \"; escape quotes inside strings as \\\""
        }
        val offset = parseError(s)
        if (!inString && offset != null) {
            val (at, expected) = offset
            val what = expected?.let { "; $it" }.orEmpty()
            lines += "JSON syntax error at char $at$what: ${excerpt(s, at)}"
        }
        if (curly > 0) lines += "$curly '}' ${if (curly == 1) "is" else "are"} missing"
        if (curly < 0) lines += "${-curly} '}' too many"
        if (square > 0) lines += "$square ']' ${if (square == 1) "is" else "are"} missing"
        if (square < 0) lines += "${-square} ']' too many"
        if (Regex("[{,]\\s*'[^']*'\\s*:").containsMatchIn(s)) lines += "use double quotes (\") for keys and strings, not single quotes"
        if (lines.isEmpty()) lines += "not valid JSON: ${excerpt(s, 0)}"
        return "messages is not valid JSON. " + where + lines.joinToString("; ")
    }

    /** The parser's error offset and what it expected there, in words. */
    private fun parseError(s: String): Pair<Int, String?>? {
        val message = try {
            Json.parseToJsonElement(s)
            return null
        } catch (e: Exception) {
            e.message.orEmpty()
        }
        val m = Regex("offset (\\d+)(?::\\s*([^\\n]*))?").find(message) ?: return null
        val at = m.groupValues[1].toIntOrNull()?.coerceIn(0, s.length) ?: return null
        val detail = m.groupValues.getOrNull(2).orEmpty().substringBefore(" at path").trim()
        val words = when {
            detail.startsWith("Expected end of the object or comma") -> "expected ',' or '}' here"
            detail.startsWith("Expected end of the array or comma") -> "expected ',' or ']' here"
            detail.startsWith("Expected colon") || detail.contains("Expected colon") -> "expected ':' after the key"
            detail.startsWith("Expected quotation mark") || detail.startsWith("Expected beginning of the string") ->
                "expected a \"quoted\" key here (is a '}' missing before it?)"
            detail.startsWith("Trailing comma") -> "remove the comma before the closing bracket"
            detail.startsWith("Expected EOF") -> "the JSON goes on after its end; send one array"
            detail.isNotEmpty() -> detail.replaceFirstChar { it.lowercaseChar() }.take(80)
            else -> null
        }
        return at to words
    }

    /** About 24 characters on each side of [at], with `⟪here⟫` at the place. */
    internal fun excerpt(s: String, at: Int): String {
        val a = (at - 24).coerceAtLeast(0)
        val b = (at + 24).coerceAtMost(s.length)
        val before = s.substring(a, at.coerceIn(a, b)).replace('\n', ' ')
        val after = s.substring(at.coerceIn(a, b), b).replace('\n', ' ')
        return (if (a > 0) "…" else "") + before + "⟪here⟫" + after + (if (b < s.length) "…" else "")
    }

    private fun parse(text: String): JsonElement? = try {
        Json.parseToJsonElement(text)
    } catch (_: Exception) {
        null
    }

    private fun stripFence(text: String): String {
        if (!text.startsWith("```")) return text
        val firstNewline = text.indexOf('\n')
        if (firstNewline < 0) return text
        return text.substring(firstNewline + 1).trimEnd().removeSuffix("```").trim()
    }

    // ---------------------------------------------------------------------------------------
    // Shape

    private fun isComponent(o: JsonElement): Boolean =
        o is JsonObject && o["component"]?.isJsonString() == true && A2uiMessage.KINDS.none { it in o }

    /** The message list of [e], or null when it is no list of anything. */
    private fun topLevel(e: JsonElement, notes: Notes): List<JsonElement>? = when {
        e is JsonArray -> when {
            e.isNotEmpty() && e.all { isComponent(it) } -> listOf(wrapComponents(e.toList(), notes))
            else -> e.toList()
        }
        e !is JsonObject -> null
        A2uiMessage.kindOf(e) == null && e["messages"] is JsonArray -> topLevel(e["messages"]!!, notes)
        isComponent(e) -> listOf(wrapComponents(listOf(e), notes))
        else -> listOf(e)
    }

    /** Marker for a createSurface the intake builds around bare components (its surfaceId and catalogId are filled in later). */
    private const val WRAPPED = "\u0000wrapped"

    private fun wrapComponents(components: List<JsonElement>, notes: Notes): JsonObject {
        notes.add("wrap", "the components were not in a message; wrapped them in {\"version\":\"v1.0\",\"createSurface\":{\"surfaceId\":…,\"catalogId\":…,\"components\":[…]}}")
        return JsonObject(
            linkedMapOf(
                "version" to JsonPrimitive(A2uiMessage.VERSION),
                A2uiMessage.CREATE_SURFACE to JsonObject(linkedMapOf("components" to JsonArray(components), WRAPPED to JsonPrimitive(true))),
            ),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Messages and components

    private class Repairer(
        private val catalogs: List<A2uiCatalog>,
        private val limits: A2uiLimits,
        private val prior: Map<String, Set<String>>,
        private val notes: Notes,
    ) {
        private val single: A2uiCatalog? = catalogs.singleOrNull()
        private val usedSurfaceIds = HashSet<String>(prior.keys)

        /** Component ids per surface as the stream goes, for updateComponents. */
        private val known = HashMap<String, MutableSet<String>>().apply { prior.forEach { (k, v) -> put(k, v.toMutableSet()) } }

        fun messages(items: List<JsonElement>): List<JsonElement> {
            items.forEach { m ->
                val body = (m as? JsonObject)?.get(A2uiMessage.CREATE_SURFACE) as? JsonObject
                body?.get("surfaceId")?.stringOrNull()?.let { usedSurfaceIds += it }
                if (m is JsonObject && A2uiMessage.kindOf(m) == null && looksLikeBody(m)) m["surfaceId"]?.stringOrNull()?.let { usedSurfaceIds += it }
            }
            return items.map { message(it) }
        }

        private fun looksLikeBody(o: JsonObject) = o["components"] is JsonArray && ("surfaceId" in o || "catalogId" in o || o.size == 1 || "dataModel" in o)

        private fun message(e: JsonElement): JsonElement {
            var m = e as? JsonObject ?: return e
            if (A2uiMessage.kindOf(m) == null && A2uiMessage.KINDS.none { it in m } && looksLikeBody(m)) {
                val sid = m["surfaceId"]?.stringOrNull()
                val kind = if (sid != null && sid in prior && "catalogId" !in m) A2uiMessage.UPDATE_COMPONENTS else A2uiMessage.CREATE_SURFACE
                notes.add("envelope", "a $kind body was sent without its message; wrapped it as {\"version\":\"v1.0\",\"$kind\":{…}}")
                val body = if (kind == A2uiMessage.UPDATE_COMPONENTS) JsonObject(m.filterKeys { it == "surfaceId" || it == "components" }) else m
                m = JsonObject(linkedMapOf("version" to JsonPrimitive(A2uiMessage.VERSION), kind to body))
            }
            m = version(m)
            val kind = A2uiMessage.kindOf(m) ?: return m
            val body = m[kind] as? JsonObject ?: return m
            val fixed = when (kind) {
                A2uiMessage.CREATE_SURFACE -> createSurface(body)
                A2uiMessage.UPDATE_COMPONENTS -> updateComponents(body)
                else -> body
            }
            return if (fixed === body) m else JsonObject(m + (kind to fixed))
        }

        private fun version(m: JsonObject): JsonObject {
            val v = m["version"]
            if (v?.stringOrNull() == A2uiMessage.VERSION) return m
            val sloppy = v == null || v.stringOrNull()?.trim()?.lowercase()?.removePrefix("v") in setOf("1", "1.0", "1.0.0") ||
                (v.isJsonNumber() && v.numberOrNull() == 1.0)
            if (!sloppy) return m
            notes.add("version", "set \"version\":\"v1.0\" on every message")
            return JsonObject(linkedMapOf<String, JsonElement>("version" to JsonPrimitive(A2uiMessage.VERSION)).apply { putAll(m - "version") })
        }

        private fun createSurface(body: JsonObject): JsonObject {
            val map = LinkedHashMap(body)
            val wrapped = map.remove(WRAPPED) != null
            if (map["surfaceId"]?.stringOrNull().isNullOrEmpty()) {
                val id = newSurfaceId()
                map["surfaceId"] = JsonPrimitive(id)
                if (!wrapped) notes.add("surfaceId", "createSurface had no surfaceId; used '$id'")
            }
            val catalogId = map["catalogId"]?.stringOrNull()
            val catalog = catalogs.firstOrNull { it.catalogId == catalogId } ?: single?.also {
                map["catalogId"] = JsonPrimitive(it.catalogId)
                if (!wrapped) {
                    if (catalogId == null) notes.add("catalog", "createSurface had no catalogId; set it to the chat's catalog")
                    else notes.add("catalog", "catalogId '${catalogId.take(60)}' is not a known catalog; set it to the chat's catalog (copy it exactly)")
                }
            }
            val surfaceId = map["surfaceId"]!!.stringOrNull()!!
            val components = map["components"] as? JsonArray
            if (catalog != null && components != null) {
                val result = Components(catalog, limits, emptySet(), notes).repair(components, makeRoot = wrapped)
                map["components"] = result
                known.getOrPut(surfaceId) { HashSet() } += result.mapNotNull { (it as? JsonObject)?.get("id")?.stringOrNull() }
                (map["dataModel"] as? JsonObject)?.let { dm -> choiceModel(result, dm)?.let { map["dataModel"] = it } }
            }
            return JsonObject(reorder(map))
        }

        private fun updateComponents(body: JsonObject): JsonObject {
            val surfaceId = body["surfaceId"]?.stringOrNull() ?: return body
            val components = body["components"] as? JsonArray ?: return body
            val catalog = single ?: return body
            val ids = known.getOrPut(surfaceId) { HashSet() }
            val result = Components(catalog, limits, ids.toSet(), notes).repair(components, makeRoot = false)
            ids += result.mapNotNull { (it as? JsonObject)?.get("id")?.stringOrNull() }
            return if (result == components) body else JsonObject(body + ("components" to result))
        }

        /** A ChoicePicker bound to an absolute path whose start value is a string: the value as a list. */
        private fun choiceModel(components: JsonArray, model: JsonObject): JsonObject? {
            var dm: JsonElement = model
            for (c in components) {
                val o = c as? JsonObject ?: continue
                if (o["component"]?.stringOrNull() != "ChoicePicker") continue
                val path = (o["value"] as? JsonObject)?.get("@path")?.stringOrNull() ?: continue
                if (!path.startsWith("/") || !JsonPointer.isValid(path)) continue
                val v = try {
                    JsonPointer.get(dm, path)
                } catch (_: Exception) {
                    null
                } ?: continue
                if (!v.isJsonString()) continue
                val s = v.stringOrNull()!!
                dm = try {
                    JsonPointer.set(dm, path, if (s.isEmpty()) JsonArray(emptyList()) else JsonArray(listOf(v)))
                } catch (_: Exception) {
                    continue
                }
                notes.add("choiceModel", "a ChoicePicker's value is a list: its dataModel start value became ${if (s.isEmpty()) "[]" else "[\"$s\"]"}")
            }
            return (dm as? JsonObject)?.takeIf { it != model }
        }

        private fun reorder(map: Map<String, JsonElement>): Map<String, JsonElement> {
            val order = listOf("surfaceId", "catalogId")
            return LinkedHashMap<String, JsonElement>().apply {
                order.forEach { k -> map[k]?.let { put(k, it) } }
                map.forEach { (k, v) -> if (k !in order) put(k, v) }
            }
        }

        private fun newSurfaceId(): String {
            var n = 1
            while (true) {
                val id = if (n == 1) "ui" else "ui_$n"
                if (usedSurfaceIds.add(id)) return id
                n++
            }
        }
    }

    /** Repairs of one components list. */
    private class Components(
        private val catalog: A2uiCatalog,
        private val limits: A2uiLimits,
        known: Set<String>,
        private val notes: Notes,
    ) {
        private val ids = HashSet<String>(known)
        private val out = ArrayList<JsonElement>()

        fun repair(list: JsonArray, makeRoot: Boolean): JsonArray {
            list.forEach { (it as? JsonObject)?.get("id")?.stringOrNull()?.let { id -> ids += id } }
            for (c in list) {
                if (c is JsonObject && out.size < limits.maxComponentsPerSurface) component(c, 0) else out += c
            }
            if (makeRoot) giveRoot()
            return JsonArray(out)
        }

        /** A wrapped list without a "root": its one top component (referenced by no other) becomes root. */
        private fun giveRoot() {
            if (out.any { (it as? JsonObject)?.get("id")?.stringOrNull() == "root" }) return
            val referenced = HashSet<String>()
            out.forEach { c -> (c as? JsonObject)?.let { collectRefs(it, referenced) } }
            val tops = out.withIndex().filter { (_, c) -> (c as? JsonObject)?.get("id")?.stringOrNull()?.let { it !in referenced } ?: (c is JsonObject) }
            val (i, top) = tops.singleOrNull() ?: return
            out[i] = JsonObject(linkedMapOf<String, JsonElement>("id" to JsonPrimitive("root")).apply { putAll((top as JsonObject) - "id") })
            notes.add("root", "no component had id \"root\"; the top one became \"root\"")
        }

        private fun collectRefs(c: JsonObject, into: MutableSet<String>) {
            val spec = catalog.component(c["component"]?.stringOrNull() ?: return) ?: return
            for (slot in spec.slots) at(c, slot.path) { v ->
                v.stringOrNull()?.let { into += it }
                (v as? JsonArray)?.forEach { e -> e.stringOrNull()?.let { into += it } }
                (v as? JsonObject)?.get("componentId")?.stringOrNull()?.let { into += it }
            }
        }

        /** Repairs [c] and appends it (after the children it hoists out of itself). */
        private fun component(c: JsonObject, depth: Int) {
            var o = c
            val type = o["component"]?.stringOrNull()
            val spec = type?.let { catalog.component(it) }
            val selfId = o["id"]?.stringOrNull() ?: "c"
            val hoisted = ArrayList<JsonObject>()
            if (spec != null && depth < limits.maxJsonDepth) {
                for (slot in spec.slots) o = rewrite(o, slot.path, 0) { v, name -> hoistIn(v, slot.isList, selfId, name, hoisted) }
            }
            if (type == "Button") o = button(o, selfId, hoisted)
            if (type == "ChoicePicker") {
                val v = o["value"]
                if (v != null && v.isJsonString()) {
                    o = JsonObject(o + ("value" to JsonArray(listOf(v))))
                    notes.add("choice", "a ChoicePicker's value is a list of values; wrapped the string in [ ]")
                }
            }
            out += o
            for (h in hoisted) {
                if (out.size >= limits.maxComponentsPerSurface) break
                component(h, depth + 1)
            }
        }

        private fun hoistIn(v: JsonElement, isList: Boolean, parentId: String, slotName: String, hoisted: MutableList<JsonObject>): JsonElement {
            fun hoist(e: JsonElement, base: String): JsonElement {
                if (e !is JsonObject || e["component"]?.isJsonString() != true) return e
                val own = e["id"]?.stringOrNull()
                val id = if (own != null && own !in ids) own.also { ids += it } else newId(base)
                hoisted += JsonObject(linkedMapOf<String, JsonElement>("id" to JsonPrimitive(id)).apply { putAll(e - "id") })
                notes.add("hoist", "children are ids, not objects: moved inlined components into the list (ids like '$id')")
                return JsonPrimitive(id)
            }
            return when {
                isList && v is JsonArray -> JsonArray(v.mapIndexed { i, e -> hoist(e, "${parentId}_${i + 1}") })
                !isList -> hoist(v, "${parentId}_$slotName")
                else -> v
            }
        }

        private fun button(b: JsonObject, id: String, hoisted: MutableList<JsonObject>): JsonObject {
            if (catalog.component("Text") == null) return b
            val child = b["child"]
            val label: String?
            var o = b
            when {
                child == null -> {
                    val key = listOf("label", "text").firstOrNull { b[it]?.isJsonString() == true } ?: return b
                    label = b[key]!!.stringOrNull()
                    o = JsonObject(b - key)
                    notes.add("button", "a Button's label is its own Text: moved Button '$id''s $key into a Text child")
                }
                child.isJsonString() && child.stringOrNull()!! !in ids && looksLikeLabel(child.stringOrNull()!!) -> {
                    label = child.stringOrNull()
                    notes.add("button", "a Button's child is the id of a Text: Button '$id''s child \"${label!!.take(40)}\" became a Text component")
                }
                else -> return b
            }
            val textId = newId("${id}_label")
            hoisted += JsonObject(linkedMapOf("id" to JsonPrimitive(textId), "component" to JsonPrimitive("Text"), "text" to JsonPrimitive(label!!)))
            return JsonObject(LinkedHashMap(o).apply { put("child", JsonPrimitive(textId)) })
        }

        private fun newId(base: String): String {
            val clean = base.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48).ifEmpty { "c" }
            var n = 1
            while (true) {
                val id = if (n == 1) clean else "${clean}_$n"
                if (ids.add(id)) return id
                n++
            }
        }

        /** Applies [f] to the value at [path] (`*` = every array item) in [o]; the last token names the slot. */
        private fun rewrite(o: JsonElement, path: List<String>, i: Int, f: (JsonElement, String) -> JsonElement): JsonObject {
            return rewriteAny(o, path, i, f) as JsonObject
        }

        private fun rewriteAny(e: JsonElement, path: List<String>, i: Int, f: (JsonElement, String) -> JsonElement): JsonElement {
            if (i == path.size) return f(e, path.lastOrNull { it != "*" } ?: "child")
            val t = path[i]
            return when {
                t == "*" && e is JsonArray -> JsonArray(e.map { rewriteAny(it, path, i + 1, f) })
                e is JsonObject && t in e -> {
                    val v = e[t]!!
                    val nv = rewriteAny(v, path, i + 1, f)
                    if (nv === v) e else JsonObject(LinkedHashMap(e).apply { put(t, nv) })
                }
                else -> e
            }
        }

        private fun at(e: JsonElement, path: List<String>, f: (JsonElement) -> Unit) {
            if (path.isEmpty()) return f(e)
            val t = path.first()
            when {
                t == "*" && e is JsonArray -> e.forEach { at(it, path.drop(1), f) }
                e is JsonObject -> e[t]?.let { at(it, path.drop(1), f) }
                else -> Unit
            }
        }
    }

    /**
     * A string that is label text rather than a component id: it has a space, starts with a
     * capital, or has characters ids do not use. `go_t` and `submitLabel` read as ids (a missing
     * id is reported, not guessed); `Confirm` and `Book it!` read as labels.
     */
    internal fun looksLikeLabel(s: String): Boolean =
        s.isNotBlank() && (s.any { it.isWhitespace() } || s.first().isUpperCase() || s.any { !(it.isLetterOrDigit() || it == '_' || it == '-') })

    /** Repairs by kind, each said once (with a count when it happened more than once). */
    private class Notes {
        private val byKind = LinkedHashMap<String, Pair<String, Int>>()

        fun add(kind: String, line: String) {
            val prev = byKind[kind]
            byKind[kind] = (prev?.first ?: line) to ((prev?.second ?: 0) + 1)
        }

        fun lines(): List<String> = byKind.values.map { (line, n) -> if (n > 1) "$line (×$n)" else line }
    }
}
