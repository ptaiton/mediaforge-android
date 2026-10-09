package com.mediaforge.android

import android.app.Application

class MediaForgeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PushConfiguration.restore(this)
    }
}
