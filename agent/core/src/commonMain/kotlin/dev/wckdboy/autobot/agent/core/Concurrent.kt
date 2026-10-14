package dev.wckdboy.autobot.agent.core

import kotlin.concurrent.atomics.AtomicReference
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/*
 * Multiplatform stand-ins for the java.util.concurrent types the runtime used on the JVM, so the
 * same code runs on Kotlin/Native (iOS).
 */

/** A mutual-exclusion lock (`synchronized` on the JVM, a native mutex on iOS). */
class Lock {
    @PublishedApi
    internal val monitor = SynchronizedObject()

    inline fun <T> withLock(block: () -> T): T = synchronized(monitor, block)
}

/** Copy-on-write list: lock-free reads of an immutable snapshot, writes swap the snapshot. */
class CopyOnWriteList<T> : Iterable<T> {
    private val ref = AtomicReference<List<T>>(emptyList())

    val snapshot: List<T> get() = ref.load()

    override fun iterator(): Iterator<T> = snapshot.iterator()

    fun isEmpty(): Boolean = snapshot.isEmpty()

    operator fun plusAssign(element: T) = update { it + element }

    fun remove(element: T): Boolean {
        var removed = false
        update { list ->
            val index = list.indexOf(element)
            removed = index >= 0
            if (removed) list.toMutableList().apply { removeAt(index) } else list
        }
        return removed
    }

    fun removeAll(predicate: (T) -> Boolean): Boolean {
        var removed = false
        update { list -> list.filterNot(predicate).also { removed = it.size != list.size } }
        return removed
    }

    fun clear() = ref.store(emptyList())

    private inline fun update(transform: (List<T>) -> List<T>) {
        while (true) {
            val current = ref.load()
            if (ref.compareAndSet(current, transform(current))) return
        }
    }
}

/** A [MutableSet] whose operations are atomic; iteration works on a snapshot. */
class ConcurrentSet<T> : AbstractMutableSet<T>() {
    private val lock = Lock()
    private val items = LinkedHashSet<T>()

    override val size: Int get() = lock.withLock { items.size }

    override fun add(element: T): Boolean = lock.withLock { items.add(element) }

    override fun remove(element: T): Boolean = lock.withLock { items.remove(element) }

    override fun contains(element: T): Boolean = lock.withLock { element in items }

    override fun clear() = lock.withLock { items.clear() }

    override fun iterator(): MutableIterator<T> {
        val copy = lock.withLock { items.toList() }.iterator()
        return object : MutableIterator<T> {
            private var last: T? = null

            override fun hasNext() = copy.hasNext()

            override fun next(): T = copy.next().also { last = it }

            @Suppress("UNCHECKED_CAST")
            override fun remove() {
                this@ConcurrentSet.remove(last as T)
            }
        }
    }
}

/** Wall-clock milliseconds since the epoch, for log timestamps. */
expect fun currentTimeMillis(): Long

private val epoch = kotlin.time.TimeSource.Monotonic.markNow()

/** Monotonic nanoseconds for measuring durations (`System.nanoTime` semantics). */
fun monotonicNanos(): Long = epoch.elapsedNow().inWholeNanoseconds
