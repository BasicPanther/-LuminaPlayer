package com.example.data.service

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

object MediaSessionHolder {
    var activeSession: MediaSession? = null
}

class PlaybackService : MediaSessionService() {
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return MediaSessionHolder.activeSession
    }
}
