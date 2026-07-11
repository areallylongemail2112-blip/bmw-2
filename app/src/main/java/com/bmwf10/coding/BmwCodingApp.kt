package com.bmwf10.coding

import android.app.Application
import com.bmwf10.coding.data.CodingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BmwCodingApp : Application() {

    val repository: CodingRepository by lazy { CodingRepository.get(this) }

    override fun onCreate() {
        super.onCreate()
        // Seed the Room DB from the bundled JSON asset on first launch.
        CoroutineScope(Dispatchers.IO).launch { repository.ensureSeeded() }
    }
}
