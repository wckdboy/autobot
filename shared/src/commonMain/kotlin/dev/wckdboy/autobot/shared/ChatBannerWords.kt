package dev.wckdboy.autobot.shared

/** The words of a chat's setup banners, the same on Android and iOS. */
object ChatBannerWords {
    /** A cloud provider saved without the API key its preset needs: said before anything is sent. */
    fun missingKey(provider: String): String = "$provider has no API key, so messages will fail. Add it in Model providers."
}
