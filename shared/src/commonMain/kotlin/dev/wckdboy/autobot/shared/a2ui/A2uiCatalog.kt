package dev.wckdboy.autobot.shared.a2ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A place in a component that holds other components, found from the catalog schema: a property
 * typed `ComponentId`/`Child` (one child) or `ChildList` (a list or a template). [path] is the
 * property path inside the component, `*` standing for any array item (Tabs: `tabs/ * /child`).
 */
class ChildSlot internal constructor(val path: List<String>, val isList: Boolean)

/** A component type of a catalog and its composition rules. */
class ComponentSpec internal constructor(
    val name: String,
    /** Parent types allowed (`Surface` for the top level); null when any parent is. */
    val allowedParents: List<String>?,
    /** Child types allowed; null when any child is. */
    val allowedChildren: List<String>?,
    val slots: List<ChildSlot>,
)

/** A function of a catalog and its call rules. */
class FunctionSpec internal constructor(
    val name: String,
    /** `string`, `number`, `boolean`, `array`, `object`, `validationResult`, `any` or `void`. */
    val returnType: String,
    /** `rendererOnly` (the default), `agentOnly` or `rendererOrAgent`. */
    val allowedCallers: String,
    val requiresUserActivation: Boolean,
)

/** The result of loading a catalog: the catalog, or why it was refused. */
class A2uiCatalogLoad internal constructor(val catalog: A2uiCatalog?, val errors: List<String>)

/**
 * An A2UI v1.0 catalog (components and functions) as a validator and renderer use it. The basic
 * catalog ships embedded ([basic]); others load from their JSON with [load].
 */
class A2uiCatalog private constructor(
    val catalogId: String,
    internal val json: JsonObject,
) {
    /** The catalog's Markdown guidance for the model, if any. */
    val instructions: String? = json["instructions"]?.stringOrNull()

    internal val components: Map<String, ComponentSpec>
    internal val functions: Map<String, FunctionSpec>

    init {
        val comps = json["components"] as? JsonObject ?: JsonObject(emptyMap())
        components = comps.mapValues { (name, schema) ->
            val o = schema as? JsonObject ?: JsonObject(emptyMap())
            ComponentSpec(
                name = name,
                allowedParents = (o["allowedParents"] as? JsonArray)?.mapNotNull { it.stringOrNull() },
                allowedChildren = (o["allowedChildren"] as? JsonArray)?.mapNotNull { it.stringOrNull() },
                slots = findSlots(o),
            )
        }
        val fns = json["functions"] as? JsonObject ?: JsonObject(emptyMap())
        functions = fns.mapValues { (name, schema) ->
            val o = schema as? JsonObject ?: JsonObject(emptyMap())
            FunctionSpec(
                name = name,
                returnType = o["returnType"]?.stringOrNull() ?: "any",
                allowedCallers = o["allowedCallers"]?.stringOrNull() ?: "rendererOnly",
                requiresUserActivation = o["requiresUserActivation"]?.booleanOrNull() ?: false,
            )
        }
    }

    val componentNames: List<String> get() = components.keys.toList()
    val functionNames: List<String> get() = functions.keys.toList()

    fun component(name: String): ComponentSpec? = components[name]
    fun function(name: String): FunctionSpec? = functions[name]

    /** The catalog JSON, for a prompt. */
    val jsonText: String by lazy { json.toJsonText() }

    /** Schemas for validating agent messages with this catalog as `catalog.json`. */
    internal val messageValidator: JsonSchemaValidator by lazy {
        JsonSchemaValidator(
            SchemaSet()
                .add("common_types.json", specJson(A2uiSpecJson.commonTypes))
                .add("agent_to_renderer.json", specJson(A2uiSpecJson.agentToRenderer))
                .add("catalog.json", json),
        )
    }

    private fun findSlots(schema: JsonObject): List<ChildSlot> {
        val out = LinkedHashMap<String, ChildSlot>()
        fun walk(s: JsonElement?, prefix: List<String>, depth: Int) {
            if (s !is JsonObject || depth > 12) return
            s["\$ref"]?.stringOrNull()?.let { ref ->
                when {
                    ref.endsWith("/\$defs/ComponentId") || ref.endsWith("/\$defs/Child") ->
                        out.getOrPut(prefix.joinToString("/")) { ChildSlot(prefix, isList = false) }
                    ref.endsWith("/\$defs/ChildList") ->
                        out.getOrPut(prefix.joinToString("/")) { ChildSlot(prefix, isList = true) }
                    ref.startsWith("#/") -> walk(JsonPointer.get(json, ref.substring(1)), prefix, depth + 1)
                }
            }
            (s["properties"] as? JsonObject)?.forEach { (k, v) -> if (prefix.isNotEmpty() || k != "component") walk(v, prefix + k, depth + 1) }
            walk(s["items"], prefix + "*", depth + 1)
            for (k in listOf("allOf", "anyOf", "oneOf")) (s[k] as? JsonArray)?.forEach { walk(it, prefix, depth + 1) }
        }
        walk(schema, emptyList(), 0)
        return out.values.toList()
    }

    companion object {
        const val BASIC_CATALOG_ID = "https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json"

        /** The A2UI v1.0 basic catalog (Text, Image, Row, Column, Button, TextField, ...). */
        val basic: A2uiCatalog by lazy { checkNotNull(load(A2uiSpecJson.basicCatalog).catalog) }

        private val catalogDefinition: JsonSchemaValidator by lazy {
            JsonSchemaValidator(
                SchemaSet()
                    .add("common_types.json", specJson(A2uiSpecJson.commonTypes))
                    .add("catalog_definition.json", specJson(A2uiSpecJson.catalogDefinition)),
            )
        }

        private val reservedComponents = setOf("Surface")

        /**
         * Loads a catalog from its JSON. It must match `catalog_definition.json`, name every
         * component and function as a UAX #31 identifier, and declare each component's
         * `component` const under its own name.
         */
        fun load(text: String): A2uiCatalogLoad {
            val root = try {
                Json.parseToJsonElement(text)
            } catch (e: Exception) {
                return A2uiCatalogLoad(null, listOf("catalog is not JSON: ${e.message}"))
            }
            return load(root)
        }

        internal fun load(root: JsonElement): A2uiCatalogLoad {
            if (root !is JsonObject) return A2uiCatalogLoad(null, listOf("catalog must be a JSON object"))
            val errors = catalogDefinition.validate(root, "catalog_definition.json").map { "${it.path.ifEmpty { "/" }}: ${it.message}" }.toMutableList()
            val id = root["catalogId"]?.stringOrNull()
            (root["components"] as? JsonObject)?.forEach { (name, schema) ->
                if (name in reservedComponents) errors += "/components/$name: '$name' is reserved by the protocol"
                if (!SchemaPatterns.isIdentifier(name)) errors += "/components/$name: component names must be UAX #31 identifiers"
                if (discriminator(schema) != name) errors += "/components/$name: must declare properties.component.const = \"$name\""
            }
            (root["functions"] as? JsonObject)?.keys?.forEach { name ->
                if (!SchemaPatterns.isIdentifier(name)) errors += "/functions/$name: function names must be UAX #31 identifiers (and not start with '@')"
            }
            if (id == null || errors.isNotEmpty()) return A2uiCatalogLoad(null, errors.ifEmpty { listOf("catalogId is missing") })
            return A2uiCatalogLoad(A2uiCatalog(id, root), emptyList())
        }

        /** The `component` const of a component schema, directly or in an `allOf` entry. */
        private fun discriminator(schema: JsonElement): String? {
            val o = schema as? JsonObject ?: return null
            (((o["properties"] as? JsonObject)?.get("component") as? JsonObject)?.get("const"))?.stringOrNull()?.let { return it }
            return (o["allOf"] as? JsonArray)?.firstNotNullOfOrNull { discriminator(it) }
        }

        internal fun specJson(text: String): JsonElement = Json.parseToJsonElement(text)
    }
}
