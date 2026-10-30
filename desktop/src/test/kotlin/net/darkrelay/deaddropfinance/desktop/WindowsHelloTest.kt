package net.darkrelay.deaddropfinance.desktop

import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Windows Hello bridge. Only availability() and the QueryInterface proof touch Windows; neither shows anything.
 * verify() is never called here: on a real machine it would put the Windows Hello dialog on screen.
 */
class WindowsHelloTest {
    private val op = "{9fc2b0bb-e446-44e2-aa61-9cab8f636af2}"

    @Test fun guidTextRoundTripsThroughTheStructLayout() {
        val g = WindowsHello.guid("af4f3f91-564c-4ddc-b8b5-973447627c65")
        assertEquals(0x91.toByte(), g[0], "Data1 is little endian in memory")
        assertEquals(0x4c.toByte(), g[4], "so is Data2")
        assertEquals(0xb8.toByte(), g[8], "the last 8 bytes stay in text order")
        assertEquals("af4f3f91-564c-4ddc-b8b5-973447627c65", WindowsHello.guidString(g))
    }

    @Test fun pinterfaceIidsMatchKnownWindowsValues() {
        // IAsyncOperation<Boolean> and IIterable<String>, as the Windows SDK headers list them
        assertEquals("cdb5efb3-5788-509d-9be1-71ccb8a3362a", WindowsHello.guidString(WindowsHello.pinterfaceIid("pinterface($op;b1)")))
        assertEquals(
            "e2fcc7c1-3bfc-5a0b-b2b0-72e769d1cb7e",
            WindowsHello.guidString(WindowsHello.pinterfaceIid("pinterface({faa585ea-6214-4217-afda-7f46de5869b3};string)")),
        )
    }

    @Test fun theTwoOperationIids() {
        // IAsyncOperation<UserConsentVerificationResult> is the value windows.security.credentials.ui.h lists; the
        // availability one is proven on a real machine by the QueryInterface test below
        assertEquals("fd596ffd-2318-558f-9dbe-d21df43764a5", WindowsHello.guidString(WindowsHello.VERIFICATION_OP_IID))
        assertEquals("ddd384f3-d818-5d83-ab4b-32119c28587c", WindowsHello.guidString(WindowsHello.AVAILABILITY_OP_IID))
    }

    @Test fun availabilityAnswersOnWindows() {
        assumeTrue(System.getProperty("os.name", "").startsWith("Windows"))
        val a = WindowsHello.availability()
        println("Windows Hello availability: $a")
        assertTrue(a in HelloVerifier.Availability.entries)
    }

    @Test fun theComputedIidIsTheOperationsOwnInterface() {
        assumeTrue(System.getProperty("os.name", "").startsWith("Windows"))
        val proof = assertNotNull(WindowsHello.queryInterfaceProof(), "WinRT's UserConsentVerifier could be reached")
        println("QueryInterface: computed 0x%08X, other 0x%08X, value %s".format(proof.computedHr, proof.otherHr, proof.valueThroughIt))
        assertEquals(0, proof.computedHr, "the operation answers to IAsyncOperation<UserConsentVerifierAvailability>")
        assertTrue(proof.otherHr < 0, "and not to IAsyncOperation<UserConsentVerificationResult>")
        val v = assertNotNull(proof.valueThroughIt, "the availability could be read through that interface")
        assertTrue(v in 0..4, "a UserConsentVerifierAvailability value, got $v")
    }
}
