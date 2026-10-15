package dev.wckdboy.autobot.shared.audiogen

import java.text.Normalizer

internal actual fun nfkc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
