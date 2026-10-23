package com.github.gotify.fork

import org.junit.Assert.assertEquals
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
}
