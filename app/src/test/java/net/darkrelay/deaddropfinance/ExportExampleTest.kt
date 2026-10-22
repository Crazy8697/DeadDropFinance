package net.darkrelay.deaddropfinance

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import net.darkrelay.deaddropfinance.domain.SetupExample
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith

/**
 * Export example's production path: the app's own shipped copy of docs/IMPORT.md (assets/docs/IMPORT.md, from the
 * copyImportDoc Gradle task) still has a fenced example SetupExample.extract can pull out. The example's own
 * contents are checked against the importer in domain/SetupImportTest.
 */
@RunWith(RobolectricTestRunner::class)
class ExportExampleTest {

    @Test fun theShippedDocHasAnExtractableExample() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val doc = ctx.assets.open("docs/IMPORT.md").use { String(it.readBytes(), Charsets.UTF_8) }
        val json = SetupExample.extract(doc)
        assertTrue(json != null && json.contains("\"format\": \"deaddropfinance-setup\""))
    }
}
