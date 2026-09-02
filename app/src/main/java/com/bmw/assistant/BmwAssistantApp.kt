package com.bmw.assistant

import android.app.Application
import com.bmw.assistant.data.CodingRepository
import com.bmw.assistant.data.DiagnosticsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Application entry point. Holds the app-wide repositories and warms them on first launch. */
class BmwAssistantApp : Application() {

    val codingRepository: CodingRepository by lazy { CodingRepository.get(this) }
    val diagnosticsRepository: DiagnosticsRepository by lazy { DiagnosticsRepository.get(this) }

    override fun onCreate() {
        super.onCreate()
        // Seed the coding DB and preload diagnostics definitions from the bundled JSON assets.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                codingRepository.ensureSeeded()
            } catch (_: Exception) {
                deleteDatabase("bmw_assistant.db")
                codingRepository.ensureSeeded()
            }
            diagnosticsRepository.ensureLoaded()
        }
    }
}
