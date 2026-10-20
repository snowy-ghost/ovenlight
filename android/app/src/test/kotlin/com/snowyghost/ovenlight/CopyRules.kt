package com.snowyghost.ovenlight

import org.junit.Assert.assertFalse

/** What every line a guest can see keeps to: no Tailscale, no tailnet, no em or en dashes. */
object CopyRules {
    fun assertNeutral(text: String) {
        assertFalse(text, text.lowercase().contains("tailscale"))
        assertFalse(text, text.lowercase().contains("tailnet"))
        assertFalse("no em or en dashes: $text", text.contains("\u2014") || text.contains("\u2013"))
    }
}
