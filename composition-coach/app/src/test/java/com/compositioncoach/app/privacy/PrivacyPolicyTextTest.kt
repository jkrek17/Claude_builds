package com.compositioncoach.app.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyPolicyTextTest {

    @Test
    fun `policy text is non-empty and reasonably substantial`() {
        assertFalse(PrivacyPolicyText.TEXT.isBlank())
        assertTrue("policy text should be a real document, not a placeholder", PrivacyPolicyText.TEXT.length > 500)
    }

    @Test
    fun `policy covers every point the app must disclose`() {
        val text = PrivacyPolicyText.TEXT.lowercase()
        assertTrue("must mention the camera", text.contains("camera"))
        assertTrue("must state analysis is on-device", text.contains("on your device") || text.contains("on-device"))
        assertTrue("must state there is no analytics", text.contains("no analytics"))
        assertTrue("must mention ML Kit / the face model", text.contains("ml kit"))
        assertTrue("must mention Google Play services (the unbundled model download path)", text.contains("play services"))
        assertTrue("must mention photos are saved to the gallery", text.contains("gallery") || text.contains("mediastore"))
    }

    @Test
    fun `title and last-updated line are present`() {
        assertFalse(PrivacyPolicyText.TITLE.isBlank())
        assertTrue(PrivacyPolicyText.LAST_UPDATED.startsWith("Last updated"))
    }
}
