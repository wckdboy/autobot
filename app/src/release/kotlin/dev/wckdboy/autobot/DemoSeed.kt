package dev.wckdboy.autobot

import javax.inject.Inject
import javax.inject.Singleton

/** Release builds have no demo data: the debug build's `DemoSeed` is not compiled in. */
@Singleton
class DemoSeed @Inject constructor() {
    @Suppress("RedundantSuspendModifier")
    suspend fun seedIfEmpty() = Unit
}
