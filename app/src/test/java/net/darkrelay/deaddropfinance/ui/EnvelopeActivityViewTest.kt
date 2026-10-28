package net.darkrelay.deaddropfinance.ui

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import net.darkrelay.deaddropfinance.BuildConfig
import net.darkrelay.deaddropfinance.data.EnvelopeEntity
import net.darkrelay.deaddropfinance.data.MoveEntity
import net.darkrelay.deaddropfinance.data.MoveKind
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.EnvelopeActivityFixture
import net.darkrelay.deaddropfinance.domain.EnvelopeActivityFixture.budget
import net.darkrelay.deaddropfinance.domain.EnvelopeActivityFixture.today
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An envelope's Activity list and Move money's start from inside an envelope (1.2.3), with invented data. The compose
 * rule needs the debug build's test activity, so release unit tests skip these (see ui/PurgeWizardTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h4000dp-xhdpi")
class EnvelopeActivityViewTest {

    private val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain
        .outerRule { base, _ -> object : Statement() { override fun evaluate() { assumeTrue(BuildConfig.DEBUG); base.evaluate() } } }
        .around(compose)

    private fun detail(e: EnvelopeEntity, b: Budget = budget(), onTx: (String) -> Unit = {}) =
        compose.setContent { EnvelopeDetailScreen(e, b, today, {}, {}, {}, {}, {}, {}, onTx) }

    @Test fun aBusyEnvelopeListsEverything() {
        var opened: String? = null
        detail(EnvelopeActivityFixture.gaming) { opened = it }
        compose.onNodeWithText("Activity").assertExists()
        compose.onNodeWithText("Recent activity").assertDoesNotExist()
        for (l in listOf("Payday auto-fill", "Rollover sweep", "Moved in from Food", "Moved out to Food", "Moved in from Rollover", "Moved from Ready to Assign", "Moved to Ready to Assign", "SAMPLE REFUND")) {
            compose.onNodeWithText(l).assertExists()
        }
        compose.onAllNodesWithText("Assigned").assertCountEquals(2)
        compose.onNodeWithText("before budget start", substring = true).assertExists()
        compose.onNodeWithText("No transactions in this envelope yet.").assertDoesNotExist()
        compose.onNodeWithText("SAMPLE GAME STORE").performClick()
        assertEquals("s1", opened)
    }

    @Test fun onlyATargetShowsTheEmptyState() {
        detail(EnvelopeActivityFixture.onlyTarget)
        compose.onNodeWithText("No transactions in this envelope yet.").assertExists()
    }

    @Test fun rolloverShowsItsList() {
        detail(EnvelopeActivityFixture.rollover)
        compose.onNodeWithText("From rollover sweep").assertExists()
        compose.onNodeWithText("Moved out to Gaming").assertExists()
    }

    @Test fun unplannedShowsItsList() {
        detail(EnvelopeActivityFixture.unplanned)
        compose.onNodeWithText("SAMPLE SNACKS").assertExists()
    }

    @Test fun aLongListShowsAPageAtATime() {
        val extra = (1..2000).map { MoveEntity(id = "x$it", fromEnvelopeId = null, toEnvelopeId = "t", amount = 1, date = today.minusDays(it % 300L), kind = MoveKind.ASSIGN, createdAt = it.toLong(), updatedAt = 1) }
        val t0 = System.nanoTime()
        detail(EnvelopeActivityFixture.onlyTarget, budget(extra))
        compose.onAllNodesWithText("Assigned").assertCountEquals(ACTIVITY_PAGE)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms < 10_000)
        compose.onNodeWithText("Show more (${2000 - ACTIVITY_PAGE} left)").performClick()
        compose.onAllNodesWithText("Assigned").assertCountEquals(2 * ACTIVITY_PAGE)
    }

    @Test fun moveMoneyFromInsideAnEnvelopeStartsAsReadyToAssignIntoIt() {
        var got: Triple<String?, String?, Long>? = null
        compose.setContent { MoveMoneyScreen(budget(), "g", null, {}) { f, t, a -> got = Triple(f, t, a) } }
        compose.onNodeWithText("Ready to assign", substring = true).assertExists()
        compose.onNodeWithText("Gaming", substring = true).assertExists()
        compose.onNodeWithText("Amount").performTextInput("12.50")
        compose.onNodeWithText("Move").performClick()
        assertEquals(Triple<String?, String?, Long>(null, "g", 12_50), got)
    }

    @Test fun moveMoneyFromTheEnvelopesBarKeepsItsDefaults() {
        var got: Triple<String?, String?, Long>? = null
        val error = "Only $1.00 is ready to assign."
        compose.setContent { MoveMoneyScreen(budget(), null, error, {}) { f, t, a -> got = Triple(f, t, a) } }
        compose.onNodeWithText(error).assertExists()
        compose.onNodeWithText("Move").performClick()
        // From Ready to Assign, To the first envelope in fill order; an empty amount is sent as 0, as before
        assertEquals(Triple<String?, String?, Long>(null, budget().fillOrder.first().id, 0), got)
    }
}
