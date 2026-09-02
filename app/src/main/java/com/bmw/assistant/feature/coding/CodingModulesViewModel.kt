package com.bmw.assistant.feature.coding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.bmw.assistant.BmwAssistantApp
import com.bmw.assistant.data.VerifiedMapImporter
import com.bmw.assistant.data.model.Module
import com.bmw.assistant.ui.common.Event
import kotlinx.coroutines.launch

data class ModuleCardUi(val module: Module, val codingCount: Int)

class CodingModulesViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as BmwAssistantApp).codingRepository

    private val _modules = MutableLiveData<List<ModuleCardUi>>(emptyList())
    val modules: LiveData<List<ModuleCardUi>> = _modules

    private val _notice = MutableLiveData<Event<String>>()
    val notice: LiveData<Event<String>> = _notice

    fun load() {
        viewModelScope.launch {
            repo.ensureSeeded()
            // Only modules that actually carry coding features appear on the coding list.
            val list = repo.getModules()
                .map { m -> ModuleCardUi(m, repo.codingCount(m.id)) }
                .filter { it.codingCount > 0 }
            _modules.value = list
        }
    }

    fun importMaps(json: String) {
        viewModelScope.launch {
            try {
                val patches = VerifiedMapImporter.parse(json)
                val n = repo.importVerifiedMaps(patches)
                _notice.value = Event(
                    if (n == 0) "No matching coding ids in the catalog."
                    else "Imported $n verified map(s). Those items can now be written on a real car."
                )
                load()
            } catch (e: Exception) {
                _notice.value = Event(e.message ?: "Import failed")
            }
        }
    }
}
