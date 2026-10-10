package com.antivocale.app.service

import android.content.Intent
import com.antivocale.app.receiver.NotificationActionReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.reflect.KClassifier
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible

/**
 * TASK-764: the round-trip symmetry contract, enumerated by reflection so a
 * field ADDED to ResultNotificationSpec with a default (every historical
 * drift field was defaulted at birth) fails this class until it rides
 * writeTo/readFrom: a new type is missing from the non-default table, and a
 * new field of a known type round-trips at its default and trips the
 * per-property compare. When you add a spec field that must survive page
 * reposts, wire it into ResultNotificationSpecNav and extend the type table
 * if needed; the failure names the field, not three files.
 *
 * Documented recomputations (excluded from the compare): pageIndex (the
 * intent carries the NAV TARGET page) and repost (the refresher's flag).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ResultNotificationSpecNavTest {

    /** Per-FIELD probe values, complementary across the two passes: a spec
     *  field's declared default can collide with at most one pass's probe,
     *  so the other pass fails and names the field (a per-TYPE table was
     *  mutation-proven blind to a Boolean defaulted true and to same-type
     *  transpositions). A new spec TYPE fails here, naming its KType. */
    private fun probeFor(p: kotlin.reflect.KParameter, pass: Int): Any = when (p.type.classifier) {
        String::class -> "p$pass-${p.name}" // unique per field: transpositions cross-fail
        Boolean::class -> (p.index + pass) % 2 == 0 // alternates across passes
        Int::class -> 1_000 + p.index + pass
        Float::class -> 1_000f + p.index + pass
        Long::class -> 1_000L + p.index + pass
        else -> throw AssertionError("spec field '${p.name}' has a type the probe table lacks: ${p.type}")
    }

    private val ctor get() = ResultNotificationSpec::class.primaryConstructor!!

    private fun everyFieldProbed(pass: Int): ResultNotificationSpec = ctor.callBy(
        ctor.parameters.associateWith { p -> probeFor(p, pass) }
    )

    private fun assertRoundTrip(original: ResultNotificationSpec, back: ResultNotificationSpec?) {
        assertNotNull(back)
        val skip = setOf("pageIndex", "repost")
        for (m in ResultNotificationSpec::class.memberProperties) {
            if (m.name in skip) continue
            m.isAccessible = true
            assertEquals("spec field '${m.name}' must ride the round trip", m.getter.call(original), m.getter.call(back))
        }
    }

    @Test
    fun `every spec field survives the round trip (probe pass 0)`() {
        val original = everyFieldProbed(pass = 0)
        val intent = Intent()
        ResultNotificationSpecNav.writeTo(intent, original, currentPage = 4)
        val back = ResultNotificationSpecNav.readFrom(intent)
        assertRoundTrip(original, back)
        // The two documented recomputations.
        assertEquals(4, back!!.pageIndex)
        assertEquals(false, back.repost)
    }

    @Test
    fun `every spec field survives the round trip (probe pass 1)`() {
        // Complementary probes: a declared default can collide with at most
        // one pass, so an unwired new field fails the other pass.
        val original = everyFieldProbed(pass = 1)
        val intent = Intent()
        ResultNotificationSpecNav.writeTo(intent, original, currentPage = 2)
        assertRoundTrip(original, ResultNotificationSpecNav.readFrom(intent))
    }

    @Test
    fun `nullables ride as absent extras, not blank sentinels`() {
        val original = ctor.callBy(
            ctor.parameters.associateWith { p ->
                if (p.type.isMarkedNullable) null else probeFor(p, 0)
            }
        )
        val intent = Intent()
        ResultNotificationSpecNav.writeTo(intent, original, currentPage = 0)
        assertRoundTrip(original, ResultNotificationSpecNav.readFrom(intent))
        // The name claims absence, so enforce it: a regression to
        // putExtra(key, value ?: "") would still pass the compare above.
        assertFalse(intent.hasExtra(NotificationActionReceiver.EXTRA_TASK_ID))
    }

    @Test
    fun `the seventeen private key values are frozen (cross-version repost compat)`() {
        // A retyped key keeps writeTo/readFrom symmetric and every other
        // test green, but strands every notification the currently shipped
        // version posted (they repost with the field at its default). The
        // literals are the contract; pin them by reflection.
        val frozen = mapOf(
            "EXTRA_SIGNATURE_TEXT" to "signature_text",
            "EXTRA_SIGNATURE_POSITION" to "signature_position",
            "EXTRA_PAGE_INDEX" to "page_index",
            "EXTRA_NOTIFICATION_ID" to "notification_id",
            "EXTRA_FIRST_POSTED_AT" to "first_posted_at",
            "EXTRA_CONFIDENCE" to "confidence",
            "EXTRA_DETECTED_LANGUAGE" to "detected_language",
            "EXTRA_REPETITION_SUSPECTED" to "repetition_suspected",
            "EXTRA_COPIED_TO_CLIPBOARD" to "copied_to_clipboard",
            "EXTRA_STREAMED_WITHOUT_VAD" to "streamed_without_vad",
            "EXTRA_REFINED_FROM" to "refined_from",
            "EXTRA_NOT_REFINED" to "not_refined",
            "EXTRA_IS_PARTIAL" to "is_partial",
            "EXTRA_FAILED_CHUNK_COUNT" to "failed_chunk_count",
            "EXTRA_SAVE_FAILURE" to "save_failure",
            "EXTRA_SAVED_FOLDER" to "saved_folder",
            "EXTRA_SAVED_FILE" to "saved_file",
        )
        val declared = ResultNotificationSpecNav::class.java.declaredFields
            .filter { it.type == String::class.java } // skip Kotlin's INSTANCE artifact
            .associate { it.name to it.also { f -> f.isAccessible = true }.get(null) }
        assertEquals("the round-trip key set changed", frozen, declared)
    }

    @Test
    fun `a pre-field intent reposts with defaults (AC4, live notifications across an update)`() {
        // The shape of the FIRST shipped nav intents: text, id, page only.
        val intent = Intent().apply {
            putExtra(NotificationActionReceiver.EXTRA_TRANSCRIPTION_TEXT, "old")
            putExtra("notification_id", 3007)
            putExtra("page_index", 1)
        }
        val back = ResultNotificationSpecNav.readFrom(intent)
        assertNotNull(back)
        assertEquals("old", back!!.transcriptionText)
        assertEquals(3007, back.notificationId)
        assertEquals(1, back.pageIndex)
        assertEquals("", back.signatureText)
        assertEquals(false, back.repetitionSuspected)
        assertNull(back.saveFailureReason)
        assertNull(back.savedFolderName)
        assertEquals(false, back.repost)
    }

    @Test
    fun `a foreign intent without text or id reads null`() {
        assertNull(ResultNotificationSpecNav.readFrom(Intent()))
        val idOnly = Intent().putExtra("notification_id", 3007)
        assertNull(ResultNotificationSpecNav.readFrom(idOnly))
    }
}
