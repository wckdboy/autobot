package dev.wckdboy.autobot.feature.chat.conversation

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.selects.select

private object Tick
private object Done
private object None

/**
 * Emits at most one value per [periodMillis]: the first value after a quiet period right away,
 * then the latest one at the end of each period (values in between are dropped). Values for
 * which [urgent] is true go out immediately, in order (e.g. the end of a stream). The last
 * value is never lost.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> Flow<T>.throttleLatest(periodMillis: Long, urgent: (T) -> Boolean = { false }): Flow<T> = flow {
    coroutineScope {
        val values = produce(capacity = Channel.UNLIMITED) { collect { send(it) } }
        var window: Deferred<Unit>? = null
        var pending: Any? = None
        while (true) {
            val open = window
            val event = select<Any?> {
                values.onReceiveCatching { r -> if (r.isClosed) Done else r.getOrThrow() }
                open?.onAwait { Tick }
            }
            when {
                event === Done -> {
                    open?.cancel()
                    @Suppress("UNCHECKED_CAST")
                    if (pending !== None) emit(pending as T)
                    break
                }
                event === Tick -> if (pending !== None) {
                    @Suppress("UNCHECKED_CAST")
                    emit(pending as T)
                    pending = None
                    window = async { delay(periodMillis) }
                } else {
                    window = null
                }
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    val value = event as T
                    if (open == null || urgent(value)) {
                        open?.cancel()
                        pending = None
                        emit(value)
                        window = async { delay(periodMillis) }
                    } else {
                        pending = value
                    }
                }
            }
        }
    }
}
