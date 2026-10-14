package com.tosasitill.az100

import android.app.Application

/** Receives the framework service used to publish settings to scoped processes. */
class Az100Application : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.initialize(this)
    }
}
