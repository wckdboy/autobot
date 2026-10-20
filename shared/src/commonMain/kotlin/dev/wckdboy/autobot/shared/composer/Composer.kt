package dev.wckdboy.autobot.shared.composer

/**
 * The one composer every chat uses (phone chat, agent run, the node's agent), the same on
 * Android and iOS: what its button does, what it says, and what stays on screen while typing.
 */
object Composer {
    /** The field grows to this many lines, then scrolls inside. */
    const val MAX_LINES = 5

    /** What the composer's button does now. */
    enum class Action {
        /** Sends the message (disabled while there is nothing to send). */
        SEND,

        /** A turn runs and something is typed: it goes once the turn ends. */
        QUEUE,

        /** A turn runs and nothing is typed: stops it. */
        STOP,
    }

    /**
     * The button for [text] and [attachments] while a turn is [running]. (The node's agent can
     * neither queue nor stop a turn: it always sends, disabled while one runs.)
     */
    fun action(text: String, attachments: Int, running: Boolean): Action = when {
        !running -> Action.SEND
        text.isNotBlank() || attachments > 0 -> Action.QUEUE
        else -> Action.STOP
    }

    /** Whether [action] can be pressed: Stop always; Send and Queue once there is something to send. */
    fun enabled(action: Action, text: String, attachments: Int, ready: Boolean = true): Boolean = when (action) {
        Action.STOP -> true
        Action.SEND, Action.QUEUE -> ready && (text.isNotBlank() || attachments > 0)
    }

    /** The button's spoken label. */
    fun label(action: Action): String = when (action) {
        Action.SEND -> "Send"
        Action.QUEUE -> "Queue"
        Action.STOP -> "Stop"
    }

    /** The field's placeholder: short, so it never wraps on a narrow phone. */
    fun placeholder(running: Boolean, node: Boolean = false): String = when {
        node -> "A task for your machine"
        running -> "Queue a follow-up"
        else -> "Message"
    }

    /** A part of a screen with a field, for [shows]. */
    enum class Part {
        /** The field and its attach, dictate, send and stop buttons. */
        FIELD,

        /** Images waiting to go, slash commands, words heard: they belong to what is typed. */
        ATTACHMENTS,

        /** A question or approval the user is answering. */
        ANSWERING,

        /** Banners (missing key, offline, no model), notices. */
        BANNER,

        /** Chips and lines about the session (think, redact, cascade, policy, context). */
        CHIPS,

        /** The agent's plan panel. */
        PLAN,

        /** Empty-state art and headers, tips. */
        DECOR,
    }

    /**
     * Whether [part] shows while the keyboard is [keyboardUp]. The keyboard takes about a third of
     * the screen, so only the field, what goes with it and what the user is answering stay; the
     * rest steps aside until it goes down.
     */
    fun shows(part: Part, keyboardUp: Boolean): Boolean = when (part) {
        Part.FIELD, Part.ATTACHMENTS, Part.ANSWERING -> true
        Part.BANNER, Part.CHIPS, Part.PLAN, Part.DECOR -> !keyboardUp
    }
}
