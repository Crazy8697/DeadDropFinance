package net.darkrelay.deaddropfinance.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SetupModeTest {
    @Test fun aFreshInstallMustRunSetup() = assertEquals(SetupMode.WIZARD, setupMode(false, false, hasAccounts = false))

    @Test fun anInstallFromSixWithAccountsGetsTheCardNotTheWizard() {
        assertEquals(SetupMode.CARD, setupMode(false, false, hasAccounts = true))
        assertEquals(SetupMode.NONE, setupMode(false, cardDismissed = true, hasAccounts = true))
    }


    @Test fun finishedSetupShowsNothing() = assertEquals(SetupMode.NONE, setupMode(true, false, hasAccounts = false))
}
