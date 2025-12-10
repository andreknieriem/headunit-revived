package com.andrerinas.headunitrevived.utils

import android.content.IntentFilter
import com.andrerinas.headunitrevived.contract.DisconnectIntent
import com.andrerinas.headunitrevived.contract.KeyIntent

/**
 * @author algavris
 * @date 22/12/2017
 */
object IntentFilters {
    val disconnect = IntentFilter(DisconnectIntent.action)
    val keyEvent = IntentFilter(KeyIntent.action)
}