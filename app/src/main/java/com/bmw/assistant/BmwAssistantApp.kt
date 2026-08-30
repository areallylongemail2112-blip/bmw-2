package com.bmw.assistant

import android.app.Application
import com.bmw.assistant.data.CodingRepository
import com.bmw.assistant.data.DiagnosticsRepository
import com.bmw.assistant.data.ServiceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Application entry point. Holds the app-wide repositories and warms them on first launch. */
class BmwAssistantApp : Application() {

    val codingRepository: CodingRepository by lazy { CodingRepository.get(this) }
    val diagnosticsRepository: DiagnosticsRepository by lazy { DiagnosticsRepository.get(this) }
    val serviceRepository: ServiceRepository by lazy { ServiceRepository.get(this) }

    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO).launch {
            codingRepository.ensureSeeded()
            diagnosticsRepository.ensureLoaded()
            serviceRepository.ensureLoaded()
        }
    }
}
