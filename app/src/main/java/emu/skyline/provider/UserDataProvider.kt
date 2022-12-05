/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright © 2022 Skyline Team and Contributors (https://github.com/skyline-emu/)
 */

package emu.skyline.provider

import androidx.core.content.FileProvider
import emu.skyline.R

class UserDataProvider : FileProvider(R.xml.user_data_paths) {

}