package com.guanyi.mirra.feature.session

/** Own a temporary reading-window flag, preserving earlier settings and a later owner's change. */
internal class ReadingScreenOnLease(private val read: () -> Boolean, private val write: (Boolean) -> Unit,
    private val activeValue: Boolean = true) {
    private var prior: Boolean? = null

    fun update(eligible: Boolean) {
        if (eligible && prior == null) {
            prior = read()
            write(activeValue)
        } else if (!eligible) {
            val previous = prior ?: return
            prior = null
            // Do not overwrite a later owner that has already changed our applied value.
            if (read() == activeValue) write(previous)
        }
    }

    fun close() = update(false)
}
