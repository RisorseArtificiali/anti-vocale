package com.antivocale.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import androidx.test.core.app.ApplicationProvider
import com.antivocale.app.R
import com.antivocale.app.ui.appearance.LauncherIconManager
import com.antivocale.app.ui.appearance.LauncherIconVariant
import com.antivocale.app.transcription.BackendRegistry
import com.antivocale.app.transcription.emptyRecordsProvider
import com.antivocale.app.transcription.seedCatalogForTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Dynamic long-press share shortcuts (TASK-393 / GH #87) against Robolectric's
 * real ShortcutManager: shortcuts are read back from
 * [ShortcutManager.getDynamicShortcuts], no mocking of the launcher layer.
 * Because the shadow stores shortcuts in a HashMap, order is asserted through
 * each ShortcutInfo's rank, never through list order.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class ShareShortcutManagerTest {

    private lateinit var context: Context
    private lateinit var fake: FakePreferencesManager
    private lateinit var iconManager: LauncherIconManager
    private lateinit var manager: ShareShortcutManager
    private lateinit var shortcutManager: ShortcutManager

    /** Recency source under test; the production lambda reads the calibrator. */
    private var usage: List<RecentModelUse> = emptyList()

    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fake = FakePreferencesManager()
        seedCatalogForTest()
        val registry = BackendRegistry(ExternalModelStore(fake), emptyRecordsProvider())
        iconManager = LauncherIconManager(context)
        manager = ShareShortcutManager(
            context, fake, registry, iconManager, recentUsage = { usage },
        )
        shortcutManager = context.getSystemService(ShortcutManager::class.java)!!
        fake._advancedSharingEnabled.value = true
    }

    /**
     * Saves a model path for a backend (dir name deliberately matches no catalog
     * variant, so labels stay family-level and locale-independent). The LLM
     * backend stores its path in the generic preference, not a per-entry one.
     */
    private fun saveModel(backendId: String) {
        val path = "/data/models/used-$backendId"
        if (backendId == "llm") fake._modelPath.value = path
        else fake._sherpaModelPath(backendId).value = path
    }

    private fun use(backendId: String, lastUsedAt: Long) {
        usage = usage + RecentModelUse(backendId, lastUsedAt)
    }

    private fun dynamicShortcutsById(): Map<String, android.content.pm.ShortcutInfo> =
        shortcutManager.dynamicShortcuts.associateBy { it.id }

    // ---- ranking (pure) ----

    @Test
    fun `ranking keeps the most recent use per backend and orders by it`() {
        val ranked = rankRecentBackends(
            listOf(
                RecentModelUse("whisper", 100),
                RecentModelUse("llm", 300),
                // A newer whisper sample must beat the older one, not add a second entry.
                RecentModelUse("whisper", 500),
            ),
            limit = 3,
        )
        assertEquals(listOf("whisper", "llm"), ranked)
    }

    @Test
    fun `ranking caps at the limit`() {
        val ranked = rankRecentBackends(
            listOf(
                RecentModelUse("a", 1), RecentModelUse("b", 2),
                RecentModelUse("c", 3), RecentModelUse("d", 4),
            ),
            limit = 3,
        )
        assertEquals(listOf("d", "c", "b"), ranked)
    }

    // ---- registration ----

    @Test
    fun `refresh registers shortcuts in recency order with alias intents and localized labels`() = runTest {
        saveModel("whisper"); saveModel("sherpa-onnx"); saveModel("llm")
        use("sherpa-onnx", now - 10)
        use("whisper", now)
        use("llm", now - 5)

        manager.refresh()

        val byId = dynamicShortcutsById()
        assertEquals(setOf("share-whisper", "share-llm", "share-sherpa-onnx"), byId.keys)
        // Ranks mirror recency: whisper (newest) 0, llm 1, sherpa-onnx 2.
        assertEquals(0, byId.getValue("share-whisper").rank)
        assertEquals(1, byId.getValue("share-llm").rank)
        assertEquals(2, byId.getValue("share-sherpa-onnx").rank)

        val whisper = byId.getValue("share-whisper")
        // Exactly the static alias flow: explicit SEND to the manifest alias
        // component, which ShareReceiverActivity resolves back to the backend.
        val intent = whisper.intent!!
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("audio/*", intent.type)
        assertEquals("com.antivocale.app.ShareWhisper", intent.component?.className)
        val whisperName = context.getString(R.string.whisper_title)
        assertEquals(whisperName, whisper.shortLabel.toString())
        assertEquals(
            context.getString(R.string.share_shortcut_transcribe_with, whisperName),
            whisper.longLabel.toString(),
        )
    }

    @Test
    fun `refresh replaces stale shortcut ids`() = runTest {
        saveModel("whisper"); saveModel("llm"); saveModel("qwen3-asr")
        use("llm", now - 10); use("whisper", now - 5)
        manager.refresh()
        assertEquals(setOf("share-whisper", "share-llm"), dynamicShortcutsById().keys)

        usage = emptyList()
        use("qwen3-asr", now); use("whisper", now - 1)
        manager.refresh()

        val byId = dynamicShortcutsById()
        assertEquals(setOf("share-qwen3-asr", "share-whisper"), byId.keys)
    }

    @Test
    fun `at most three shortcuts are registered`() = runTest {
        for (id in listOf("whisper", "llm", "qwen3-asr", "gigaam", "nemotron-streaming")) saveModel(id)
        usage = listOf("whisper", "llm", "qwen3-asr", "gigaam", "nemotron-streaming")
            .mapIndexed { i, id -> RecentModelUse(id, now + i) }

        manager.refresh()

        // Timestamps rank nemotron > gigaam > qwen3 > llm > whisper; only the top 3 register.
        assertEquals(setOf("share-gigaam", "share-nemotron-streaming", "share-qwen3-asr"), dynamicShortcutsById().keys)
    }

    /**
     * Regression for the 2026-09-09 device trial: a shortcut is visible only
     * on the launcher activity the system resolved, and switching the
     * launcher-icon variant enables a DIFFERENT alias component. The set must
     * anchor to the enabled alias, and a variant switch must re-publish even
     * when the candidates are unchanged (the anchor is part of the refresh
     * signature for exactly this reason).
     */
    @Test
    fun `shortcuts anchor to the enabled alias and re-anchor on variant switch`() = runTest {
        saveModel("whisper"); use("whisper", now)

        manager.refresh()
        assertEquals(
            "fresh install anchors every shortcut on the default alias",
            List(shortcutManager.dynamicShortcuts.size) { "com.antivocale.app.LauncherDefault" },
            shortcutManager.dynamicShortcuts.map { it.activity!!.className },
        )

        iconManager.select(LauncherIconVariant.CROSSED)
        manager.refresh()
        assertEquals(
            "the switch moves the anchor even with identical candidates",
            List(shortcutManager.dynamicShortcuts.size) { "com.antivocale.app.LauncherCrossed" },
            shortcutManager.dynamicShortcuts.map { it.activity!!.className },
        )
        // Same set, new home: nothing lost in the move.
        assertEquals(setOf("share-whisper"), dynamicShortcutsById().keys)
    }

    @Test
    fun `launcher shortcut budget below three caps the set`() = runTest {
        shadowOf(shortcutManager).setMaxShortcutCountPerActivity(2)
        saveModel("whisper"); saveModel("llm"); saveModel("qwen3-asr")
        usage = listOf("whisper", "llm", "qwen3-asr").mapIndexed { i, id -> RecentModelUse(id, now + i) }

        manager.refresh()

        assertEquals(setOf("share-qwen3-asr", "share-llm"), dynamicShortcutsById().keys)
    }

    @Test
    fun `advanced sharing off clears every dynamic shortcut`() = runTest {
        saveModel("whisper"); use("whisper", now)
        manager.refresh()
        assertEquals(1, dynamicShortcutsById().size)

        fake._advancedSharingEnabled.value = false
        manager.refresh()

        assertTrue(dynamicShortcutsById().isEmpty())
    }

    @Test
    fun `backends without a saved model are skipped`() = runTest {
        // gigaam is the most recently used but has no model path; whisper has both.
        saveModel("whisper")
        use("gigaam", now); use("whisper", now - 1)

        manager.refresh()

        assertEquals(setOf("share-whisper"), dynamicShortcutsById().keys)
    }

    @Test
    fun `usage rows that resolve to no registered backend are skipped`() = runTest {
        // A deleted external record leaves its calibration key behind: no descriptor, no shortcut.
        saveModel("whisper")
        use("external:gone-record", now); use("whisper", now - 1)

        manager.refresh()

        assertEquals(setOf("share-whisper"), dynamicShortcutsById().keys)
    }

    @Test
    fun `no usage yields no shortcuts`() = runTest {
        saveModel("whisper")

        manager.refresh()

        assertTrue(dynamicShortcutsById().isEmpty())
    }
}
