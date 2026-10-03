package com.ahmadnayfeh.shasha.mobile

import android.app.Application

/** The phone app: Shasha («شاشة») for watching YouTube without ads, with downloads and a good player. */
open class MobileApp : Application() {
    val container: AppContainer by lazy { createContainer() }

    /** The screenshot tests build the app's objects over fake sources here; the app itself never overrides this. */
    protected open fun createContainer(): AppContainer = AppContainer(this)

    override fun onCreate() {
        super.onCreate()
        container.start()
        container.downloadNotifier.createChannels()
    }
}
