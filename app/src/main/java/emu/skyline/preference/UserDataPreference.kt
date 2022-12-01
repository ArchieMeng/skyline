/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright © 2022 Skyline Team and Contributors (https://github.com/skyline-emu/)
 */

package emu.skyline.preference

import android.app.Activity
import android.content.Context
import android.util.AttributeSet
import androidx.lifecycle.LifecycleOwner
import androidx.preference.Preference
import androidx.preference.R
import emu.skyline.utils.UserDataManager

/**
 * This preference is used to check the status of user data by querying [UserDataManager]
 */
class UserDataPreference @JvmOverloads constructor(context : Context, attrs : AttributeSet? = null, defStyleAttr : Int = R.attr.preferenceStyle) : Preference(context, attrs, defStyleAttr) {
    init {
        updateSummary()

        UserDataManager.addOnStateChangedListener(context as LifecycleOwner) {
            (context as? Activity)?.runOnUiThread {
                updateSummary()
            }
        }
    }

    /**
     * Forces a new sync of user data
     */
    override fun onClick() = UserDataManager.requestSync(true)

    private fun updateSummary() {
        summary = "Current state: ${UserDataManager.currentState}"
    }
}
