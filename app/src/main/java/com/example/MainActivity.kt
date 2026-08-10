package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.example.ui.screens.MainScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.MediaViewModel

import androidx.compose.runtime.mutableStateOf
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Log

class MainActivity : ComponentActivity() {
    companion object {
        var isPlayerActive = false
        val isInPipMode = mutableStateOf(false)
    }

    private val viewModel: MediaViewModel by viewModels {
        MediaViewModel.Factory(applicationContext)
    }

    private fun trustAllHosts() {
        try {
            val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
                object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                }
            )
            val sc = javax.net.ssl.SSLContext.getInstance("SSL")
            sc.init(null, trustAllCerts, java.security.SecureRandom())
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)
            javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to setup trust-all SSL context", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        trustAllHosts()
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme(darkTheme = true) {
                MainScreen(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isPlayerActive) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val params = PictureInPictureParams.Builder().build()
                    enterPictureInPictureMode(params)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to enter Picture-in-Picture automatically", e)
                }
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode.value = isInPictureInPictureMode
    }
}

