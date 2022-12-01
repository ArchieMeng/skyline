/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright © 2022 Skyline Team and Contributors (https://github.com/skyline-emu/)
 */

@file:OptIn(DelicateCoroutinesApi::class)

package emu.skyline.utils

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import emu.skyline.SkylineApplication
import emu.skyline.getPublicFilesDir
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Name of the action used to sync user data with other Skyline installations
 * @note This *must* match the action in the manifest
 */
private const val ActionQueryUserData = "skyline.intent.action.QUERY_USER_DATA"

/**
 * Extras returned by a user data query performed by [UserDataManager]
 */
private const val ExtraUserDataVersion = "skyline.intent.extra.USER_DATA_VERSION"
private const val ExtraUserDataTimestamp = "skyline.intent.extra.USER_DATA_TIMESTAMP"
private const val ExtraUserDataFdBinder = "skyline.intent.extra.USER_DATA_FD_BINDER"

private const val TransactionGetPublicFilesDir = Binder.FIRST_CALL_TRANSACTION
private const val TransactionGetInternalFilesDir = Binder.FIRST_CALL_TRANSACTION + 1

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

    private const val UserDataMetaDir = ".skyline"
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

    private var version : Int
        get() = versionFile.inputStream().use {
            String(it.readBytes()).toInt()
        }
        set(version) = versionFile.outputStream().use {
            Log.v(Tag, "Writing version $version to $versionFile")
            it.write(version.toString().toByteArray())
        }

    private var timestamp : Long
        get() = timestampFile.inputStream().use {
            String(it.readBytes()).toLong()
        }
        set(timestamp) = timestampFile.outputStream().use {
            Log.v(Tag, "Writing timestamp $timestamp to $timestampFile")
            it.write(timestamp.toString().toByteArray())
        }

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
        val intent = Intent(ActionQueryUserData)
        val packageManager = appContext.packageManager

        val receivers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryBroadcastReceivers(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryBroadcastReceivers(intent, 0)
        }

        Log.d(Tag, "Found ${receivers.size - 1} receivers: " + receivers.joinToString { it.activityInfo.packageName })

        val currentVersion = version
        var finalVersion = currentVersion
        var maxTimestamp = timestamp
        var selectedPackageName : String? = null
        var fd : ParcelFileDescriptor? = null

        // Find where the most recent user data is
        for (receiverInfo in receivers) {
            // Skip our own package
            if (receiverInfo.activityInfo.packageName == appContext.packageName)
                continue

            // Create an explicit copy of the intent
            val explicitIntent = Intent(intent).apply {
                component = ComponentName(receiverInfo.activityInfo.packageName, receiverInfo.activityInfo.name)
            }

            // Query this receiver and suspend execution to wait for the result
            val (queryResult : Bundle, queriedFd : ParcelFileDescriptor?) = suspendCoroutine { continuation ->
                val queryBroadcastReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context : Context, intent : Intent) {
                        val extras = getResultExtras(true)

                        // Perform IPC to retrieve the file descriptor
                        val parcelFileDescriptor = extras.getBinder(ExtraUserDataFdBinder)?.let { binder ->
                            val reply = Parcel.obtain()

                            if (!binder.transact(TransactionGetPublicFilesDir, Parcel.obtain(), reply, 0))
                                return@let null

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                reply.readParcelable(ParcelFileDescriptor::class.java.classLoader, ParcelFileDescriptor::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                reply.readParcelable(ParcelFileDescriptor::class.java.classLoader)
                            }
                        }


                        continuation.resume(Pair(extras, parcelFileDescriptor))
                    }
                }

                // TODO: find a way to avoid executing the result receiver on the main thread
                appContext.sendOrderedBroadcast(explicitIntent, null, queryBroadcastReceiver, null, 0, null, null)
            }
            Log.d(Tag, "Received query result from ${receiverInfo.activityInfo.packageName}")
            Log.v(Tag, "Query result: $queryResult")
            Log.v(Tag, "fd: $queriedFd, valid: ${queriedFd?.fileDescriptor?.valid()}")

            // Skip if the other installation has a newer version
            val queriedVersion = queryResult.getInt(ExtraUserDataVersion, 0)
            if (queriedVersion > currentVersion)
                continue

            // Save the file descriptor if the other installation has a newer timestamp
            val queriedTimestamp = queryResult.getLong(ExtraUserDataTimestamp, 0)
            if (queriedTimestamp > maxTimestamp) {
                maxTimestamp = queriedTimestamp
                finalVersion = queriedVersion
                fd = queriedFd
                selectedPackageName = receiverInfo.activityInfo.packageName
            } else {
                queriedFd?.close()
            }
        }

        // Update our version to match the one of the selected installation
        if (finalVersion < currentVersion)
            version = finalVersion

        Log.d(Tag, selectedPackageName?.let {
            "$it has the newest user data"
        } ?: "No other Skyline installation has newer user data")

        return fd
    }

    /**
     * Synchronizes user data with the data at the provided file descriptor
     */
    private fun syncPublicFilesDir(parcelFileDescriptor : ParcelFileDescriptor) {
        val out = File(appContext.getPublicFilesDir(), "sync")
    }

    /**
     * Returns user data information packed into a [Bundle]
     * @return A [Bundle] containing the user data information, and a Binder to retrieve a file descriptor to the user data
     */
    fun getQueryResultBundle(context : Context) : Bundle {
        // Create a Binder that will be used to transfer the file descriptor
        val binder = object : Binder() {
            override fun onTransact(code : Int, data : Parcel, reply : Parcel?, flags : Int) : Boolean {
                if (reply == null)
                    return false

                val dir = when (code) {
                    TransactionGetPublicFilesDir -> {
                        context.getPublicFilesDir().toUri()
                    }

                    TransactionGetInternalFilesDir -> {
                        context.filesDir.toUri()
                    }

                    else -> return false
                }

                val parcelFileDescriptor = context.contentResolver.openFileDescriptor(dir, "r")!!
                reply.writeParcelable(parcelFileDescriptor, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
                return true
            }
        }

        return Bundle().apply {
            putInt(ExtraUserDataVersion, version)
            putLong(ExtraUserDataTimestamp, timestamp)
            putBinder(ExtraUserDataFdBinder, binder)
        }
    }
}

/**
 * Manifest-registered receiver that replies to user data queries
 */
class UserDataQueryBroadcastReceiver : BroadcastReceiver() {
    /**
     * Returns a FD of the `files` directory of the app, and metadata about the user data
     */
    override fun onReceive(context : Context, intent : Intent) {
        if (intent.action != ActionQueryUserData)
            return

        // TODO: look into process lifetime and Binder
        setResultExtras(UserDataManager.getQueryResultBundle(context))
    }
}
