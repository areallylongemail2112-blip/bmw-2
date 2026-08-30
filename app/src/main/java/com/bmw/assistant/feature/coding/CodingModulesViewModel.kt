package com.bmw.assistant.feature.coding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.bmw.assistant.BmwAssistantApp
import com.bmw.assistant.core.ecu.ConnectionManager
import com.bmw.assistant.data.VerifiedMapImporter
import com.bmw.assistant.data.model.Module
import com.bmw.assistant.ui.common.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ModuleCardUi(val module: Module, val codingCount: Int)

class CodingModulesViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as BmwAssistantApp).codingRepository

    private val _modules = MutableLiveData<List<ModuleCardUi>>(emptyList())
    val modules: LiveData<List<ModuleCardUi>> = _modules

    private val _message = MutableLiveData<Event<String>>()
    val message: LiveData<Event<String>> = _message

    fun load() {
        viewModelScope.launch {
            repo.ensureSeeded()
            val identity = ConnectionManager.current.identity
            val list = repo.getModules()
                .filter { identity == null || identity.isModulePresent(it.id) }
                .map { m -> ModuleCardUi(m, repo.codingCount(m.id)) }
                .filter { it.codingCount > 0 }
            _modules.value = list
        }
    }

    fun importMapsJson(json: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val data = VerifiedMapImporter.parse(json)
                    val connectedVin = ConnectionManager.current.identity?.vin
                    if (!data.vin.isNullOrBlank() && !connectedVin.isNullOrBlank() &&
                        !data.vin.equals(connectedVin, ignoreCase = true)
                    ) {
                        return@withContext "Maps are for VIN ${data.vin}, but the connected car is $connectedVin. Import cancelled."
                    }
                    val count = repo.importMaps(data)
                    val verified = data.codings.count { it.ecuMap?.verified == true }
                    "Imported $count coding map(s) ($verified verified)."
                } catch (e: Exception) {
                    e.message ?: "Import failed"
                }
            }
            load()
            _message.value = Event(result)
        }
    }
}
