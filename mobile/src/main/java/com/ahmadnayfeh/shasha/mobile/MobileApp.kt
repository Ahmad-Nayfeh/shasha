package com.ahmadnayfeh.shasha.mobile

import android.app.Application

/** The phone app: Shasha («شاشة») for watching YouTube without ads, with downloads and a good player. */
class MobileApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        container.start()
        container.downloadNotifier.createChannels()
    }
}
