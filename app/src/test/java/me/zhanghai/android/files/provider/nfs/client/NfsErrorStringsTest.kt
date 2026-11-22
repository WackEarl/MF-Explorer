package me.zhanghai.android.files.provider.nfs.client

import android.content.res.Configuration
import java.util.Locale
import me.zhanghai.android.files.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NfsErrorStringsTest {
    @Test
    fun englishAndGermanMessagesMatchTheMapping() {
        val app = RuntimeEnvironment.getApplication()
        val english = app.resources
        assertEquals("The NFS request timed out", english.getString(R.string.nfs_error_timeout))
        assertEquals(
            "Connection to the NFS server was lost",
            english.getString(R.string.nfs_error_connection_lost)
        )
        assertEquals(
            "Access to the NFS server was denied",
            english.getString(R.string.nfs_error_access_denied)
        )
        assertEquals(
            "The NFS server is unreachable",
            english.getString(R.string.nfs_error_server_unreachable)
        )
        assertEquals(
            "The NFS export was not found",
            english.getString(R.string.nfs_error_export_not_found)
        )

        val germanConfig = Configuration(english.configuration)
        germanConfig.setLocale(Locale.GERMAN)
        val german = app.createConfigurationContext(germanConfig).resources
        assertEquals(
            "Zeitüberschreitung bei der NFS-Anfrage",
            german.getString(R.string.nfs_error_timeout)
        )
        assertEquals(
            "Verbindung zum NFS-Server verloren",
            german.getString(R.string.nfs_error_connection_lost)
        )
        assertEquals(
            "Zugriff auf den NFS-Server verweigert",
            german.getString(R.string.nfs_error_access_denied)
        )
        assertEquals(
            "NFS-Server nicht erreichbar",
            german.getString(R.string.nfs_error_server_unreachable)
        )
        assertEquals(
            "NFS-Export nicht gefunden",
            german.getString(R.string.nfs_error_export_not_found)
        )
    }
}
