package suwayomi.tachidesk.manga.impl.util

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PackageToolsTest {
    @Test
    fun `accepts current extension API from manifest metadata`() {
        val packageInfo = packageInfo(versionName = "99.0.0", metadataVersion = 1.6f)

        assertEquals(1.6, PackageTools.requireSupportedExtensionLibVersion(packageInfo))
    }

    @Test
    fun `accepts legacy extension API from version name`() {
        val packageInfo = packageInfo(versionName = "1.4.42")

        assertEquals(1.4, PackageTools.requireSupportedExtensionLibVersion(packageInfo))
    }

    @Test
    fun `rejects versions between discrete supported APIs`() {
        val packageInfo = packageInfo(versionName = "1.5.1")

        val error =
            assertThrows(ExtensionCompatibilityException::class.java) {
                PackageTools.requireSupportedExtensionLibVersion(packageInfo)
            }
        assertEquals(
            "Extension bytecode is not compatible with this platform: " +
                "extension API version is 1.5; supported versions are 1.4, 1.6",
            error.message,
        )
    }

    private fun packageInfo(
        versionName: String,
        metadataVersion: Float? = null,
    ): PackageInfo =
        PackageInfo().apply {
            this.versionName = versionName
            applicationInfo =
                ApplicationInfo().apply {
                    metaData =
                        Bundle().apply {
                            metadataVersion?.let {
                                putFloat(PackageTools.METADATA_EXTENSION_LIB, it)
                            }
                        }
                }
        }
}
