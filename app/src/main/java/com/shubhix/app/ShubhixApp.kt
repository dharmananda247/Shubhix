package com.shubhix.app

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * Owns the one MainVM for the whole process, so the app screen, the floating bubble and the
 * Quick Settings tile all talk to the same assistant.
 */
class ShubhixApp : Application(), ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()

    val vm: MainVM by lazy {
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(this))[MainVM::class.java]
    }

    /** True while the Shubhix screen is visible (the bubble only shows pop-up replies when it is not). */
    @Volatile var visible = false

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
