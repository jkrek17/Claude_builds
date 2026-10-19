package com.compositioncoach.app.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The Compare chip's pre-level copies: private cache files that never outlive their review. */
class OriginalCopyStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store() = OriginalCopyStore(temp.root)

    @Test
    fun `a saved copy lives in the cache's originals folder with the original bytes`() {
        val bytes = byteArrayOf(1, 2, 3)
        val copy = store().save(bytes)
        assertNotNull(copy)
        assertEquals(File(temp.root, "originals").canonicalFile, copy!!.canonicalFile.parentFile)
        assertArrayEquals(bytes, copy.readBytes())
    }

    @Test
    fun `delete removes its own copies`() {
        val store = store()
        val copy = store.save(byteArrayOf(9))!!
        assertTrue(store.delete(copy.path))
        assertFalse(copy.exists())
        assertFalse(store.delete(copy.path))
    }

    @Test
    fun `delete refuses anything that is not one of its copies`() {
        val store = store()
        val photo = temp.newFile("CC_20261019.jpg")
        assertFalse(store.delete(photo.path))
        assertTrue(photo.exists())
        File(temp.root, "originals").mkdirs()
        // Named like a copy, but reached through ".." so it actually sits outside the store's folder.
        val outside = File(File(temp.root, "originals/.."), "cc_original_1.jpg").apply { writeBytes(byteArrayOf(1)) }
        assertFalse(store.delete(outside.path))
        assertTrue(outside.exists())
        assertFalse(store.delete(null))
    }

    @Test
    fun `purge clears every copy left by a previous process`() {
        val store = store()
        store.save(byteArrayOf(1))
        store.save(byteArrayOf(2))
        assertEquals(2, store.purge())
        assertEquals(0, store.purge())
    }
}
