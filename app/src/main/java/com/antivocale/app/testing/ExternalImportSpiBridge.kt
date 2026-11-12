package com.antivocale.app.testing

/**
 * TASK-989: the SPI's bridge to the external-import affordances (the
 * Models tab's ViewModel owns the import job, the state flow, the cancel
 * handle and the retry closures; before this object the debug SPI could
 * only DISPATCH an import on its own coroutine, which rendered nothing in
 * the UI and could not be cancelled or retried except by tapping buttons -
 * exactly the uiautomate-the-button anti-pattern the SPI exists to
 * replace).
 *
 * The ViewModel wires itself in init and unwires in onCleared (the handles
 * are null while the Models tab has never composed); the debug SPI reads
 * and drives them. The object is inert in release builds: nothing but the
 * debug receiver's TestSpiOps consults it. It is deliberately a dumb
 * handle bag, not a coordinator: the import logic stays where it is; a
 * future process-level import owner (the pin-override work) can absorb it.
 */
internal object ExternalImportSpiBridge {

    /** Renders the current import state as one line (the SPI's JSON payload). */
    @Volatile var renderState: (() -> String)? = null

    /** The machine-readable busy check: true while an import runs on the
     *  ViewModel path. Control flow keys on THIS, never on [renderState]'s
     *  wording (a display-format edit must not disarm the in-flight guard;
     *  simplify round, all three agents). */
    @Volatile var importInFlight: (() -> Boolean)? = null

    /** The Cancel button's own call: aborts the in-flight import job. */
    @Volatile var cancel: (() -> Unit)? = null

    /** Fires the Error/Cancelled state's retry closure (re-dispatch);
     *  false when the current state carries no retry closure. */
    @Volatile var retry: (() -> Boolean)? = null

    /**
     * The dialog's URL-import entry: dispatches the import ON THE
     * VIEWMODEL's scope (state renders in the UI; cancel/retry live).
     * The async SPI import prefers this when present.
     */
    @Volatile var dispatchUrlImport: ((url: String) -> Unit)? = null

    fun unwire() {
        renderState = null
        importInFlight = null
        cancel = null
        retry = null
        dispatchUrlImport = null
    }
}
