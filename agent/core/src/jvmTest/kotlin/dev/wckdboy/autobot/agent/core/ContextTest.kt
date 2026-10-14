package dev.wckdboy.autobot.agent.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextTest {

    private val greeter = ServiceKey<String>("greeter")
    private val counter = ServiceKey<Int>("counter")
    private val hook = Hook<String, String>("test/hook")
    private val event = Event<String>("test/event")

    @Test
    fun pluginWaitsForInjectedServiceAndUnloadsWhenItDisappears() {
        val root = Context()
        var applied = 0
        var disposed = 0
        root.plugin(
            plugin("needs-greeter", greeter) { ctx ->
                applied++
                ctx.effect { Disposable { disposed++ } }
            },
        )
        assertEquals(0, applied)

        val service = root.provide(greeter, "hi")
        assertEquals(1, applied)

        service.dispose()
        assertEquals(1, disposed)

        root.provide(greeter, "again")
        assertEquals("plugin is re-queued and reloads", 2, applied)
    }

    @Test
    fun pluginRegistrationsAreVisibleToSiblingScopesAndUnwindWithPlugin() = runTest {
        val root = Context()
        val handle = root.plugin(
            plugin("provider") { ctx ->
                ctx.provide(counter, 7)
                ctx.intercept(hook) { input, next -> next("$input+plugin") }
            },
        )
        val agent = root.fork("agent")
        assertEquals(7, agent[counter])
        assertEquals("x+plugin", agent.run(hook, "x") { it })

        handle.dispose()
        assertNull(agent[counter])
        assertEquals("x", agent.run(hook, "x") { it })
    }

    @Test
    fun hooksRunByPriorityThenRegistrationOrderAndCanShortCircuit() = runTest {
        val root = Context()
        root.intercept(hook, priority = 0) { input, next -> next("${input}a") }
        root.intercept(hook, priority = 10) { input, next -> next("${input}b") }
        root.intercept(hook, priority = 0) { input, next -> next("${input}c") }
        assertEquals("bac!", root.run(hook, "") { "$it!" })

        val child = root.fork("child")
        child.intercept(hook, priority = 100) { _, _ -> "blocked" }
        assertEquals("blocked", child.run(hook, "") { "$it!" })
        assertEquals("parent unaffected", "bac!", root.run(hook, "") { "$it!" })
    }

    @Test
    fun childScopeShadowsServicesAndDisposesWithParent() = runTest {
        val root = Context()
        root.provide(greeter, "global")
        val child = root.fork("agent")
        child.provide(greeter, "local")
        val seen = mutableListOf<String>()
        child.on(event) { seen += "child:$it" }
        root.on(event) { seen += "root:$it" }

        assertEquals("local", child[greeter])
        child.emit(event, "e")
        assertEquals(listOf("root:e", "child:e"), seen)

        root.dispose()
        assertTrue(child.isDisposed)
        assertNull(child[greeter])
    }
}
