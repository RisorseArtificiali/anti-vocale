package com.antivocale.app.ui.viewmodel

import com.antivocale.app.data.ExternalModelListJson
import com.antivocale.app.data.ExternalModelRecord
import java.io.File
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertTrue

/** TASK-924 simplify pass: the bundle-import fixtures the restore and link
 *  suites had duplicated verbatim. One home: a fixture change lands once. */
internal object BundleImportTestSupport {
    fun hashFile(f: File): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(f.readBytes())
            .joinToString("") { "%02x".format(it) }

    fun storedRecords(json: String?): List<ExternalModelRecord> =
        ExternalModelListJson.decode(json)
}

/** Polls [predicate] on real time (the import and the catalog refreshes
 *  run on real IO dispatchers); FAILS the test on timeout so a broken
 *  derivation cannot pass by silently waiting out the clock.
 *
 * TASK-960: the budget is 30s because the CI fdroid runner reds a 5s
 * budget under load while the logic is correct (the family green in
 * every local isolation on the same HEAD); 30s still fails fast on a
 * real derivation break. Same treatment as the BatchIntake walls. */
internal fun TestScope.await(what: String, predicate: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 30_000
    while (!predicate() && System.currentTimeMillis() < deadline) {
        Thread.sleep(20)
        runCurrent()
    }
    assertTrue("timed out waiting for: $what", predicate())
}
