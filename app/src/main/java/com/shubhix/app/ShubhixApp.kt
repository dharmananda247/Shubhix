package com.shubhix.app

import android.app.Application

class ShubhixApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
