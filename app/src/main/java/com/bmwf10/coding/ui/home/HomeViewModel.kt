package com.bmwf10.coding.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.bmwf10.coding.BmwCodingApp
import com.bmwf10.coding.data.model.Module
import kotlinx.coroutines.launch

data class ModuleCardUi(val module: Module, val codingCount: Int)

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as BmwCodingApp).repository

    private val _modules = MutableLiveData<List<ModuleCardUi>>(emptyList())
    val modules: LiveData<List<ModuleCardUi>> = _modules

    fun load() {
        viewModelScope.launch {
            repo.ensureSeeded()
            val list = repo.getModules().map { m ->
                ModuleCardUi(m, repo.codingCount(m.id))
            }
            _modules.value = list
        }
    }
}
