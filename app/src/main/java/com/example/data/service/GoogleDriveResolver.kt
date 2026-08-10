package com.example.data.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object GoogleDriveResolver {
    private const val TAG = "GoogleDriveResolver"
    val gdriveCookies = ConcurrentHashMap<String, String>()

    suspend fun resolveGoogleDriveUrl(driveUrl: String, accessToken: String? = null): String = withContext(Dispatchers.IO) {
        if (!driveUrl.contains("drive.google.com") && !driveUrl.contains("docs.google.com") && !driveUrl.contains("googleapis.com")) {
            return@withContext driveUrl
        }
        val fileId = extractFileId(driveUrl) ?: return@withContext driveUrl
        
        if (!accessToken.isNullOrBlank()) {
            val url = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
            val clientNoRedirect = OkHttpClient.Builder()
                .followRedirects(false)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .build()
            try {
                clientNoRedirect.newCall(request).execute().use { response ->
                    val location = response.header("Location")
                    if (!location.isNullOrBlank()) {
                        return@withContext location
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resolve authenticated Location", e)
            }
            return@withContext url
        }
        
        val cookieStore = mutableMapOf<String, String>()
        val client = OkHttpClient.Builder()
            .followRedirects(true)
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    for (cookie in cookies) {
                        cookieStore[cookie.name] = cookie.value
                    }
                }
                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    val cookies = mutableListOf<Cookie>()
                    cookieStore.forEach { (name, value) ->
                        try {
                            Cookie.Builder()
                                .name(name)
                                .value(value)
                                .domain(url.host)
                                .build()
                                .let { cookies.add(it) }
                        } catch (e: Exception) {}
                    }
                    return cookies
                }
            })
            .build()
            
        val initialUrl = "https://docs.google.com/uc?export=download&id=$fileId"
        val request = Request.Builder()
            .url(initialUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()
            
        try {
            client.newCall(request).execute().use { response ->
                val finalUrl = response.request.url.toString()
                val cookieHeader = cookieStore.map { "${it.key}=${it.value}" }.joinToString("; ")
                
                if (finalUrl.contains("googleusercontent.com")) {
                    if (cookieHeader.isNotBlank()) {
                        gdriveCookies[finalUrl] = cookieHeader
                    }
                    return@withContext finalUrl
                }
                
                val body = response.body?.string() ?: ""
                
                // Look for any confirmation token using multiple resilient regex patterns
                var token: String? = null
                
                val confirmRegex = "confirm=([^\"'&\\s>]+)".toRegex()
                val match = confirmRegex.find(body)
                if (match != null) {
                    token = match.groupValues[1]
                }
                
                if (token == null) {
                    val inputConfirmRegex = "name=\"confirm\"\\s+value=\"([^\"]+)\"".toRegex()
                    token = inputConfirmRegex.find(body)?.groupValues?.get(1)
                }
                if (token == null) {
                    val inputConfirmRegex2 = "value=\"([^\"]+)\"\\s+name=\"confirm\"".toRegex()
                    token = inputConfirmRegex2.find(body)?.groupValues?.get(1)
                }
                
                if (token == null) {
                    val formActionRegex = "action=\"([^\"]*confirm=([^\"'&\\s>]+)[^\"]*)\"".toRegex()
                    val formMatch = formActionRegex.find(body)
                    if (formMatch != null) {
                        token = formMatch.groupValues[2]
                    }
                }
                
                if (token != null) {
                    val confirmUrl = "https://docs.google.com/uc?export=download&id=$fileId&confirm=$token"
                    val confirmRequest = Request.Builder()
                        .url(confirmUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .build()
                    client.newCall(confirmRequest).execute().use { confirmResponse ->
                        val finalConfirmUrl = confirmResponse.request.url.toString()
                        val confirmCookieHeader = cookieStore.map { "${it.key}=${it.value}" }.joinToString("; ")
                        val returnedUrl = if (finalConfirmUrl.contains("googleusercontent.com")) finalConfirmUrl else confirmUrl
                        if (confirmCookieHeader.isNotBlank()) {
                            gdriveCookies[returnedUrl] = confirmCookieHeader
                        }
                        return@withContext returnedUrl
                    }
                }
                
                val formActionRegex = "id=\"downloadForm\"\\s+action=\"([^\"]+)\"".toRegex()
                val formMatch = formActionRegex.find(body)
                if (formMatch != null) {
                    val action = formMatch.groupValues[1].replace("&amp;", "&")
                    val fullAction = if (action.startsWith("/")) "https://docs.google.com/uc" + action else action
                    val confirmRequest = Request.Builder()
                        .url(fullAction)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .build()
                    client.newCall(confirmRequest).execute().use { confirmResponse ->
                        val finalConfirmUrl = confirmResponse.request.url.toString()
                        val confirmCookieHeader = cookieStore.map { "${it.key}=${it.value}" }.joinToString("; ")
                        val returnedUrl = if (finalConfirmUrl.contains("googleusercontent.com")) finalConfirmUrl else fullAction
                        if (confirmCookieHeader.isNotBlank()) {
                            gdriveCookies[returnedUrl] = confirmCookieHeader
                        }
                        return@withContext returnedUrl
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve confirm token", e)
        }
        
        return@withContext "https://docs.google.com/uc?export=download&id=$fileId"
    }

    fun extractFileId(url: String): String? {
        val fileRegex = "/d/([a-zA-Z0-9-_]+)".toRegex()
        val matchFile = fileRegex.find(url)
        if (matchFile != null) return matchFile.groupValues[1]
        
        val idRegex = "[?&]id=([a-zA-Z0-9-_]+)".toRegex()
        val matchId = idRegex.find(url)
        if (matchId != null) return matchId.groupValues[1]

        val filesPathRegex = "/files/([a-zA-Z0-9-_]+)".toRegex()
        val matchFilesPath = filesPathRegex.find(url)
        if (matchFilesPath != null) return matchFilesPath.groupValues[1]
        
        if (url.startsWith("gdrive_")) return url.removePrefix("gdrive_")
        if (!url.contains("/") && url.length > 15) return url.trim()
        return null
    }
}
