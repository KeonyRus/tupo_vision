package com.keonyrus.tupovision

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A one-line picture of the network as this app sees it, written to the log when a request cannot
 * leave the phone. The app can be cut off while the browser is not: a VPN or DNS filter that only
 * covers some apps, or a saver mode that blocks apps that are not on screen.
 */
object NetDiag {
  private val HOSTS = listOf("auth.openai.com", "api.openai.com", "www.google.com")

  suspend fun report(context: Context): String =
      withContext(Dispatchers.IO) {
        val parts = ArrayList<String>()
        for (host in HOSTS) {
          val resolved = runCatching { InetAddress.getByName(host) }.isSuccess
          parts.add("$host " + if (resolved) "находится" else "НЕ находится")
        }
        runCatching {
          val connectivity = context.getSystemService(ConnectivityManager::class.java)
          val network = connectivity.activeNetwork
          val capabilities = network?.let { connectivity.getNetworkCapabilities(it) }
          val link = network?.let { connectivity.getLinkProperties(it) }
          val kind =
              when {
                capabilities == null -> "для приложения закрыта"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильная"
                else -> "другая"
              }
          parts.add("сеть: $kind")
          if (link != null && link.isPrivateDnsActive) {
            parts.add("Private DNS: " + (link.privateDnsServerName ?: "авто"))
          }
          val saver =
              when (connectivity.restrictBackgroundStatus) {
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "включена"
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "включена, но не для нас"
                else -> "выключена"
              }
          parts.add("экономия трафика: $saver")
          val power = context.getSystemService(PowerManager::class.java)
          parts.add("энергосбережение: " + if (power.isPowerSaveMode) "включено" else "выключено")
        }
        parts.joinToString("; ")
      }
}
