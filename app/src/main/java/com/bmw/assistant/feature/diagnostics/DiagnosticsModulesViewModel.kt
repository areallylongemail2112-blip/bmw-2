package com.bmw.assistant.feature.diagnostics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.bmw.assistant.BmwAssistantApp
import com.bmw.assistant.data.model.Module
import kotlinx.coroutines.launch

data class DiagModuleUi(val module: Module, val hasLiveData: Boolean)

class DiagnosticsModulesViewModel(app: Application) : AndroidViewModel(app) {

    private val codingRepo = (app as BmwAssistantApp).codingRepository
    private val diagRepo = (app as BmwAssistantApp).diagnosticsRepository

    private val _modules = MutableLiveData<List<DiagModuleUi>>(emptyList())
    val modules: LiveData<List<DiagModuleUi>> = _modules

    fun load() {
        viewModelScope.launch {
            codingRepo.ensureSeeded()
            // Every module can report fault codes; some also expose live data.
            _modules.value = codingRepo.getModules().map { m ->
                DiagModuleUi(m, diagRepo.hasLiveData(m.id))
            }
        }
    }
}
