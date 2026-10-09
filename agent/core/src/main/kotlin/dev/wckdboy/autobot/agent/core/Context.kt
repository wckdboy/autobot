package dev.wckdboy.autobot.agent.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/*
 * A small Kotlin take on Cordis, the plugin framework under DeepSeek Harness ("everything is a
 * plugin"):
 *
 * - Services: typed slots ([ServiceKey]) that plugins provide and consume.
 * - Events: fire-and-forget [Event]s and around-middleware [Hook]s (DSH "waterfall").
 * - Effects: every registration returns a [Disposable]; disposing a scope undoes its effects in
 *   reverse order, so there is no privileged core — every piece can be unloaded.
 * - Plugins with `inject` dependencies load only while those services exist and are unloaded
 *   (and re-queued) when one disappears.
 * - Scopes: [Context.fork] creates a child (e.g. a per-agent context). Children see their
 *   ancestors' services and listeners; their own registrations shadow and unwind with them.
 */

fun interface Disposable {
    fun dispose()
}

/** Typed identity of a service slot. Compared by reference. */
class ServiceKey<T : Any>(val name: String) {
    override fun toString(): String = "ServiceKey($name)"
}

/** A fire-and-forget event delivered to suspend listeners in registration order. */
class Event<T>(val name: String) {
    override fun toString(): String = "Event($name)"
}

/**
 * Around-middleware ("waterfall"). Each listener receives the input and `next`; it may call
 * `next` (possibly with a modified input), transform its result, or short-circuit by returning
 * without calling it. Higher [Context.intercept] priority runs outermost.
 */
class Hook<I, O>(val name: String) {
    override fun toString(): String = "Hook($name)"
}

typealias HookHandler<I, O> = suspend (input: I, next: suspend (I) -> O) -> O

/** A unit of functionality applied to a [Context] scope. */
interface Plugin {
    val name: String

    /** Services that must exist before [apply] runs. */
    val inject: Set<ServiceKey<*>> get() = emptySet()

    /** Registers this plugin's effects on [ctx]; they are undone when the plugin unloads. */
    fun apply(ctx: Context)
}

/** Convenience for small plugins. */
fun plugin(name: String, vararg inject: ServiceKey<*>, apply: (Context) -> Unit): Plugin = object : Plugin {
    override val name = name
    override val inject = inject.toSet()
    override fun apply(ctx: Context) = apply(ctx)
}

class MissingServiceException(key: ServiceKey<*>) : IllegalStateException("Service ${key.name} is not available")

class Context private constructor(
    val name: String,
    val parent: Context?,
    private val root: Root,
    private val isPluginScope: Boolean,
) {
    constructor(name: String = "root") : this(name, null, Root(), false)

    /**
     * Where registrations land. A plugin scope only owns the *lifetime* of its registrations;
     * the services and listeners themselves live on the host scope so siblings (e.g. agent
     * scopes) can see them, as in Cordis.
     */
    private val owner: Context get() = if (isPluginScope) parent!!.owner else this

    private class Registration(val order: Long, val priority: Int, val handler: Any)

    /** Shared plugin bookkeeping for one context tree. */
    private class Root {
        val sequence = AtomicLong()
        val pending = CopyOnWriteArrayList<PendingPlugin>()
        val loaded = CopyOnWriteArrayList<LoadedPlugin>()
    }

    private class PendingPlugin(val plugin: Plugin, val host: Context)
    private class LoadedPlugin(val plugin: Plugin, val host: Context, val scope: Context)

    private val services = HashMap<ServiceKey<*>, Any>()
    private val listeners = HashMap<Any, MutableList<Registration>>()
    private val effects = ArrayDeque<Disposable>()
    private val children = CopyOnWriteArrayList<Context>()

    @Volatile
    var isDisposed: Boolean = false
        private set

    // ---------------------------------------------------------------------------------------
    // Services

    /** The nearest provider of [key] in this scope or its ancestors. */
    operator fun <T : Any> get(key: ServiceKey<T>): T? {
        var scope: Context? = this
        while (scope != null) {
            synchronized(scope) {
                @Suppress("UNCHECKED_CAST")
                (scope.services[key] as T?)?.let { return it }
            }
            scope = scope.parent
        }
        return null
    }

    fun <T : Any> require(key: ServiceKey<T>): T = get(key) ?: throw MissingServiceException(key)

    /**
     * Provides [value] for [key] in this scope. Disposing the returned handle removes it and
     * unloads any plugin that injected it.
     */
    fun <T : Any> provide(key: ServiceKey<T>, value: T): Disposable {
        check(!isDisposed) { "Context $name is disposed" }
        val target = owner
        synchronized(target) { target.services[key] = value }
        val handle = Disposable {
            val removed = synchronized(target) {
                if (target.services[key] === value) {
                    target.services.remove(key)
                    true
                } else {
                    false
                }
            }
            if (removed) onServiceRemoved(key)
        }
        val effect = addEffect(handle)
        flushPending()
        return effect
    }

    // ---------------------------------------------------------------------------------------
    // Events and hooks

    fun <T> on(event: Event<T>, listener: suspend (T) -> Unit): Disposable = register(event, 0, listener)

    fun <I, O> intercept(hook: Hook<I, O>, priority: Int = 0, handler: HookHandler<I, O>): Disposable =
        register(hook, priority, handler)

    /** Delivers [value] to every listener in this scope chain, ancestors first. */
    suspend fun <T> emit(event: Event<T>, value: T) {
        for (registration in collect(event)) {
            @Suppress("UNCHECKED_CAST")
            (registration.handler as suspend (T) -> Unit)(value)
        }
    }

    /** Runs [hook]'s middleware chain around [terminal]. */
    suspend fun <I, O> run(hook: Hook<I, O>, input: I, terminal: suspend (I) -> O): O {
        val chain = collect(hook).sortedWith(compareByDescending<Registration> { it.priority }.thenBy { it.order })
        @Suppress("UNCHECKED_CAST")
        val handlers = chain.map { it.handler as HookHandler<I, O> }

        suspend fun call(index: Int, value: I): O =
            if (index == handlers.size) terminal(value) else handlers[index](value) { next -> call(index + 1, next) }

        return call(0, input)
    }

    fun hasListeners(key: Any): Boolean = collect(key).isNotEmpty()

    private fun register(key: Any, priority: Int, handler: Any): Disposable {
        val registration = Registration(root.sequence.incrementAndGet(), priority, handler)
        check(!isDisposed) { "Context $name is disposed" }
        val target = owner
        synchronized(target) { target.listeners.getOrPut(key) { mutableListOf() }.add(registration) }
        val handle = Disposable { synchronized(target) { target.listeners[key]?.remove(registration) } }
        return addEffect(handle)
    }

    private fun collect(key: Any): List<Registration> {
        val chain = generateSequence(this) { it.parent }.toList().asReversed()
        return chain.flatMap { scope -> synchronized(scope) { scope.listeners[key]?.toList().orEmpty() } }
    }

    // ---------------------------------------------------------------------------------------
    // Effects and scopes

    /** Runs [execute] now; its disposer runs when this scope is disposed (reverse order). */
    fun effect(execute: () -> Disposable): Disposable = addEffect(execute())

    private fun addEffect(disposable: Disposable): Disposable {
        val wrapped = object : Disposable {
            private var done = false

            override fun dispose() {
                if (done) return
                done = true
                synchronized(this@Context) { effects.remove(this) }
                disposable.dispose()
            }
        }
        synchronized(this) { effects.addLast(wrapped) }
        return wrapped
    }

    /** Creates a child scope. It is disposed together with this scope. */
    fun fork(name: String): Context = fork(name, plugin = false)

    private fun fork(name: String, plugin: Boolean): Context {
        val child = Context("${this.name}/$name", this, root, plugin)
        children += child
        return child
    }

    /** Disposes child scopes, then this scope's effects in reverse registration order. */
    fun dispose() {
        if (isDisposed) return
        children.asReversed().forEach { it.dispose() }
        children.clear()
        val toRun = synchronized(this) {
            isDisposed = true
            effects.toList().asReversed().also { effects.clear() }
        }
        toRun.forEach { runCatching { it.dispose() } }
        synchronized(this) {
            services.clear()
            listeners.clear()
        }
        parent?.children?.remove(this)
        root.loaded.removeAll { it.host === this || it.scope === this }
        root.pending.removeAll { it.host === this }
    }

    // ---------------------------------------------------------------------------------------
    // Plugins

    /**
     * Loads [plugin] into a child scope of this context. If its `inject` services are missing it
     * is queued and loaded as soon as they appear. The handle unloads (or dequeues) it.
     */
    fun plugin(plugin: Plugin): Disposable {
        val pending = PendingPlugin(plugin, this)
        root.pending += pending
        flushPending()
        return Disposable {
            root.pending.remove(pending)
            root.loaded.filter { it.plugin === plugin && it.host === this }.forEach { unload(it) }
        }
    }

    fun loadedPlugins(): List<String> = root.loaded.filter { it.host === this }.map { it.plugin.name }

    private fun flushPending() {
        var progress = true
        while (progress) {
            progress = false
            for (pending in root.pending) {
                if (pending.host.isDisposed) {
                    root.pending.remove(pending)
                    continue
                }
                if (pending.plugin.inject.all { pending.host[it] != null } && root.pending.remove(pending)) {
                    val scope = pending.host.fork("plugin:${pending.plugin.name}", plugin = true)
                    root.loaded += LoadedPlugin(pending.plugin, pending.host, scope)
                    try {
                        pending.plugin.apply(scope)
                    } catch (t: Throwable) {
                        scope.dispose()
                        throw t
                    }
                    progress = true
                }
            }
        }
    }

    private fun onServiceRemoved(key: ServiceKey<*>) {
        val affected = root.loaded.filter { loaded -> key in loaded.plugin.inject && loaded.host[key] == null }
        affected.forEach { loaded ->
            unload(loaded)
            if (!loaded.host.isDisposed) root.pending += PendingPlugin(loaded.plugin, loaded.host)
        }
    }

    private fun unload(loaded: LoadedPlugin) {
        root.loaded.remove(loaded)
        loaded.scope.dispose()
    }

    override fun toString(): String = "Context($name)"
}
