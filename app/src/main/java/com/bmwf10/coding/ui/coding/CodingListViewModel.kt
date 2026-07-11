package com.bmwf10.coding.ui.coding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.bmwf10.coding.BmwCodingApp
import com.bmwf10.coding.data.model.CodingItem
import com.bmwf10.coding.data.model.Module
import kotlinx.coroutines.launch

data class CodingRowUi(
    val coding: CodingItem,
    val currentValueDisplay: String
)

class CodingListViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as BmwCodingApp).repository

    private val _module = MutableLiveData<Module?>()
    val module: LiveData<Module?> = _module

    private val _rows = MutableLiveData<List<CodingRowUi>>(emptyList())
    val rows: LiveData<List<CodingRowUi>> = _rows

    fun load(moduleId: String) {
        viewModelScope.launch {
            _module.value = repo.getModule(moduleId)
            val codings = repo.getCodingsForModule(moduleId)
            _rows.value = codings.map { c ->
                CodingRowUi(c, c.displayValue(repo.getValue(c)))
            }
        }
    }
}
