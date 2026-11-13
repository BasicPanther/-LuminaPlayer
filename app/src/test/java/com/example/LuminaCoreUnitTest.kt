package com.example

import com.example.data.service.CloudSyncService
import com.example.data.service.MetadataService
import com.example.data.service.SmbNetworkService
import okhttp3.Credentials
import org.junit.Assert.*
import org.junit.Test

class LuminaCoreUnitTest {

    @Test
    fun smbUri_roundTripWithCredentialsAndSpaces() {
        val uri = SmbNetworkService.buildSmbUri(
            host = "192.168.1.50",
            port = 445,
            shareName = "Media",
            subPath = "TV Shows/Breaking Bad/S01E01 Pilot.mkv",
            username = "john doe",
            password = "p@ss:word/1"
        )
        assertTrue(uri.startsWith("smb://"))
        val parsed = SmbNetworkService.parseSmbUri(uri)
        assertNotNull(parsed)
        assertEquals("192.168.1.50", parsed!!.host)
        assertEquals(445, parsed.port)
        assertEquals("Media", parsed.shareName)
        assertEquals("TV Shows/Breaking Bad/S01E01 Pilot.mkv", parsed.subPath)
        assertEquals("john doe", parsed.username)
        assertEquals("p@ss:word/1", parsed.password)
    }

    @Test
    fun httpAuthUrl_encodesAndExtractsCleanly() {
        val raw = CloudSyncService.buildAuthenticatedHttpUrl(
            baseUrl = "https://192.168.1.100:5006/dav",
            username = "user@home",
            password = "s3cr/et:pw"
        )
        val (cleanUrl, authHeader) = CloudSyncService.extractUrlAndAuth(raw)
        assertEquals("https://192.168.1.100:5006/dav", cleanUrl)
        assertEquals(Credentials.basic("user@home", "s3cr/et:pw"), authHeader)
    }

    @Test
    fun resolveGeminiApiKey_prioritizesUserKeyOverPlaceholders() {
        MetadataService.activeGeminiApiKey = "AIzaTestUserSavedKey"
        assertEquals("AIzaTestUserSavedKey", MetadataService.resolveGeminiApiKey())
        assertEquals("AIzaExplicitOverride", MetadataService.resolveGeminiApiKey("AIzaExplicitOverride"))
        MetadataService.activeGeminiApiKey = "MY_GEMINI_API_KEY"
        assertNotEquals("MY_GEMINI_API_KEY", MetadataService.resolveGeminiApiKey())
        MetadataService.activeGeminiApiKey = ""
    }
}
