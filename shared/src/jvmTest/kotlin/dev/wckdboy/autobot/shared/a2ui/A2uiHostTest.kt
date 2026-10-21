package dev.wckdboy.autobot.shared.a2ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BASIC = A2uiCatalog.BASIC_CATALOG_ID

internal class RecordingListener : A2uiListener {
    val updates = ArrayList<Pair<String, RenderNode?>>()
    val deleted = ArrayList<String>()
    val errors = ArrayList<A2uiError>()
    override fun onSurfaceUpdated(surfaceId: String, root: RenderNode?) {
        updates += surfaceId to root
    }
    override fun onSurfaceDeleted(surfaceId: String) {
        deleted += surfaceId
    }
    override fun onErrors(errors: List<A2uiError>) {
        this.errors += errors
    }
}

internal fun RenderNode.find(id: String): RenderNode? =
    if (this.id == id) this else children.firstNotNullOfOrNull { it.find(id) }

internal fun RenderNode.findAll(id: String): List<RenderNode> =
    (if (this.id == id) listOf(this) else emptyList()) + children.flatMap { it.findAll(id) }

class A2uiHostTest {
    private val fixedTime = 1_780_390_643_000L // 2026-06-02T08:57:23Z
    private val host = A2uiHost(listOf(A2uiCatalog.basic), A2uiLimits.standard) { fixedTime }
    private val listener = RecordingListener().also { host.addListener(it) }

    private val contactForm = a2uiResource("cases/contact_form_example.jsonl").lines().filter { it.isNotBlank() }

    @Test
    fun contactFormStreamRendersBindsAndActs() {
        // createSurface, updateComponents, updateDataModel; leave out the final deleteSurface.
        assertEquals(emptyList<A2uiError>(), host.receive(contactForm.take(3).joinToString("\n")))
        assertEquals(listOf("contact_form_1"), host.surfaceIds)
        assertEquals(listOf("contact_form_1"), listener.updates.map { it.first })

        val root = assertNotNull(host.render("contact_form_1")).let { host.render("contact_form_1")!! }
        assertEquals("Card", root.type)
        val column = root.node("child")!!
        assertEquals("Column", column.type)
        assertEquals(
            listOf("header_row", "name_row", "email_group", "phone_group", "pref_group", "divider_1", "newsletter_checkbox", "submit_button"),
            column.nodes("children").map { it.id },
        )
        assertEquals("stretch", column.string("align"))
        assertEquals("mail", root.find("header_icon")!!.string("name"))
        assertEquals("# Contact Us", root.find("header_text")!!.string("text"))

        val first = root.find("first_name_field")!!
        assertEquals("TextField", first.type)
        assertEquals("John", first.string("value"))
        assertEquals("/contact/firstName", first.binding("value"))
        assertEquals(1.0, root.find("first_name_group")!!.number("weight"))

        val email = root.find("email_field")!!
        assertTrue(email.isValid)
        assertEquals(listOf(true, true), email.checks.map { it.valid })
        assertEquals(listOf("email", "phone", "sms"), (root.find("pref_picker")!!.value("options") as RenderValue.ListValue).items.map { (it as RenderValue.ObjectValue).fields["value"]!!.asText() })
        assertEquals(listOf("email"), root.find("pref_picker")!!.strings("value"))
        assertEquals(true, root.find("newsletter_checkbox")!!.bool("value"))
        val button = root.find("submit_button")!!
        assertEquals("Send Message", button.node("child")!!.string("text"))
        assertEquals("submitContactForm", button.action!!.eventName)

        // Typing a bad email: the model updates, the check fails, the label stays in sync.
        listener.updates.clear()
        assertTrue(host.setString(email, "value", "not-an-email"))
        val after = listener.updates.single().second!!
        val email2 = after.find("email_field")!!
        assertEquals("not-an-email", email2.string("value"))
        assertFalse(email2.isValid)
        assertEquals("Please enter a valid email address.", email2.firstError)
        assertEquals("\"not-an-email\"", host.surface("contact_form_1")!!.readJson("/contact/email"))

        // Toggle the checkbox, then submit: the context resolves now.
        assertTrue(host.setBoolean(after.find("newsletter_checkbox")!!, "value", false))
        val result = host.dispatch(host.render("contact_form_1")!!.find("submit_button")!!)
        result as A2uiActionResult.SendEvent
        assertEquals("submitContactForm", result.name)
        assertNull(result.dataModelJson)
        val msg = Json.parseToJsonElement(result.messageJson).jsonObject
        assertEquals("v1.0", msg["version"]!!.jsonPrimitive.content)
        val action = msg["action"]!!.jsonObject
        assertEquals("contact_form_1", action["surfaceId"]!!.jsonPrimitive.content)
        assertEquals("submit_button", action["sourceComponentId"]!!.jsonPrimitive.content)
        assertEquals("2026-06-02T08:57:23.000Z", action["timestamp"]!!.jsonPrimitive.content)
        assertEquals(
            Json.parseToJsonElement("""{"formId":"contact_form_1","clientTime":"Mon Feb 2, 2026 3:17 PM","isNewsletterSubscribed":false}"""),
            action["context"],
        )
        // The action message is valid against renderer_to_agent.json.
        val schemas = SchemaSet().add("common_types.json", a2uiJson("json/common_types.json"))
            .add("renderer_to_agent.json", a2uiJson("json/renderer_to_agent.json"))
            .add("catalog.json", a2uiJson("catalogs/basic/catalog.json"))
        assertEquals(emptyList<SchemaError>(), JsonSchemaValidator(schemas).validate(msg, "renderer_to_agent.json"))

        // The last line deletes the surface.
        assertEquals(emptyList<A2uiError>(), host.receive(contactForm[3]))
        assertEquals(listOf("contact_form_1"), listener.deleted)
        assertTrue(host.surfaceIds.isEmpty())
    }

    @Test
    fun templatesUseRelativePathsAndIndex() {
        val errors = host.receive(
            """
            {"version":"v1.0","createSurface":{"surfaceId":"list","catalogId":"$BASIC","components":[
              {"id":"root","component":"Column","children":["title","people"]},
              {"id":"title","component":"Text","text":{"@path":"/company"}},
              {"id":"people","component":"List","children":{"path":"/employees","componentId":"row"}},
              {"id":"row","component":"Row","children":["n","name","co","pick"]},
              {"id":"n","component":"Text","text":{"@call":"formatString","args":{"value":"${'$'}{@index(offset: 1)}."}}},
              {"id":"name","component":"Text","text":{"@path":"name"}},
              {"id":"co","component":"Text","text":{"@call":"formatString","args":{"value":"${'$'}{role} at ${'$'}{/company}"}}},
              {"id":"pick","component":"Button","child":"pickLabel","action":{"event":{"name":"pick","context":{"who":{"@path":"name"},"i":{"@call":"@index"}},"userMessage":{"@call":"formatString","args":{"value":"Picked ${'$'}{name}"}}}}},
              {"id":"pickLabel","component":"Text","text":"Pick"}
            ],"dataModel":{"company":"Acme","employees":[{"name":"Alice","role":"Engineer"},{"name":"Bob","role":"Designer"}]}}}
            """.trimIndent().replace("\n", ""),
        )
        assertEquals(emptyList<A2uiError>(), errors)
        val root = host.render("list")!!
        val rows = root.find("people")!!.nodes("children")
        assertEquals(listOf("row@/employees/0", "row@/employees/1"), rows.map { it.key })
        assertEquals(listOf("1.", "2."), rows.map { it.find("n")!!.string("text") })
        assertEquals(listOf("Alice", "Bob"), rows.map { it.find("name")!!.string("text") })
        assertEquals("Designer at Acme", rows[1].find("co")!!.string("text"))
        assertEquals("/employees/1/name", rows[1].find("name")!!.binding("text"))

        val r = host.dispatch(rows[1].find("pick")!!) as A2uiActionResult.SendEvent
        assertEquals("Picked Bob", r.userMessage)
        val action = Json.parseToJsonElement(r.messageJson).jsonObject["action"]!!.jsonObject
        assertEquals(Json.parseToJsonElement("""{"who":"Bob","i":1}"""), action["context"])
        assertEquals("Picked Bob", action["userMessage"]!!.jsonPrimitive.content)

        // Growing the list re-renders more rows; deleting a key removes it.
        host.receive("""{"version":"v1.0","updateDataModel":{"surfaceId":"list","path":"/employees/-","value":{"name":"Cy","role":"PM"}}}""")
        assertEquals(3, host.render("list")!!.find("people")!!.children.size)
        host.receive("""{"version":"v1.0","updateDataModel":{"surfaceId":"list","path":"/company","value":null}}""")
        assertEquals("", host.render("list")!!.find("title")!!.stringOr("text", ""))
        assertEquals("PM at ", host.render("list")!!.findAll("co")[2].string("text"))
    }

    @Test
    fun progressiveRenderingShowsPlaceholdersUntilChildrenArrive() {
        host.receive("""{"version":"v1.0","createSurface":{"surfaceId":"p","catalogId":"$BASIC"}}""")
        assertNull(host.render("p"))
        host.receive("""{"version":"v1.0","updateComponents":{"surfaceId":"p","components":[{"id":"root","component":"Card","child":"body"}]}}""")
        val root = host.render("p")!!
        assertTrue(root.node("child")!!.isPlaceholder)
        assertEquals(1, host.surface("p")!!.integrityErrors().size)
        host.receive("""{"version":"v1.0","updateComponents":{"surfaceId":"p","components":[{"id":"body","component":"Text","text":"hi"}]}}""")
        assertEquals("hi", host.render("p")!!.node("child")!!.string("text"))
        assertEquals(emptyList<A2uiError>(), host.surface("p")!!.integrityErrors())
        // Updating a component replaces it.
        host.receive("""{"version":"v1.0","updateComponents":{"surfaceId":"p","components":[{"id":"body","component":"Text","text":"bye","variant":"caption"}]}}""")
        assertEquals("bye", host.render("p")!!.node("child")!!.string("text"))
    }

    @Test
    fun tabsAndModalSlotsResolveToNodes() {
        val errors = host.receive(
            """{"version":"v1.0","createSurface":{"surfaceId":"t","catalogId":"$BASIC","components":[
            {"id":"root","component":"Tabs","tabs":[{"title":"One","child":"a"},{"title":{"@path":"/t2"},"child":"m"}]},
            {"id":"a","component":"Text","text":"A"},
            {"id":"m","component":"Modal","trigger":"open","content":"body"},
            {"id":"open","component":"Button","child":"openLabel","action":{"functionCall":{"@call":"openUrl","args":{"url":"https://example.com/x"}}}},
            {"id":"openLabel","component":"Text","text":"Open"},
            {"id":"body","component":"Text","text":"In the modal"}],"dataModel":{"t2":"Two"}}}""".replace("\n", ""),
        )
        assertEquals(emptyList<A2uiError>(), errors)
        val root = host.render("t")!!
        val tabs = (root.value("tabs") as RenderValue.ListValue).items.map { it as RenderValue.ObjectValue }
        assertEquals(listOf("One", "Two"), tabs.map { it.fields["title"]!!.asText() })
        assertEquals("a", (tabs[0].fields["child"] as RenderValue.NodeValue).node.id)
        assertEquals(listOf("a", "m"), root.children.map { it.id })
        val modal = root.find("m")!!
        assertEquals("open", modal.node("trigger")!!.id)
        assertEquals("In the modal", modal.node("content")!!.string("text"))
        val r = host.dispatch(modal.node("trigger")!!)
        assertEquals("https://example.com/x", (r as A2uiActionResult.OpenUrl).url)
    }

    @Test
    fun buttonsWithFailingChecksDoNothing() {
        host.receive(
            """{"version":"v1.0","createSurface":{"surfaceId":"b","catalogId":"$BASIC","sendDataModel":true,"components":[
            {"id":"root","component":"Column","children":["terms","go"]},
            {"id":"terms","component":"CheckBox","label":"I agree","value":{"@path":"/terms"}},
            {"id":"go","component":"Button","child":"goLabel","action":{"event":{"name":"submit"}},
             "checks":[{"condition":{"@call":"and","args":{"values":[{"@path":"/terms"},{"@call":"required","args":{"value":{"@path":"/name"}}}]}},"message":"Accept the terms and give a name"}]},
            {"id":"goLabel","component":"Text","text":"Go"}],"dataModel":{"terms":false,"name":"Ada"}}}""".replace("\n", ""),
        )
        val go = host.render("b")!!.find("go")!!
        assertFalse(go.isValid)
        assertEquals("Accept the terms and give a name", go.firstError)
        val refused = host.dispatch(go) as A2uiActionResult.Refused
        assertEquals("Accept the terms and give a name", refused.reason)

        host.setBoolean(host.render("b")!!.find("terms")!!, "value", true)
        val sent = host.dispatch(host.render("b")!!.find("go")!!) as A2uiActionResult.SendEvent
        assertEquals(
            Json.parseToJsonElement("""{"version":"v1.0","surfaces":{"b":{"terms":true,"name":"Ada"}}}"""),
            Json.parseToJsonElement(sent.dataModelJson!!),
        )
        assertEquals("{}", Json.parseToJsonElement(sent.messageJson).jsonObject["action"]!!.jsonObject["context"].toString())
    }

    @Test
    fun choicePickerAndSliderWriteBack() {
        host.receive(
            """{"version":"v1.0","createSurface":{"surfaceId":"c","catalogId":"$BASIC","components":[
            {"id":"root","component":"Column","children":["pick","vol","when"]},
            {"id":"pick","component":"ChoicePicker","variant":"multipleSelection","options":[{"label":"A","value":"a"},{"label":"B","value":"b"}],"value":{"@path":"/sel"}},
            {"id":"vol","component":"Slider","max":10,"value":{"@path":"/vol"}},
            {"id":"when","component":"DateTimeInput","enableDate":true,"value":{"@path":"/date"}}]}}""".replace("\n", ""),
        )
        val root = host.render("c")!!
        assertEquals(emptyList<String>(), root.find("pick")!!.strings("value"))
        assertTrue(host.setStrings(root.find("pick")!!, "value", listOf("a", "b")))
        assertTrue(host.setNumber(root.find("vol")!!, "value", 7.5))
        assertTrue(host.setString(root.find("when")!!, "value", "2026-10-21"))
        val r2 = host.render("c")!!
        assertEquals(listOf("a", "b"), r2.find("pick")!!.strings("value"))
        assertEquals(7.5, r2.find("vol")!!.numberOr("value", 0.0), 0.0)
        assertEquals(10.0, r2.find("vol")!!.numberOr("max", 0.0), 0.0)
        assertEquals("""{"sel":["a","b"],"vol":7.5,"date":"2026-10-21"}""", host.surface("c")!!.dataModelJson())
        // A literal (unbound) property cannot be written.
        assertFalse(host.setNumber(r2.find("vol")!!, "max", 3.0))
        assertTrue(host.setJson("c", "/sel", "null"))
        assertEquals("""{"vol":7.5,"date":"2026-10-21"}""", host.surface("c")!!.dataModelJson())
    }

    @Test
    fun versionIsChecked() {
        val e = host.receive("""{"version":"v0.9","createSurface":{"surfaceId":"x","catalogId":"$BASIC"}}""").single()
        assertEquals(A2uiErrorCode.VALIDATION_FAILED, e.code)
        assertEquals("/version", e.path)
        assertTrue(e.message, e.message.contains("v1.0"))
        val missing = host.receive("""{"createSurface":{"surfaceId":"x","catalogId":"$BASIC"}}""").single()
        assertTrue(missing.message.contains("missing \"version\""))
        assertTrue(host.surfaceIds.isEmpty())
    }

    @Test
    fun surfaceLifecycleErrors() {
        assertEquals(emptyList<A2uiError>(), host.receive("""{"version":"v1.0","createSurface":{"surfaceId":"s","catalogId":"$BASIC"}}"""))
        val dup = host.receive("""{"version":"v1.0","createSurface":{"surfaceId":"s","catalogId":"$BASIC"}}""").single()
        assertEquals(A2uiErrorCode.INTEGRITY_ERROR, dup.code)
        val unknown = host.receive("""{"version":"v1.0","updateComponents":{"surfaceId":"nope","components":[{"id":"root","component":"Text","text":"x"}]}}""").single()
        assertEquals(A2uiErrorCode.INTEGRITY_ERROR, unknown.code)
        val badCatalog = host.receive("""{"version":"v1.0","createSurface":{"surfaceId":"z","catalogId":"https://example.com/mine"}}""").single()
        assertTrue(badCatalog.message.contains("unknown catalog"))
        // Delete, then the id can be used again.
        host.receive("""{"version":"v1.0","deleteSurface":{"surfaceId":"s"}}""")
        assertEquals(emptyList<A2uiError>(), host.receive("""{"version":"v1.0","createSurface":{"surfaceId":"s","catalogId":"$BASIC"}}"""))
        // Without a catalog, components cannot be validated.
        host.receive("""{"version":"v1.0","createSurface":{"surfaceId":"nocat"}}""")
        val noCat = host.receive("""{"version":"v1.0","updateComponents":{"surfaceId":"nocat","components":[{"id":"root","component":"Text","text":"x"}]}}""").single()
        assertTrue(noCat.message.contains("catalogId"))
    }

    @Test
    fun rejectedMessagesChangeNothing() {
        host.receive("""{"version":"v1.0","createSurface":{"surfaceId":"s","catalogId":"$BASIC","components":[{"id":"root","component":"Text","text":"ok"}]}}""")
        val errors = host.receive(
            """{"version":"v1.0","updateComponents":{"surfaceId":"s","components":[{"id":"root","component":"Text","text":"changed"},{"id":"bad","component":"Text","text":5}]}}""",
        )
        assertEquals("/components/1/text", errors.single().path)
        assertEquals("ok", host.render("s")!!.string("text"))
        assertTrue(listener.errors.isNotEmpty())
    }

    @Test
    fun functionCallMessagesAreRefusedPolitely() {
        val e = host.receive(
            """{"version":"v1.0","callRendererFunction":{"functionCallId":"c1","callFunction":{"@call":"required","catalogId":"$BASIC","args":{"value":"x"}}}}""",
        ).single()
        assertEquals(A2uiErrorCode.INVALID_FUNCTION_CALL, e.code)
        assertEquals("c1", e.functionCallId)
        val reply = Json.parseToJsonElement(e.toAgentMessageJson()!!).jsonObject
        assertEquals("c1", reply["rendererFunctionResponse"]!!.jsonObject["functionCallId"]!!.jsonPrimitive.content)
        val schemas = SchemaSet().add("common_types.json", a2uiJson("json/common_types.json"))
            .add("renderer_to_agent.json", a2uiJson("json/renderer_to_agent.json"))
            .add("catalog.json", a2uiJson("catalogs/basic/catalog.json"))
        assertEquals(emptyList<SchemaError>(), JsonSchemaValidator(schemas).validate(reply, "renderer_to_agent.json"))

        val r = host.receive("""{"version":"v1.0","agentFunctionResponse":{"functionCallId":"c2","value":1}}""").single()
        assertEquals(A2uiErrorCode.INVALID_FUNCTION_CALL, r.code)
    }

    @Test
    fun errorsBecomeAgentMessages() {
        val e = A2uiError(A2uiErrorCode.VALIDATION_FAILED, "expected string, got number", "/components/0/text", "card")
        val msg = Json.parseToJsonElement(e.toAgentMessageJson()!!) as JsonObject
        val schemas = SchemaSet().add("common_types.json", a2uiJson("json/common_types.json"))
            .add("renderer_to_agent.json", a2uiJson("json/renderer_to_agent.json"))
            .add("catalog.json", a2uiJson("catalogs/basic/catalog.json"))
        assertEquals(emptyList<SchemaError>(), JsonSchemaValidator(schemas).validate(msg, "renderer_to_agent.json"))
        assertEquals("VALIDATION_FAILED surface 'card' /components/0/text: expected string, got number", e.forModel())
        assertNull(A2uiError(A2uiErrorCode.VALIDATION_FAILED, "x").toAgentMessageJson())
    }

    @Test
    fun acceptsArraysWrappersAndFences() {
        val array = """[{"version":"v1.0","createSurface":{"surfaceId":"a","catalogId":"$BASIC","components":[{"id":"root","component":"Text","text":"A"}]}}]"""
        assertEquals(emptyList<A2uiError>(), host.receive(array))
        val wrapper = """{"messages":[{"version":"v1.0","createSurface":{"surfaceId":"b","catalogId":"$BASIC"}}]}"""
        assertEquals(emptyList<A2uiError>(), host.receive(wrapper))
        val fenced = "```json\n{\"version\":\"v1.0\",\"createSurface\":{\"surfaceId\":\"c\",\"catalogId\":\"$BASIC\"}}\n```"
        assertEquals(emptyList<A2uiError>(), host.receive(fenced))
        val pretty = "{\n  \"version\": \"v1.0\",\n  \"deleteSurface\": {\"surfaceId\": \"c\"}\n}"
        assertEquals(emptyList<A2uiError>(), host.receive(pretty))
        assertEquals(listOf("a", "b"), host.surfaceIds)
    }

    @Test
    fun mediaUrlsAreDataForThePolicy() {
        host.receive(
            """{"version":"v1.0","createSurface":{"surfaceId":"m","catalogId":"$BASIC","components":[
            {"id":"root","component":"Column","children":["img","vid"]},
            {"id":"img","component":"Image","url":{"@path":"/pic"}},
            {"id":"vid","component":"Video","url":"https://v.example/x.mp4","posterUrl":"http://v.example/p.jpg"}],"dataModel":{"pic":"https://i.example/cat.jpg"}}}""".replace("\n", ""),
        )
        assertEquals(listOf("https://i.example/cat.jpg", "https://v.example/x.mp4", "http://v.example/p.jpg"), host.surface("m")!!.mediaUrls())
    }
}
