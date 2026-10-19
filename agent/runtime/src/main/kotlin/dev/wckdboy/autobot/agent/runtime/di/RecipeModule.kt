package dev.wckdboy.autobot.agent.runtime.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.wckdboy.autobot.agent.runtime.recipes.AndroidNodeRecipeHost
import dev.wckdboy.autobot.agent.runtime.recipes.AndroidRecipeEnvironment
import dev.wckdboy.autobot.shared.recipes.NodeRecipeSync
import dev.wckdboy.autobot.agent.runtime.recipes.RecipeBook
import dev.wckdboy.autobot.agent.runtime.recipes.RecipeEnvironment
import dev.wckdboy.autobot.core.security.WipeParticipant
import javax.inject.Provider

@Module
@InstallIn(SingletonComponent::class)
abstract class RecipeModule {
    @Binds
    abstract fun recipeEnvironment(env: AndroidRecipeEnvironment): RecipeEnvironment

    companion object {
        @Provides
        @javax.inject.Singleton
        fun nodeRecipeSync(host: AndroidNodeRecipeHost): NodeRecipeSync = NodeRecipeSync(host)

        /**
         * Panic wipe: scheduled and queued recipe runs are cancelled and their notifications
         * removed before storage goes (the recipes themselves are rows of the encrypted database,
         * and WorkManager's own database is deleted with the rest).
         */
        @Provides
        @IntoSet
        fun provideRecipesWipeParticipant(book: Provider<RecipeBook>): WipeParticipant =
            WipeParticipant.ordered(WipeParticipant.STOP_WORK) { book.get().stopAll() }
    }
}
