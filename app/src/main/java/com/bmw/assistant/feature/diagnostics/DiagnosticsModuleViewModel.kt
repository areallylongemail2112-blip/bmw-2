package com.bmw.assistant.feature.diagnostics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.bmw.assistant.BmwAssistantApp
import com.bmw.assistant.core.ecu.ConnectionManager
import com.bmw.assistant.data.model.LiveParameter
import com.bmw.assistant.data.model.Module
import com.bmw.assistant.ui.common.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One fault code as shown in the list. */
data class FaultRowUi(
    val saeCode: String,
    val hexCode: String,
    val description: String?,
    val status: String
)

/** One live measurement row. */
data class LiveRowUi(
    val id: String,
    val name: String,
    val description: String,
    val value: String
)

class DiagnosticsModuleViewModel(app: Application) : AndroidViewModel(app) {

    private val codingRepo = (app as BmwAssistantApp).codingRepository
    private val diagRepo = (app as BmwAssistantApp).diagnosticsRepository

    private val _module = MutableLiveData<Module?>()
    val module: LiveData<Module?> = _module

    private val _faults = MutableLiveData<List<FaultRowUi>>(emptyList())
    val faults: LiveData<List<FaultRowUi>> = _faults

    private val _scanned = MutableLiveData(false)
    val scanned: LiveData<Boolean> = _scanned

    private val _liveRows = MutableLiveData<List<LiveRowUi>>(emptyList())
    val liveRows: LiveData<List<LiveRowUi>> = _liveRows

    private val _busy = MutableLiveData(false)
    val busy: LiveData<Boolean> = _busy

    private val _autoRefreshing = MutableLiveData(false)
    val autoRefreshing: LiveData<Boolean> = _autoRefreshing

    private val _message = MutableLiveData<Event<String>>()
    val message: LiveData<Event<String>> = _message

    private var liveParams: List<LiveParameter> = emptyList()
    val hasLiveData: Boolean get() = liveParams.isNotEmpty()

    private var autoJob: Job? = null

    fun load(moduleId: String) {
        viewModelScope.launch {
            _module.value = codingRepo.getModule(moduleId)
            liveParams = diagRepo.liveParametersFor(moduleId)
            _liveRows.value = liveParams.map { LiveRowUi(it.id, it.name, it.description, "—") }
        }
    }

    // --- fault codes ---

    fun scanFaults() {
        val m = _module.value ?: return
        if (!ensureDiagnosticsReady()) return
        _busy.value = true
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    val engine = ConnectionManager.diagnosticsEngine()
                        ?: return@withContext Outcome.NotCapable
                    val rows = engine.readFaults(m).map { dtc ->
                        FaultRowUi(dtc.saeCode(), dtc.hexCode(), diagRepo.describe(dtc), dtc.statusLabel())
                    }
                    Outcome.Faults(rows)
                } catch (e: Exception) {
                    Outcome.Error(e.message ?: "Reading fault codes failed")
                }
            }
            applyOutcome(outcome, clearedMessage = false)
            _busy.value = false
        }
    }

    fun clearFaults() {
        val m = _module.value ?: return
        if (!ensureDiagnosticsReady()) return
        _busy.value = true
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    val engine = ConnectionManager.diagnosticsEngine()
                        ?: return@withContext Outcome.NotCapable
                    engine.clearFaults(m)
                    Outcome.Faults(emptyList())
                } catch (e: Exception) {
                    Outcome.Error(e.message ?: "Clearing fault codes failed")
                }
            }
            applyOutcome(outcome, clearedMessage = true)
            _busy.value = false
        }
    }

    private fun applyOutcome(outcome: Outcome, clearedMessage: Boolean) {
        when (outcome) {
            is Outcome.Faults -> {
                _faults.value = outcome.rows
                _scanned.value = true
                emit(
                    when {
                        clearedMessage -> "Fault memory cleared."
                        outcome.rows.isEmpty() -> "No fault codes stored."
                        else -> "Found ${outcome.rows.size} fault code(s)."
                    }
                )
            }
            Outcome.NotCapable -> emit(NOT_CAPABLE)
            is Outcome.Error -> emit(outcome.message)
        }
    }

    // --- live data ---

    fun refreshLiveOnce() {
        if (liveParams.isEmpty()) return
        if (!ensureDiagnosticsReady()) return
        viewModelScope.launch { readAllLive() }
    }

    fun toggleAutoRefresh(on: Boolean) {
        if (on) {
            if (liveParams.isEmpty()) return
            if (!ensureDiagnosticsReady()) { _autoRefreshing.value = false; return }
            _autoRefreshing.value = true
            autoJob?.cancel()
            autoJob = viewModelScope.launch {
                while (isActive) {
                    readAllLive()
                    delay(POLL_INTERVAL_MS)
                }
            }
        } else {
            _autoRefreshing.value = false
            autoJob?.cancel()
            autoJob = null
        }
    }

    private suspend fun readAllLive() {
        val m = _module.value ?: return
        val rows = withContext(Dispatchers.IO) {
            val engine = ConnectionManager.diagnosticsEngine() ?: return@withContext null
            // Open the extended session once for the whole batch, then read each value without
            // renegotiating it (best-effort: if the open fails, the reads below surface it).
            runCatching { engine.openSession(m) }
            liveParams.map { p ->
                val text = try {
                    engine.readLive(m, p, openSession = false)?.let { p.format(it) } ?: "n/a"
                } catch (_: Exception) {
                    "error"
                }
                LiveRowUi(p.id, p.name, p.description, text)
            }
        }
        // Typed explicitly so the value published is provably non-null: LiveData is a Java
        // class, so a null slipping through here would only fail at the observer.
        val resolved: List<LiveRowUi> = rows ?: run {
            emit(NOT_CAPABLE)
            toggleAutoRefresh(false)
            return
        }
        _liveRows.postValue(resolved)
    }

    /** True if a diagnostics-capable connection is live; otherwise emits a message and returns false. */
    private fun ensureDiagnosticsReady(): Boolean {
        val conn = ConnectionManager.current
        return when {
            !conn.isConnected -> {
                emit("Not connected. Open the Connection screen and connect (or use demo mode) first.")
                false
            }
            !conn.supportsDiagnostics -> {
                emit(NOT_CAPABLE)
                false
            }
            else -> true
        }
    }

    private fun emit(msg: String) { _message.value = Event(msg) }

    override fun onCleared() {
        autoJob?.cancel()
    }

    private sealed class Outcome {
        data class Faults(val rows: List<FaultRowUi>) : Outcome()
        data class Error(val message: String) : Outcome()
        object NotCapable : Outcome()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1200L
        private const val NOT_CAPABLE =
            "This connection can't read diagnostics. Use ENET or demo mode."
    }
}
