package com.mini.lyricplayer

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        PlayerManager.init(this)
        PlayerManager.restoreLastSession()
        LyricOverlay.init(this)
        PlayerManager.addListener(LyricOverlay)
    }
}
