package com.virtualcamerapro.setup

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virtualcamerapro.broker.BrokerSessionStore
import com.virtualcamerapro.engine.OutputSettings
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Uses isolated private storage; it never changes the user's broker session or camera. */
@RunWith(AndroidJUnit4::class)
class BrokerSessionStoreTest {
    private lateinit var isolated: IsolatedContext
    private lateinit var source: File
    private val settings = OutputSettings(width = 640, height = 480, cameraId = "rear_main", loop = false,
        rotation = 90, scaleMode = "fill", zoom = 2.0, panX = -0.5, panY = 0.25)

    @Before fun createIsolatedSessionStorage() {
        isolated = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        source = File(isolated.filesDir, "source-session-test.media").apply {
            writeBytes(ByteArray(1024) { index -> ((index * 37 + 11) and 255).toByte() })
        }
    }

    @After fun removeOnlyThisTestsPrivateFiles() { isolated.dispose() }

    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun save() = BrokerSessionStore(isolated).save(source, "video", hash(source), settings)
    private fun preferences() = isolated.getSharedPreferences("guest-broker-session-v1", Context.MODE_PRIVATE)

    @Test fun explicitDeactivationRetainsSelectionButDoesNotRequestResume() {
        save()
        BrokerSessionStore(isolated).inactive()
        val recreated = BrokerSessionStore(isolated)
        assertFalse("An explicitly deactivated camera must stay off", recreated.requested())
        val selection = requireNotNull(recreated.load())
        assertEquals(source, selection.file)
        assertEquals(settings, selection.settings)
        assertTrue("Deactivate preserves selected media for a later manual activation", source.exists())
        assertFalse("Persisted selection must not contain an Active/frame proof", preferences().getString("source", "")!!.contains("proof"))
    }

    @Test fun changedMediaCannotBecomeAValidRestoredSession() {
        save()
        val original = source.readBytes()
        original[original.size / 2] = (original[original.size / 2].toInt() xor 0x7f).toByte()
        source.writeBytes(original)
        assertNull("Changed media must not be silently resumed under its previous hash", BrokerSessionStore(isolated).load())
    }

    @Test fun clearingSelectionRemovesTheBrokerCopyAndPersistedIntent() {
        save()
        val unrelated = File(isolated.filesDir, "keep-unrelated.txt").apply { writeText("preserved") }
        BrokerSessionStore(isolated).clear()
        val recreated = BrokerSessionStore(isolated)
        assertFalse(recreated.requested())
        assertNull(recreated.load())
        assertFalse("Clear selection must remove the retained broker media copy", source.exists())
        assertEquals("preserved", unrelated.readText())
        assertFalse(preferences().contains("source"))
    }

    @Test fun aCorruptedJournalCannotReadOrDeleteAFileOutsideItsPrivateSourceDirectory() {
        save()
        val outside = File(isolated.filesDir.parentFile, "source-outside-${UUID.randomUUID()}.media").apply { writeText("outside sentinel") }
        try {
            val record = JSONObject(preferences().getString("source", null)!!).put("file", "../${outside.name}")
            assertTrue(preferences().edit().putString("source", record.toString()).commit())
            assertNull(BrokerSessionStore(isolated).load())
            BrokerSessionStore(isolated).clear()
            assertEquals("outside sentinel", outside.readText())
            assertFalse(BrokerSessionStore(isolated).requested())
        } finally { assertTrue(outside.delete()) }
    }

    @Test fun corruptedPreferenceTypesDoNotRequestActivationOrDeleteUnidentifiedMedia() {
        assertTrue(preferences().edit().putString("requestedActive", "true").putInt("source", 42).commit())
        val store = BrokerSessionStore(isolated)
        assertFalse(store.requested())
        assertNull(store.load())
        store.clear()
        assertTrue("An unrecognized journal is not authority to delete a file", source.exists())
    }

    private class IsolatedContext(private val actual: Context) : ContextWrapper(actual) {
        private val prefix = "broker-session-regression-${UUID.randomUUID()}-"
        private val directory = File(actual.cacheDir, prefix).apply { check(mkdir()) }
        private val names = mutableSetOf<String>()
        override fun getFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = prefix + name
            names.add(isolatedName)
            return actual.getSharedPreferences(isolatedName, mode)
        }
        fun dispose() {
            check(directory.canonicalFile.parentFile == actual.cacheDir.canonicalFile)
            directory.listFiles()?.forEach { check(it.isFile && it.delete()) }
            check(directory.delete())
            names.forEach { actual.deleteSharedPreferences(it) }
        }
    }
}
