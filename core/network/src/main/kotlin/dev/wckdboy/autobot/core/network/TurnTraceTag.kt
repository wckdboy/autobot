package dev.wckdboy.autobot.core.network

import dev.wckdboy.autobot.agent.core.TurnTrace
import okhttp3.Request

/**
 * Tags the request with [trace], the agent turn it is made for, so the audit log ties it to that
 * turn (privacy receipts). No-op for null.
 */
fun Request.Builder.turnTrace(trace: TurnTrace?): Request.Builder = if (trace == null) this else tag(TurnTrace::class.java, trace)

/** Tags the request with the calling coroutine's agent turn, if it works for one. */
suspend fun Request.Builder.turnTrace(): Request.Builder = turnTrace(TurnTrace.current())
