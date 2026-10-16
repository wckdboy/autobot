package dev.wckdboy.autobot.core.designsystem.motion

import android.view.View
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.CompositionObserverHandle
import androidx.compose.runtime.tooling.CompositionRegistrationObserver
import androidx.compose.runtime.tooling.ObservableComposition
import androidx.compose.runtime.tooling.observe
import androidx.compose.ui.platform.findViewTreeCompositionContext

/**
 * Counts recompose-scope executions in every composition of a window (sub-compositions such
 * as lazy list items included), via the runtime's tooling observer. [scopes] is the number of
 * scope (re)compositions since [reset]; [compositions] the number of composition passes.
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
class RecompositionCounter private constructor(recomposer: Recomposer) : AutoCloseable {
    var scopes = 0
        private set
    var compositions = 0
        private set
    private val handles = HashMap<ObservableComposition, CompositionObserverHandle>()

    private val observer = object : CompositionObserver {
        override fun onBeginComposition(composition: ObservableComposition) {
            compositions++
        }
        override fun onScopeEnter(scope: RecomposeScope) {
            scopes++
        }
        override fun onReadInScope(scope: RecomposeScope, value: Any) = Unit
        override fun onScopeExit(scope: RecomposeScope) = Unit
        override fun onEndComposition(composition: ObservableComposition) = Unit
        override fun onScopeInvalidated(scope: RecomposeScope, value: Any?) = Unit
        override fun onScopeDisposed(scope: RecomposeScope) = Unit
    }

    private val registration: CompositionObserverHandle = recomposer.observe(object : CompositionRegistrationObserver {
        override fun onCompositionRegistered(composition: ObservableComposition) {
            handles[composition] = composition.setObserver(observer)
        }
        override fun onCompositionUnregistered(composition: ObservableComposition) {
            handles.remove(composition)?.dispose()
        }
    })

    fun reset() {
        scopes = 0
        compositions = 0
    }

    override fun close() {
        registration.dispose()
        handles.values.forEach { it.dispose() }
        handles.clear()
    }

    companion object {
        /** Observes the window recomposer that owns [root]'s compositions (after `setContent`). */
        fun attach(root: View): RecompositionCounter {
            // Depth first through every child: the window may hold an action bar before the content.
            fun walk(view: View): Sequence<View> = sequenceOf(view) +
                ((view as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).asSequence().flatMap { walk(g.getChildAt(it)) } } ?: emptySequence())
            val context = walk(root).firstNotNullOfOrNull { it.findViewTreeCompositionContext() as? Recomposer }
            return RecompositionCounter(context ?: error("no Compose window recomposer under $root"))
        }
    }
}
