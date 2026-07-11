package com.rekosk.remesh.data

import android.content.Context
import com.rekosk.remesh.ble.MeshCoreBleClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The one BLE link and the one repository, shared by everything in the process.
 *
 * They cannot hang off the ViewModel any more: the foreground service that shows
 * the connection notification has to read the same connection state the UI does,
 * and it outlives any Activity. The scope is deliberately never cancelled -- the
 * process dying is what ends it.
 */
object MeshContainer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var repository: MeshRepository? = null

    @Volatile
    private var preferences: AppPreferences? = null

    fun repository(context: Context): MeshRepository =
        repository ?: synchronized(this) {
            repository ?: MeshRepository(
                client = MeshCoreBleClient(context.applicationContext),
                scope = scope,
                nodeStorage = NodeStorage(context.applicationContext),
            ).also { repository = it }
        }

    fun preferences(context: Context): AppPreferences =
        preferences ?: synchronized(this) {
            preferences ?: AppPreferences(context.applicationContext).also { preferences = it }
        }
}
