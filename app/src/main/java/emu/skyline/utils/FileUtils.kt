/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright © 2022 Skyline Team and Contributors (https://github.com/skyline-emu/)
 */

package emu.skyline.utils

import java.io.File
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

inline fun <reified T> fileDelegate(backingFile : File) = FileDelegate<T>(backingFile, T::class)

class FileDelegate<T>(private val backingFile : File, private val clazz : KClass<*>) : ReadWriteProperty<Any?, T> {
    override fun getValue(thisRef : Any?, property : KProperty<*>) : T = backingFile.inputStream().use {
        val value = String(it.readBytes())
        @Suppress("UNCHECKED_CAST")
        when (clazz) {
            Long::class -> value.toLong()
            Int::class -> value.toInt()
            Float::class -> value.toFloat()
            Boolean::class -> value.toBoolean()
            String::class -> value
            else -> error("Unsupported type $clazz")
        } as T
    }

    override fun setValue(thisRef : Any?, property : KProperty<*>, value : T) = backingFile.outputStream().use {
        it.write(value.toString().toByteArray())
    }
}
