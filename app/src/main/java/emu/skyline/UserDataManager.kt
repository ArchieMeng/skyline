/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright © 2022 Skyline Team and Contributors (https://github.com/skyline-emu/)
 */

@file:OptIn(DelicateCoroutinesApi::class)

package emu.skyline

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import emu.skyline.utils.fileDelegate
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Name of the action used to discover other Skyline installs for syncing user data
 * @note This *must* match the action in the manifest
 */
private const val ActionGetUserData = "skyline.intent.action.GET_USER_DATA"

/**
 * The maximum version of the user data that [UserDataManager] can handle
 */
private const val MaxUserDataVersion = 1

/**
 * The class responsible for syncing user data between Skyline installations
 *
 * The following data is synced:
 * - Game saves
 */
object UserDataManager {
    private val Tag = UserDataManager::class.java.simpleName

    private const val UserDataMetaDir = ".user_data"
    private const val TimestampFileName = "last"
    private const val UserDataVersionFileName = "version"

    private val appContext : Context get() = SkylineApplication.instance.applicationContext
    private val userDataMetaDir by lazy { File(appContext.getPublicFilesDir(), UserDataMetaDir) }
    private val versionFile by lazy { File(userDataMetaDir, UserDataVersionFileName) }
    private val timestampFile by lazy { File(userDataMetaDir, TimestampFileName) }

    enum class State {
        Ready,                  // User data is ready for use
        Uninitialized,          // User data hasn't been initialised yet
        WaitingForResponse,     // Waiting for response from other Skyline installations
        Syncing,                // Syncing user data with other Skyline installations in progress
    }

    @Volatile
    var currentState = State.Uninitialized
        private set(state) {
            field = state
            GlobalScope.launch(Dispatchers.Default) {
                callbacks.forEach { it.onStateChanged(state) }
            }
        }

    fun interface Callback {
        fun onStateChanged(state : State)
    }

    private val callbacks : MutableSet<Callback> = ConcurrentHashMap.newKeySet()

    private var version by fileDelegate<Int>(versionFile)

    private var timestamp by fileDelegate<Long>(timestampFile)

    /**
     * Initialises the metadata folder to default values
     */
    init {
        if (userDataMetaDir.mkdirs())
            Log.i(Tag, "Initialising user data metadata folder $userDataMetaDir")

        if (!timestampFile.exists())
            timestamp = 0

        if (!versionFile.exists())
            version = MaxUserDataVersion
    }

    /**
     * Notifies the user data manager that a game has been launched
     */
    fun notifyGameLaunched() {
        val now = System.currentTimeMillis()
        Log.d(Tag, "Game launched at $now")
        GlobalScope.launch(Dispatchers.IO) {
            timestamp = now
        }
    }

    /**
     * Registers the provided callback for user data state changes
     * @note The callback is lifecycle aware and will be removed when the provided lifecycle is destroyed
     * @param lifecycleOwner The lifecycle owner that the callback lifecycle is bound to
     */
    fun addOnStateChangedListener(lifecycleOwner : LifecycleOwner, callback : Callback) {
        callbacks.add(callback)

        // Remove the callback if the lifecycle is destroyed
        lifecycleOwner.lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source : LifecycleOwner, event : Lifecycle.Event) {
                if (event == Lifecycle.Event.ON_DESTROY) {
                    callbacks.remove(callback)
                    source.lifecycle.removeObserver(this)
                }
            }
        })
    }

    fun removeOnStateChangedListener(callback : Callback) {
        callbacks.remove(callback)
    }

    /**
     * Requests synchronization of user data with other Skyline installations
     * @note The request is performed asynchronously and this method will return immediately
     * @param forceResync If `true`, forces a new sync even if one was already performed. If `false` and a sync was already performed, the method will return immediately
     */
    fun requestSync(forceResync : Boolean = false) {
        synchronized(this) {
            // Skip if we've already synced once or if a re-sync was requested while already syncing
            if (currentState != State.Uninitialized && !(forceResync && currentState == State.Ready)) {
                Log.i(Tag, "Requested a sync but one" + if (forceResync) " is already in progress" else " has already been completed")
                return
            }

            // Ensure that state is updated right away so that subsequent calls don't trigger two syncs
            currentState = State.WaitingForResponse

            GlobalScope.launch(Dispatchers.IO) {
                Log.i(Tag, "Started synchronizing user data")
                run {
                    val fd = querySkylineInstalls()
                    delay(3000)

                    // Nothing to sync, either we're the only Skyline installation or we're are up to date
                    if (fd == null) {
                        currentState = State.Ready
                        return@run
                    }

                    // TODO: Sync user data
                    currentState = State.Syncing
                    //syncPublicFilesDir(fd)
                    fd.close()

                    currentState = State.Ready
                }

                Log.i(Tag, "Finished synchronizing user data")
            }
        }
    }

    /**
     * Queries other Skyline installations for the latest user data
     * @return The file descriptor of the most recent user data, or `null` if no user data was found
     */
    private suspend fun querySkylineInstalls() : ParcelFileDescriptor? {
        val intent = Intent(ActionGetUserData)
        val packageManager = appContext.packageManager

        val providers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentContentProviders(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentContentProviders(intent, 0)
        }

        Log.d(Tag, "Found ${providers.size - 1} receivers: " + providers.joinToString { it.activityInfo.packageName })

        val currentVersion = version
        var finalVersion = currentVersion
        var maxTimestamp = timestamp
        var selectedPackageName : String? = null

        // Find where the most recent user data is
        for (providerInfo in providers) {
            // Skip our own package
            if (providerInfo.activityInfo.packageName == appContext.packageName)
                continue

            // Create an explicit copy of the intent
            val explicitIntent = Intent(intent).apply {
                component = ComponentName(providerInfo.activityInfo.packageName, providerInfo.activityInfo.name)
            }

            // Query this receiver and suspend execution to wait for the result
            val queryResult = suspendCoroutine { continuation ->
                val queryBroadcastReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context : Context, intent : Intent) {
                        val extras = getResultExtras(true)
                        continuation.resume(extras)
                    }
                }
            }
            Log.d(Tag, "Received query result from ${providerInfo.activityInfo.packageName}")
            Log.v(Tag, "Query result: $queryResult")

            // Skip if the other installation has a newer version
            val queriedVersion = queryResult.getInt(ExtraUserDataVersion, 0)
            if (queriedVersion > currentVersion)
                continue

            // Save the file descriptor if the other installation has a newer timestamp
            val queriedTimestamp = queryResult.getLong(ExtraUserDataTimestamp, 0)
            if (queriedTimestamp > maxTimestamp) {
                maxTimestamp = queriedTimestamp
                finalVersion = queriedVersion
                selectedPackageName = providerInfo.activityInfo.packageName
            }
        }

        // Update our version to match the one of the selected installation
        if (finalVersion < currentVersion)
            version = finalVersion

        Log.d(Tag, selectedPackageName?.let {
            "$it has the newest user data"
        } ?: "No other Skyline installation has newer user data")

        return null
    }

    /**
     * Synchronizes user data with the data at the provided file descriptor
     */
    private fun syncPublicFilesDir(parcelFileDescriptor : ParcelFileDescriptor) {
        val out = File(appContext.getPublicFilesDir(), "sync")
    }
}
