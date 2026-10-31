package com.kitalonlabs.neogold.feature.research.export

/**
 * The one open "choose where to save the export" request, kept outside any screen (BUG-083 item 30).
 *
 * The system file picker creates an empty document the moment the worker picks a place. If the research session locked while
 * the picker was open, the screen that asked is gone and nobody is left to start the export, so the empty document must be
 * deleted by whoever receives the result. [deliver] does that: a result goes to the screen's handler only if the screen is
 * still waiting for it, and to [discard] in every other case.
 */
class PendingPick<T : Any>(private val discard: (T) -> Unit) {

    @Volatile
    private var handler: ((T) -> Unit)? = null

    /** The screen asks for a place; the answer goes to [onChosen]. A newer request replaces an older one. */
    fun expect(onChosen: (T) -> Unit) {
        handler = onChosen
    }

    /** The screen is gone (left, or locked away): a result that arrives now is discarded. */
    fun cancel() {
        handler = null
    }

    /** The screen received a place it will not use (the session locked meanwhile). */
    fun reject(item: T) = discard(item)

    /** The picker answered; [item] is null when the worker backed out and nothing was created. */
    fun deliver(item: T?) {
        val waiting = handler
        handler = null
        when {
            item == null -> Unit
            waiting == null -> discard(item)
            else -> waiting(item)
        }
    }
}
