package dev.wckdboy.autobot

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Release builds run R8. Native code finds Java by name: `Java_<class>_<method>` exports need the
 * class and its native methods kept, and every `GetMethodID(cls, "name", "sig")` callback needs a
 * keep rule with that exact name and Java signature in the engine's consumer rules. A missing rule
 * only shows on a release build on a phone (NoSuchMethodError in the engine process), so this
 * reads the C++ sources and the rules side by side. `verify<Variant>NativePackaging` then checks
 * R8's actual mapping of each release build.
 */
class JniKeepRulesTest {
    private val engines = File("..").resolve("engine").canonicalFile

    private fun engineDirs() = engines.listFiles { f -> f.resolve("src/main/cpp").isDirectory }.orEmpty().sortedBy { it.name }

    private fun sources(engine: File) = engine.resolve("src/main/cpp").listFiles { f -> f.extension == "cpp" }.orEmpty()

    /** `(ILjava/lang/String;[BJ)V` → `int, java.lang.String, byte[], long`. */
    private fun javaParams(sig: String): String {
        val params = sig.substringAfter('(').substringBefore(')')
        val out = mutableListOf<String>()
        var i = 0
        while (i < params.length) {
            var dims = 0
            while (params[i] == '[') { dims++; i++ }
            val type = when (params[i]) {
                'I' -> "int"; 'J' -> "long"; 'Z' -> "boolean"; 'B' -> "byte"; 'F' -> "float"; 'D' -> "double"
                'S' -> "short"; 'C' -> "char"
                'L' -> params.substring(i + 1, params.indexOf(';', i)).replace('/', '.').also { i = params.indexOf(';', i) }
                else -> error("unknown JNI type in $sig")
            }
            out += type + "[]".repeat(dims)
            i++
        }
        return out.joinToString(", ")
    }

    @Test
    fun everyJniLookupHasAKeepRule() {
        val getMethod = Regex("""GetMethodID\([^,]+,\s*"(\w+)",\s*"([^"]+)"\)""")
        val export = Regex("""Java_((?:[a-z0-9]+_)+)([A-Z]\w*?)_\w+\s*\(""")
        var lookups = 0
        engineDirs().forEach { engine ->
            val rules = engine.resolve("consumer-rules.pro").takeIf { it.isFile }?.readText().orEmpty()
            sources(engine).forEach { cpp ->
                val text = cpp.readText()
                getMethod.findAll(text).forEach { m ->
                    val (name, sig) = m.destructured
                    val rule = "$name(${javaParams(sig)});"
                    lookups++
                    assertTrue("${engine.name}/consumer-rules.pro lacks a keep rule '$rule' for GetMethodID in ${cpp.name}", rule in rules)
                }
                export.findAll(text).forEach { m ->
                    val cls = m.groupValues[1].replace('_', '.') + m.groupValues[2]
                    assertTrue(
                        "${engine.name}/consumer-rules.pro must keep the JNI class $cls and its native methods",
                        Regex("""-keepclasseswithmembernames[^\n]*class ${Regex.escape(cls)}\s*\{\s*native <methods>;""").containsMatchIn(rules),
                    )
                }
            }
        }
        // npu (3), diffusion (3) and llama (1) call back into Java: the scan must have found them.
        assertTrue("found only $lookups GetMethodID lookups", lookups >= 7)
    }
}
