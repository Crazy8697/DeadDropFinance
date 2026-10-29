package net.darkrelay.deaddropfinance

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element

/**
 * The bank notification reader is gone (1.5.0): the merged manifest has no notification listener, no notification
 * access, no spend alert receiver and no query for the phone's launcher apps, while the notification permission that
 * bill reminders and autopay warnings need stays. No reader code is left in the app or the code it shares.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NoNotificationReaderTest {

    /** The merged manifest the unit tests run with (the Android Gradle plugin's test config names it). */
    private val merged: File by lazy {
        val config = "com/android/tools/test_config.properties"
        val stream = listOfNotNull(Thread.currentThread().contextClassLoader, javaClass.classLoader, ClassLoader.getSystemClassLoader())
            .firstNotNullOfOrNull { it.getResourceAsStream(config) }
        val variant = BuildConfig.BUILD_TYPE
        val path = stream?.use { s -> Properties().apply { load(s) }.getProperty("android_merged_manifest") }
            ?: "build/intermediates/packaged_manifests/${variant}UnitTest/process${variant.replaceFirstChar { it.uppercase() }}UnitTestManifest/AndroidManifest.xml"
        listOf(File(path), File("app", path)).first { it.isFile }
    }

    @Test fun theMergedManifestHasNoReaderAndKeepsThePostNotificationsPermission() {
        val text = merged.readText()
        for (gone in listOf(
            "BIND_NOTIFICATION_LISTENER_SERVICE", "NotificationListenerService", "BankNotificationListener", "AlertActionReceiver",
            "bank notifications",
        )) assertFalse(gone, text.contains(gone))
        assertTrue(text.contains("android.permission.POST_NOTIFICATIONS"))

        // the only LAUNCHER entry is the app's own icon: no <queries> asks to see the phone's launcher apps
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(merged)
        val queries = doc.getElementsByTagName("queries")
        for (i in 0 until queries.length) {
            val cats = (queries.item(i) as Element).getElementsByTagName("category")
            for (j in 0 until cats.length) {
                val name = (cats.item(j) as Element).getAttributeNS("http://schemas.android.com/apk/res/android", "name")
                assertFalse("no launcher query", name == "android.intent.category.LAUNCHER")
            }
        }
    }

    @Test fun androidSeesNoListenerServiceOrApproveReceiver() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val info = ctx.packageManager.getPackageInfo(
            ctx.packageName, PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PERMISSIONS,
        )
        assertTrue(info.services.orEmpty().none { it.permission == "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" })
        assertTrue(info.services.orEmpty().none { it.name.endsWith("BankNotificationListener") })
        assertTrue(info.receivers.orEmpty().none { it.name.endsWith("AlertActionReceiver") })
        assertTrue(info.requestedPermissions.orEmpty().contains("android.permission.POST_NOTIFICATIONS"))
    }

    @Test fun noReaderCodeIsLeft() {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val shared = listOf(File("../shared/src"), File("shared/src")).first { it.isDirectory }
        val code = (main.walkTopDown() + shared.walkTopDown()).filter { it.isFile && it.extension == "kt" }.toList()
        val gone = listOf(
            "NotificationListenerService", "BankNotificationListener", "NotifParse", "NotifLog", "NotifReadingScreen", "Nav.Reading",
            "OPEN_READING", "AlertActionReceiver", "StaleNotificationEntries", "notifReading", "notifPackages", "alertSpending",
            "upsertNotifApp", "ACTION_NOTIFICATION_LISTENER",
        )
        val hits = code.flatMap { f -> gone.filter { f.readText().contains(it) }.map { "${f.name}: $it" } }
        assertEquals(emptyList<String>(), hits)
    }
}
