package io.trimio.engine.illustration.generate

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Where a generator reports each denoising step of the picture it is drawing ([step] of [steps]): the film's build bar
 * moves with it, so a picture that takes minutes on a processor is never mistaken for a stalled run. Carried in the
 * coroutine context, so every layer between the stage and the engine passes it on without knowing of it.
 */
class DrawProgress(val onStep: (step: Int, steps: Int) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DrawProgress>
}
