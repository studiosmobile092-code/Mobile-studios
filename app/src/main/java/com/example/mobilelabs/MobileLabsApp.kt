package com.example.mobilelabs

import android.app.Application
import com.example.mobilelabs.engine.SessionController

class MobileLabsApp : Application() {

    lateinit var sessionController: SessionController
        private set

    override fun onCreate() {
        super.onCreate()
        com.example.mobilelabs.diagnostics.LocalCrashHandler(applicationContext).install()
        sessionController = SessionController(applicationContext)
        sessionController.initialize()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::sessionController.isInitialized) {
            sessionController.onSystemTrimMemory(level)
        }
    }
}
