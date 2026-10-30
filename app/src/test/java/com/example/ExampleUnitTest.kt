package com.example

import com.example.engine.OfflineCommandEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    private val engine = OfflineCommandEngine()

    @Test
    fun testParsePhoneCallCommand() {
        val action = engine.parseCommand("call Ali")
        assertEquals("call", action.action)
        assertEquals("Ali", action.target)
        assertTrue(action.confirmationRequired)
        assertFalse(action.requiresUnlock)
    }

    @Test
    fun testParseSmsCommand() {
        val action = engine.parseCommand("send text to Mom saying I am on my way")
        assertEquals("send_sms", action.action)
        assertEquals("Mom", action.target)
        assertEquals("I am on my way", action.parameters["message"])
        assertTrue(action.confirmationRequired)
    }

    @Test
    fun testParseFlashlightToggle() {
        val onAction = engine.parseCommand("turn on flashlight")
        assertEquals("toggle_flashlight", onAction.action)
        assertEquals("on", onAction.target)
        assertFalse(onAction.confirmationRequired)

        val offAction = engine.parseCommand("turn off flashlight")
        assertEquals("toggle_flashlight", offAction.action)
        assertEquals("off", offAction.target)
    }

    @Test
    fun testParseAlarmCommand() {
        val action = engine.parseCommand("set alarm for 7:30 am")
        assertEquals("set_alarm", action.action)
        assertEquals("07:30", action.target)
        assertEquals("7", action.parameters["hour"])
        assertEquals("30", action.parameters["minute"])
    }

    @Test
    fun testParseSensitiveNotifications() {
        val action = engine.parseCommand("read notifications")
        assertEquals("read_notifications", action.action)
        assertTrue(action.requiresUnlock) // Sensitive action requires device unlock
    }

    @Test
    fun testParseNavigation() {
        val action = engine.parseCommand("navigate to Central Park")
        assertEquals("navigate", action.action)
        assertEquals("Central Park", action.target)
    }

    @Test
    fun testBilingualLanguageDetection() {
        val urduScript = com.example.engine.LanguageProcessor.detectLanguage("علی کو کال کرو")
        assertEquals(com.example.engine.DetectedLanguage.URDU_NASTALIQ, urduScript)

        val romanUrdu = com.example.engine.LanguageProcessor.detectLanguage("Ali ko call karo aur alarm laga do")
        assertEquals(com.example.engine.DetectedLanguage.ROMAN_URDU, romanUrdu)

        val mixed = com.example.engine.LanguageProcessor.detectLanguage("WhatsApp khol kar Ahmed ko message send karo ke I will be late")
        assertEquals(com.example.engine.DetectedLanguage.MIXED, mixed)

        val english = com.example.engine.LanguageProcessor.detectLanguage("Turn on the flashlight and set a timer")
        assertEquals(com.example.engine.DetectedLanguage.ENGLISH, english)
    }

    @Test
    fun testLanguageNormalization() {
        val normalizedTime = com.example.engine.LanguageProcessor.normalizeInput("kal subah do baje utha dena")
        assertTrue(normalizedTime.contains("2:00") || normalizedTime.contains("02:00"))

        val normalizedApp = com.example.engine.LanguageProcessor.normalizeInput("واٹس ایپ کھول دو")
        assertTrue(normalizedApp.contains("WhatsApp"))

        val resolvedNickname = com.example.engine.LanguageProcessor.resolveContactNickname("Ammi")
        assertEquals("Mom", resolvedNickname)
    }

    @Test
    fun testThinkingConfigDslAndSdkGenerationConfig() {
        val config = com.example.engine.gemini.thinking_config {
            enabled = true
            include_thoughts = true
            thinking_level = com.example.engine.gemini.ThinkingLevel.HIGH
            thinking_budget = 8192
        }
        assertTrue(config.include_thoughts)
        assertTrue(config.includeThoughts)
        assertEquals(8192, config.thinking_budget)
        assertEquals("HIGH", config.thinking_level)

        val client = com.example.engine.gemini.DefaultGeminiApiClient()
        val sdkGenConfig = client.buildSdkGenerationConfig(thinking_config = config)
        org.junit.Assert.assertNotNull(sdkGenConfig)
    }
}
