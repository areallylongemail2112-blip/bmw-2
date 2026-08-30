package com.bmw.assistant.data

import android.content.Context
import com.bmw.assistant.data.model.ServiceFunction
import com.bmw.assistant.data.model.ServicesData
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ServiceRepository private constructor(private val context: Context) {

    @Volatile private var data: ServicesData? = null

    suspend fun ensureLoaded(): ServicesData = withContext(Dispatchers.IO) {
        data ?: synchronized(this@ServiceRepository) {
            data ?: load().also { data = it }
        }
    }

    suspend fun all(): List<ServiceFunction> = ensureLoaded().services

    suspend fun forModule(moduleId: String): List<ServiceFunction> =
        ensureLoaded().services.filter { it.moduleId == moduleId }

    private fun load(): ServicesData {
        val json = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        return Gson().fromJson(json, ServicesData::class.java)
    }

    companion object {
        private const val ASSET = "services_f10.json"

        @Volatile private var instance: ServiceRepository? = null

        fun get(context: Context): ServiceRepository =
            instance ?: synchronized(this) {
                instance ?: ServiceRepository(context.applicationContext).also { instance = it }
            }
    }
}
