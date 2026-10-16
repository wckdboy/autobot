package dev.wckdboy.autobot.core.designsystem.component

/** "1 session", "2 sessions", "0 sessions": a count with its noun in the right number. */
fun counted(count: Int, one: String, other: String = one + "s"): String = "$count ${if (count == 1) one else other}"

fun counted(count: Long, one: String, other: String = one + "s"): String = "$count ${if (count == 1L) one else other}"
