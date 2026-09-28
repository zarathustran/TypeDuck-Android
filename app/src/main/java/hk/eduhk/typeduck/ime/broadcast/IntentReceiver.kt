/*
 * Copyright (C) 2015-present, osfans
 * waxaca@163.com https://github.com/osfans
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package hk.eduhk.typeduck.ime.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import hk.eduhk.typeduck.core.Rime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** 接收 Intent 廣播事件  */
class IntentReceiver : BroadcastReceiver(), CoroutineScope by MainScope() {
    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.action ?: return
        Timber.d("Received Command = %s", command)
        when (command) {
            COMMAND_DEPLOY -> launch {
                withContext(Dispatchers.Default) {
                    Rime.deployRime()
                }
            }
            COMMAND_SYNC -> async {
                Rime.syncRimeUserData()
                Rime.deployRime()
            }
            else -> return
        }
    }

    fun registerReceiver(context: Context) {
        val filter = IntentFilter().apply {
            addAction(COMMAND_DEPLOY)
            addAction(COMMAND_SYNC)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(this, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(this, filter)
        }
    }

    fun unregisterReceiver(context: Context) {
        context.unregisterReceiver(this)
    }

    companion object {
        private const val COMMAND_DEPLOY = "hk.eduhk.typeduck.deploy"
        private const val COMMAND_SYNC = "hk.eduhk.typeduck.sync"
    }
}
