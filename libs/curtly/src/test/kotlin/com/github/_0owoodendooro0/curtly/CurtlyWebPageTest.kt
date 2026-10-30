package com.github._0owoodendooro0.curtly

import kotlin.test.Test
import kotlin.test.assertTrue

class CurtlyWebPageTest {

    @Test
    fun testDefaultWebPageHtmlGeneration() {
        val html = CurtlyWebPage.getHtml()

        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.contains("<title>Curtly - Short URL Generator</title>"))
        assertTrue(html.contains("const API_ENDPOINT = \"/api/shorten\";"))
        assertTrue(html.contains("id=\"shorten-form\""))
        assertTrue(html.contains("id=\"longUrl\""))
        assertTrue(html.contains("id=\"customKey\""))
        assertTrue(html.contains("id=\"expiresInSeconds\""))
        assertTrue(html.contains("id=\"maxClicks\""))
    }

    @Test
    fun testCustomParameters() {
        val customEndpoint = "/v1/urls/shorten"
        val customTitle = "My Company Shortener"

        val html = CurtlyWebPage.getHtml(
            apiEndpoint = customEndpoint,
            title = customTitle
        )

        assertTrue(html.contains("<title>My Company Shortener</title>"))
        assertTrue(html.contains("const API_ENDPOINT = \"/v1/urls/shorten\";"))
    }
}
