package com.antivocale.app.testing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.data.PreferencesManagerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File

/**
 * TASK-725: the shared temp-DataStore harness for Robolectric tests that
 * exercise the REAL PreferencesManagerImpl (a temp .preferences_pb file,
 * a PreferenceDataStoreFactory scope cancelled after the test, and by
 * default a manager primed the way production wires it:
 * PreferencesManagerImpl(context, dataStore).apply { initialize() },
 * mirroring AppModule's initialize() half over the test-owned store).
 * Previously seven verbatim copies; the temp-file prefix keeps per-class
 * naming for debugging.
 *
 * [primeManager] false serves the tests that seed the store BEFORE
 * constructing the manager (the migration and catalog-url shapes).
 */
class TempDataStoreRule(
    private val prefix: String,
    private val primeManager: Boolean = true,
) : TestRule {

    lateinit var context: Context
        private set
    lateinit var dataStore: DataStore<Preferences>
        private set
    /** The production-wired manager; only initialized when [primeManager] is true. */
    lateinit var prefs: PreferencesManagerImpl
        private set
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var file: File? = null

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                try {
                    setUp()
                    base.evaluate()
                } finally {
                    tearDown()
                }
            }
        }

    private fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val f = File.createTempFile("$prefix-${System.nanoTime()}", ".preferences_pb")
        file = f
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { f }
        // initialize() blocks internally (runBlocking inside the Impl), so
        // no outer bridge is needed here.
        if (primeManager) {
            prefs = PreferencesManagerImpl(context, dataStore).apply { initialize() }
        }
    }

    private fun tearDown() {
        scope.cancel()
        file?.delete()
        // DataStore flushes via scratch-file + atomic rename: a write still
        // in flight after cancel() can re-link the path post-delete.
        // deleteOnExit closes that leak window for the test JVM.
        file?.deleteOnExit()
    }
}
