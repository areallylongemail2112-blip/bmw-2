package com.bmw.assistant.feature.service

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.bmw.assistant.BmwAssistantApp
import com.bmw.assistant.core.ecu.ConnectionManager
import com.bmw.assistant.data.model.ServiceFunction
import com.bmw.assistant.ui.common.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class ServiceAction {
    data class Ran(val name: String) : ServiceAction()
    data class Failed(val message: String) : ServiceAction()
    object NeedsConnection : ServiceAction()
}

class ServicesViewModel(app: Application) : AndroidViewModel(app) {

    private val codingRepo = (app as BmwAssistantApp).codingRepository
    private val serviceRepo = (app as BmwAssistantApp).serviceRepository

    private val _services = MutableLiveData<List<ServiceFunction>>(emptyList())
    val services: LiveData<List<ServiceFunction>> = _services

    private val _busy = MutableLiveData(false)
    val busy: LiveData<Boolean> = _busy

    private val _event = MutableLiveData<Event<ServiceAction>>()
    val event: LiveData<Event<ServiceAction>> = _event

    fun load(moduleId: String?) {
        viewModelScope.launch {
            val all = serviceRepo.all()
            _services.value = if (moduleId.isNullOrBlank()) all
            else all.filter { it.moduleId == moduleId }
        }
    }

    fun run(service: ServiceFunction) {
        if (!ConnectionManager.current.isConnected) {
            _event.value = Event(ServiceAction.NeedsConnection)
            return
        }
        _busy.value = true
        viewModelScope.launch {
            val action = withContext(Dispatchers.IO) {
                try {
                    val engine = ConnectionManager.serviceEngine()
                        ?: return@withContext ServiceAction.NeedsConnection
                    val module = codingRepo.getModule(service.moduleId)
                        ?: return@withContext ServiceAction.Failed("Module ${service.moduleId} not found.")
                    engine.run(module, service)
                    ServiceAction.Ran(service.name)
                } catch (e: Exception) {
                    ServiceAction.Failed(e.message ?: "Service failed")
                }
            }
            _busy.value = false
            _event.value = Event(action)
        }
    }
}
