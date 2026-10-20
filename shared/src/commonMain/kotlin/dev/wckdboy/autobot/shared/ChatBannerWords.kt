package dev.wckdboy.autobot.shared

/** The words of a chat's setup banners, the same on Android and iOS. */
object ChatBannerWords {
    /** No provider at all. */
    const val NO_MODEL = "No model yet. Download one or add a provider."

    /** Offline mode blocks the chat's remote provider. */
    const val OFFLINE = "Offline mode: this provider is remote, so the kill switch blocks it."

    /** A cloud provider saved without the API key its preset needs: said before anything is sent. */
    fun missingKey(provider: String): String = "$provider has no API key, so messages will fail. Add it in Model providers."
}
