package com.bmw.assistant.feature.coding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.bmw.assistant.BmwAssistantApp
import com.bmw.assistant.core.ecu.ConnectionManager
import com.bmw.assistant.core.ecu.Hex
import com.bmw.assistant.data.model.CodingItem
import com.bmw.assistant.data.model.Module
import com.bmw.assistant.data.model.ValueType
import com.bmw.assistant.ui.common.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class ApplyResult {
    data class Success(val newDisplay: String, val rawByte: String?) : ApplyResult()
    data class Error(val message: String) : ApplyResult()
    object NeedsConnection : ApplyResult()
}

/** Coding definition plus the value used to initialize the edit controls. */
data class EditUiModel(
    val coding: CodingItem,
    val module: Module?,
    val currentValue: String
)

class EditCodingViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as BmwAssistantApp).codingRepository

    private val _ui = MutableLiveData<EditUiModel?>()
    val ui: LiveData<EditUiModel?> = _ui

    private val _coding = MutableLiveData<CodingItem?>()
    val coding: LiveData<CodingItem?> = _coding

    private val _module = MutableLiveData<Module?>()
    val module: LiveData<Module?> = _module

    private val _currentValue = MutableLiveData<String>()
    val currentValue: LiveData<String> = _currentValue

    private val _busy = MutableLiveData(false)
    val busy: LiveData<Boolean> = _busy

    private val _result = MutableLiveData<Event<ApplyResult>>()
    val result: LiveData<Event<ApplyResult>> = _result

    fun load(codingId: String) {
        viewModelScope.launch {
            val c = repo.getCoding(codingId) ?: return@launch
            val m = repo.getModule(c.moduleId)
            val value = repo.getValue(c)
            _coding.value = c
            _module.value = m
            _currentValue.value = value
            // Publish atomically so the Activity can bind controls with the right value.
            _ui.value = EditUiModel(c, m, value)
        }
    }

    /**
     * Validates [input] against the coding's type/range. Returns an error string, or null
     * if valid.
     */
    fun validate(input: String): String? {
        val c = _coding.value ?: return "Not loaded"
        return when (c.valueType) {
            ValueType.BOOLEAN -> if (input == "true" || input == "false") null
            else "Value must be true or false"
            ValueType.ENUM ->
                if (c.options?.any { it.value == input } == true) null
                else "Choose one of the listed options"
            ValueType.INTEGER -> {
                val n = input.toIntOrNull() ?: return "Enter a whole number"
                val min = c.min ?: Int.MIN_VALUE
                val max = c.max ?: Int.MAX_VALUE
                if (n < min || n > max) "Enter a value between $min and $max" else null
            }
            ValueType.HEX -> {
                val cleaned = input.trim().removePrefix("0x").removePrefix("0X")
                if (cleaned.isEmpty() || !cleaned.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' })
                    return "Enter a hex value like 0x1A"
                val expected = c.hexLength ?: 2
                if (cleaned.length > expected) "Value must be at most $expected hex digits" else null
            }
        }
    }

    /**
     * Applies [newValue] to the ECU. Enforces the connection guard here in the ViewModel:
     * without an active, connected transport the write is refused and never reaches hardware.
     */
    fun apply(newValue: String) {
        val c = _coding.value ?: return
        val m = _module.value ?: return

        val conn = ConnectionManager.current
        if (!conn.isConnected) {
            _result.value = Event(ApplyResult.NeedsConnection)
            return
        }
        validate(newValue)?.let {
            _result.value = Event(ApplyResult.Error(it))
            return
        }

        _busy.value = true
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    val engine = ConnectionManager.codingEngine()
                        ?: return@withContext ApplyResult.NeedsConnection
                    // Snapshot the block before modifying it so the exact original bytes can
                    // be restored from the Backups screen. A failed snapshot aborts the write.
                    c.ecuMap?.let { map ->
                        val before = engine.readBlock(m, map.dataIdentifier)
                        if (before.isNotEmpty()) {
                            repo.addBackupIfChanged(
                                module = m,
                                dataIdentifier = map.dataIdentifier,
                                blockHex = Hex.encodeCompact(before),
                                label = "Before editing “${c.name}”",
                                source = ConnectionManager.backupSource(),
                                connectionLabel = ConnectionManager.current.label
                            )
                        }
                    }
                    val rawByte = engine.applyCoding(m, c, newValue)
                    repo.setValue(c.id, newValue)
                    ApplyResult.Success(
                        c.displayValue(newValue),
                        "0x%02X".format(rawByte.toInt() and 0xFF)
                    )
                } catch (e: Exception) {
                    ApplyResult.Error(e.message ?: "Coding failed")
                }
            }
            if (outcome is ApplyResult.Success) {
                _currentValue.value = newValue
                _ui.value = EditUiModel(c, m, newValue)
            }
            _busy.value = false
            _result.value = Event(outcome)
        }
    }
}
