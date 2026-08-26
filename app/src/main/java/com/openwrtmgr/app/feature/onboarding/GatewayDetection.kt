package com.openwrtmgr.app.feature.onboarding

import android.content.Context
import android.net.ConnectivityManager

/**
 * Section 7 — "known gateway detection": the router is almost always the current network's
 * default gateway, so prefill the address field with it instead of a hardcoded 192.168.1.1.
 * Falls back to null (caller keeps its own default) on any network state we can't read.
 */
fun detectGatewayAddress(context: Context): String? {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
    val network = connectivityManager.activeNetwork ?: return null
    val linkProperties = connectivityManager.getLinkProperties(network) ?: return null
    return linkProperties.routes
        .firstOrNull { it.isDefaultRoute }
        ?.gateway
        ?.hostAddress
}
