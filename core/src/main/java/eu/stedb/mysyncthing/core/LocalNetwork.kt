package eu.stedb.mysyncthing.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Android 17 (API 37) asks before an app reaches devices on the local network: an app that targets it
 * gets no TCP or UDP to Wi-Fi or Ethernet neighbours (private and link-local addresses, broadcast,
 * multicast) without this runtime permission, Go's sockets included. Cellular and VPN traffic is not
 * local. Android 15 and 16 grant it with INTERNET and don't know the permission.
 */
const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

/** The first Android that asks for [ACCESS_LOCAL_NETWORK]. */
const val LOCAL_NETWORK_API = 37

/** Whether this Android asks for [ACCESS_LOCAL_NETWORK] at all. */
fun asksForLocalNetwork(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= LOCAL_NETWORK_API

/**
 * Syncthing may reach the local network. Required like All files access: without it the devices on the
 * same Wi-Fi would only meet over relays, so the core does not start (SyncthingService.runCore).
 */
fun Context.hasLocalNetwork(): Boolean =
    !asksForLocalNetwork() || checkSelfPermission(ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
