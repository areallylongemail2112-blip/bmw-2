package com.bmw.assistant.feature.backups

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.bmw.assistant.BmwAssistantApp
import com.bmw.assistant.data.model.CodingBackup
import com.bmw.assistant.data.model.ValueSource
import com.bmw.assistant.core.ecu.ConnectionManager
import com.bmw.assistant.core.ecu.Hex
import com.bmw.assistant.ui.common.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class BackupAction {
    data class Restored(val label: String) : BackupAction()
    data class Created(val count: Int) : BackupAction()
    data class Failed(val message: String) : BackupAction()
    data class Exported(val json: String) : BackupAction()
    object NeedsConnection : BackupAction()
}

class BackupsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as BmwAssistantApp).codingRepository

    private val _backups = MutableLiveData<List<CodingBackup>>(emptyList())
    val backups: LiveData<List<CodingBackup>> = _backups

    private val _busy = MutableLiveData(false)
    val busy: LiveData<Boolean> = _busy

    private val _event = MutableLiveData<Event<BackupAction>>()
    val event: LiveData<Event<BackupAction>> = _event

    fun load() {
        viewModelScope.launch { _backups.value = repo.getBackups() }
    }

    fun exportJson() {
        viewModelScope.launch {
            val list = repo.getBackups()
            if (list.isEmpty()) {
                _event.value = Event(BackupAction.Failed("Nothing to export."))
                return@launch
            }
            val payload = org.json.JSONObject().apply {
                put("exportedAt", System.currentTimeMillis())
                put("backups", org.json.JSONArray().apply {
                    list.forEach { b ->
                        put(org.json.JSONObject().apply {
                            put("moduleId", b.moduleId)
                            put("moduleName", b.moduleName)
                            put("diagAddress", b.diagAddress)
                            put("dataIdentifier", b.dataIdentifier)
                            put("blockHex", b.blockHex)
                            put("label", b.label)
                            put("source", b.source.name)
                            put("connectionLabel", b.connectionLabel)
                            put("vin", b.vin)
                            put("iLevel", b.iLevel)
                            put("createdAt", b.createdAt)
                        })
                    }
                })
            }.toString(2)
            _event.value = Event(BackupAction.Exported(payload))
        }
    }

    fun delete(backup: CodingBackup) {
        viewModelScope.launch {
            repo.deleteBackup(backup.id)
            _backups.value = repo.getBackups()
        }
    }

    /** Writes a saved block back to its module, then refreshes that module's shown values. */
    fun restore(backup: CodingBackup) {
        if (!ConnectionManager.current.isConnected) {
            _event.value = Event(BackupAction.NeedsConnection)
            return
        }
        _busy.value = true
        viewModelScope.launch {
            val action = withContext(Dispatchers.IO) {
                try {
                    val module = repo.getModule(backup.moduleId)
                        ?: return@withContext BackupAction.Failed("Module ${backup.moduleId} not found.")
                    ConnectionManager.restoreBackup(module, backup)
                    // Re-read the module's codings so the list reflects the restored bytes.
                    repo.getCodingsForModule(module.id).forEach { coding ->
                        ConnectionManager.readValue(module, coding)?.let { v ->
                            repo.setValue(coding.id, v, ValueSource.FROM_CAR)
                        }
                    }
                    BackupAction.Restored(backup.label)
                } catch (e: Exception) {
                    BackupAction.Failed(e.message ?: "Restore failed")
                }
            }
            _busy.value = false
            _event.value = Event(action)
        }
    }

    /** Snapshots every distinct module coding block currently defined (manual backup). */
    fun createBackupForAll() {
        if (!ConnectionManager.current.isConnected) {
            _event.value = Event(BackupAction.NeedsConnection)
            return
        }
        _busy.value = true
        viewModelScope.launch {
            val action = withContext(Dispatchers.IO) {
                try {
                    val source = ConnectionManager.backupSource()
                    val label = ConnectionManager.current.label
                    val identity = ConnectionManager.current.identity
                    var count = 0
                    val seen = HashSet<Pair<String, Int>>()
                    for (module in repo.getModules()) {
                        for (coding in repo.getCodingsForModule(module.id)) {
                            val did = coding.ecuMap?.dataIdentifier ?: continue
                            if (!seen.add(module.id to did)) continue
                            val block = ConnectionManager.readBlock(module, did)
                            if (block.isEmpty()) continue
                            val added = repo.addBackupIfChanged(
                                module = module,
                                dataIdentifier = did,
                                blockHex = Hex.encodeCompact(block),
                                label = "Manual backup",
                                source = source,
                                connectionLabel = label,
                                vin = identity?.vin,
                                iLevel = identity?.iLevel
                            )
                            if (added) count++
                        }
                    }
                    BackupAction.Created(count)
                } catch (e: Exception) {
                    BackupAction.Failed(e.message ?: "Backup failed")
                }
            }
            _busy.value = false
            if (action is BackupAction.Created) _backups.value = repo.getBackups()
            _event.value = Event(action)
        }
    }
}
