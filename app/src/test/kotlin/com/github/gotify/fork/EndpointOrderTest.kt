package com.github.gotify.fork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal class EndpointOrderTest {

    @Test
    fun `wifi prefers the internal address`() {
        assertEquals(
            listOf("https://int.example.com", "https://ext.example.com"),
            endpointOrder("https://ext.example.com", "https://int.example.com", true)
        )
    }

    @Test
    fun `mobile data prefers the public address`() {
        assertEquals(
            listOf("https://ext.example.com", "https://int.example.com"),
            endpointOrder("https://ext.example.com", "https://int.example.com", false)
        )
    }

    @Test
    fun `trailing slashes and blanks are ignored`() {
        assertEquals(
            listOf("https://ext.example.com", "https://int.example.com"),
            endpointOrder(" https://ext.example.com/ ", "https://int.example.com/", false)
        )
        assertEquals(
            listOf("https://ext.example.com"),
            endpointOrder("https://ext.example.com", "   ", true)
        )
    }

    @Test
    fun `duplicate addresses are dropped`() {
        assertEquals(
            listOf("https://ext.example.com"),
            endpointOrder("https://ext.example.com", "https://ext.example.com", true)
        )
    }

    @Test
    fun `invalid endpoints are ignored`() {
        assertEquals(
            listOf("https://ext.example.com"),
            endpointOrder("https://ext.example.com", "not a url", true)
        )
        assertEquals(
            listOf("https://ext.example.com"),
            endpointOrder("https://ext.example.com", "ftp://example.com", true)
        )
    }

    @Test
    fun `addresses without a scheme are normalized`() {
        assertEquals("https://gotify.example.com", normalizeEndpoint(" gotify.example.com/ "))
        assertEquals("http://192.168.1.10:8080", normalizeEndpoint("192.168.1.10:8080"))
        assertEquals("http://10.0.0.2:8080", normalizeEndpoint("10.0.0.2:8080"))
        assertEquals("https://example.com/gotify", normalizeEndpoint("https://example.com/gotify/"))
    }

    @Test
    fun `unusable addresses are rejected`() {
        assertNull(normalizeEndpoint(null))
        assertNull(normalizeEndpoint(""))
        assertNull(normalizeEndpoint("   "))
        assertNull(normalizeEndpoint("ftp://example.com"))
        assertNull(normalizeEndpoint("https://example.com/path?query=1"))
        assertNull(normalizeEndpoint("not a url"))
    }
}
