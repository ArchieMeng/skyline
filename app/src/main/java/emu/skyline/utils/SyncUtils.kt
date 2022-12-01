/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright © 2022 Skyline Team and Contributors (https://github.com/skyline-emu/)
 */

package emu.skyline.utils

import java.io.File

/**
 * Provides utilities for files/directories synchronization
 */
interface SyncUtils {
    companion object {
        /**
         * Syncs the [source] file with the [destination] file
         * @note The [destination] file is overwritten if the [source] file is newer
         * @note The [destination] file is deleted if the [source] file is deleted
         */
        fun syncFile(source : File, destination : File) {
            if (source.isDirectory) {
                destination.mkdirs()
                source.listFiles()?.forEach { syncFile(it, File(destination, it.name)) }
            } else {
                source.copyTo(destination, true)
            }
        }

        /**
         * Syncs the [source] directory with the [destination] directory
         *
         * Files not in the [source] directory will be deleted from the [destination] directory
         *
         * @note
         */
        fun syncDirectory(source : File, destination : File) {
            if (!source.isDirectory || !destination.isDirectory)
                return



        }
    }
}