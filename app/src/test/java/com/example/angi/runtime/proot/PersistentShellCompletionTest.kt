package com.example.angi.runtime.proot

import org.junit.Assert.*
import org.junit.Test

class PersistentShellCompletionTest {
    @Test fun stdoutCompletionMarkerParsesAfterPriorCommandOutput() {
        val rs = "\u001e"
        val us = "\u001f"
        val marker = "${rs}abc123${us}0${us}2731${us}/workspace${rs}"
        val parsed = PersistentSandboxShell.parseCompletionMarker(marker, "abc123")
        assertNotNull(parsed)
        assertEquals(0, parsed!!.exitCode)
        assertEquals(2731, parsed.bashPid)
        assertEquals("/workspace", parsed.cwd)
    }

    @Test fun unrelatedJsonAndWrongNonceNeverCompleteCommand() {
        val rs = "\u001e"
        val us = "\u001f"
        assertNull(PersistentSandboxShell.parseCompletionMarker("""{"data":[{"id":"angi-active-model"}]}""", "abc123"))
        assertNull(PersistentSandboxShell.parseCompletionMarker("${rs}previous${us}0${us}2731${us}/root${rs}", "abc123"))
        assertNull(PersistentSandboxShell.parseCompletionMarker("${rs}abc123${us}bad${us}2731${us}/root${rs}", "abc123"))
    }
}
