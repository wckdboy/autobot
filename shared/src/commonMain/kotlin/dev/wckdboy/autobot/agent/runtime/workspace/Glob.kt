package dev.wckdboy.autobot.agent.runtime.workspace

/**
 * Glob patterns with `java.nio` PathMatcher semantics, so both apps match the same files:
 * `*` and `?` stay within one path segment, `**` crosses segments, `{a,b}` alternates and
 * `[...]` / `[!...]` are character classes. Paths use `/` and are matched whole.
 */
class Glob(pattern: String) {
    private val regex = Regex(toRegex(pattern))

    fun matches(path: String): Boolean = regex.matches(path)

    private companion object {
        fun toRegex(glob: String): String {
            val out = StringBuilder()
            var i = 0
            var inGroup = false
            while (i < glob.length) {
                val c = glob[i]
                when {
                    c == '*' && glob.getOrNull(i + 1) == '*' -> {
                        out.append(".*")
                        i++
                    }
                    c == '*' -> out.append("[^/]*")
                    c == '?' -> out.append("[^/]")
                    c == '{' && !inGroup -> {
                        out.append("(?:")
                        inGroup = true
                    }
                    c == '}' && inGroup -> {
                        out.append(')')
                        inGroup = false
                    }
                    c == ',' && inGroup -> out.append('|')
                    c == '[' -> {
                        val end = glob.indexOf(']', i + 2)
                        if (end < 0) {
                            out.append("\\[")
                        } else {
                            var body = glob.substring(i + 1, end)
                            if (body.startsWith("!")) body = "^" + body.drop(1)
                            out.append('[').append(body.replace("\\", "\\\\")).append(']')
                            i = end
                        }
                    }
                    c == '\\' && i + 1 < glob.length -> {
                        i++
                        out.append(Regex.escape(glob[i].toString()))
                    }
                    else -> out.append(Regex.escape(c.toString()))
                }
                i++
            }
            return out.toString()
        }
    }
}
