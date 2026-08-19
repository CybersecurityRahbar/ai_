package com.example.personalmemoryai

import android.app.Application
import com.example.personalmemoryai.diagnostics.DiagnosticsManager

class PersonalMemoryApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticsManager.get(this).installGlobalCrashHandler()
    }
}
