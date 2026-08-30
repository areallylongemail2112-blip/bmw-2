package com.bmw.assistant.feature.coding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.bmw.assistant.BmwAssistantApp
import com.bmw.assistant.core.ecu.ConnectionManager
import com.bmw.assistant.data.model.CodingItem
import com.bmw.assistant.data.model.Module
import com.bmw.assistant.data.model.ValueSource
import com.bmw.assistant.ui.common.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CodingRowUi(
    val coding: CodingItem,
    val currentValueDisplay: String,
    val source: ValueSource,
    val verified: Boolean
)

class CodingListViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as BmwAssistantApp).codingRepository

    private val _module = MutableLiveData<Module?>()
    val module: LiveData<Module?> = _module

    private val _rows = MutableLiveData<List<CodingRowUi>>(emptyList())
    val rows: LiveData<List<CodingRowUi>> = _rows

    private val _busy = MutableLiveData(false)
    val busy: LiveData<Boolean> = _busy

    private val _message = MutableLiveData<Event<String>>()
    val message: LiveData<Event<String>> = _message

    fun load(moduleId: String, syncFromCar: Boolean = true) {
        viewModelScope.launch {
            val m = repo.getModule(moduleId)
            _module.value = m
            refreshRows(moduleId)
            if (syncFromCar && m != null && ConnectionManager.current.supportsCoding &&
                ConnectionManager.isLive
            ) {
                syncFromCar()
            }
        }
    }

    fun syncFromCar() {
        val m = _module.value ?: return
        if (!ConnectionManager.current.supportsCoding || !ConnectionManager.isLive) {
            _message.value = Event("Not connected. Open Connection and use ENET or demo mode.")
            return
        }
        _busy.value = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val engine = ConnectionManager.codingEngine()
                    ?: return@withContext "Not connected."
                var ok = 0
                var fail = 0
                repo.getCodingsForModule(m.id).forEach { coding ->
                    val live = runCatching { engine.readCoding(m, coding) }.getOrNull()
                    if (live != null) {
                        repo.setValue(coding.id, live, ValueSource.FROM_CAR)
                        ok++
                    } else {
                        fail++
                    }
                }
                "Read $ok value(s) from the car" + if (fail > 0) " · $fail could not be decoded." else "."
            }
            refreshRows(m.id)
            _busy.value = false
            _message.value = Event(result)
        }
    }

    private suspend fun refreshRows(moduleId: String) {
        val codings = repo.getCodingsForModule(moduleId)
        _rows.value = codings.map { c ->
            val stored = repo.getStoredValue(c)
            CodingRowUi(
                coding = c,
                currentValueDisplay = c.displayValue(stored.value),
                source = stored.source,
                verified = c.ecuMap?.verified == true
            )
        }
    }
}
