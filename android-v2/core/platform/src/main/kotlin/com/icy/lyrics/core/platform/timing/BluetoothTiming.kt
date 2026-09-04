package com.icy.lyrics.core.platform.timing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import androidx.core.content.ContextCompat
import com.icy.lyrics.core.platform.settings.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update

/** Observes the selected system media route, not merely every paired device. */
@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothRouteMonitor(context: Context) {
  private val appContext = context.applicationContext
  private val router = MediaRouter2.getInstance(appContext)
  private val audioManager = appContext.getSystemService(AudioManager::class.java)
  private val executor = ContextCompat.getMainExecutor(appContext)
  private val permissionRevision = MutableStateFlow(0L)

  val activeRoute: Flow<BluetoothRoute?> = permissionRevision.flatMapLatest { observeRoute() }
    .distinctUntilChanged()

  /** Call after a runtime BLUETOOTH_CONNECT permission result. */
  fun refreshPermission() {
    permissionRevision.update { it + 1L }
  }

  private fun observeRoute(): Flow<BluetoothRoute?> = callbackFlow {
    if (!hasBluetoothPermission()) {
      trySend(null)
      close()
      return@callbackFlow
    }

    fun emitCurrent() {
      val selected = try {
        router.systemController.selectedRoutes.firstNotNullOfOrNull(::toBluetoothRoute)
      } catch (_: SecurityException) {
        null
      }
      trySend(selected)
    }

    val callback = object : MediaRouter2.ControllerCallback() {
      override fun onControllerUpdated(controller: MediaRouter2.RoutingController) {
        if (controller.id == router.systemController.id) emitCurrent()
      }
    }
    val registered = try {
      router.registerControllerCallback(executor, callback)
      emitCurrent()
      true
    } catch (_: SecurityException) {
      trySend(null)
      false
    }
    awaitClose {
      if (registered) runCatching { router.unregisterControllerCallback(callback) }
    }
  }

  private fun toBluetoothRoute(route: MediaRoute2Info): BluetoothRoute? {
    val displayName = route.name?.toString()?.takeIf(String::isNotBlank) ?: "Bluetooth device"
    val device = findBluetoothDevice(displayName) ?: return null
    return BluetoothRoute(
      deviceKey = BluetoothDeviceKeyFactory.create(device.address, device.type, displayName),
      displayName = displayName,
      routeType = device.type,
      identityKind = if (device.address.isNullOrBlank()) {
        BluetoothIdentityKind.FALLBACK_TYPE_AND_NAME
      } else {
        BluetoothIdentityKind.ADDRESS_HASH
      },
    )
  }

  private fun findBluetoothDevice(displayName: String): BluetoothAudioDevice? {
    if (!hasBluetoothPermission()) return null
    return try {
      val candidates = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .asSequence()
        .filter { it.type in BLUETOOTH_AUDIO_DEVICE_TYPES }
        .toList()
      val selected = candidates.firstOrNull {
        it.productName?.toString()?.equals(displayName, ignoreCase = true) == true
      } ?: candidates.singleOrNull()
      selected?.let { BluetoothAudioDevice(it.type, it.address.takeIf(String::isNotBlank)) }
    } catch (_: SecurityException) {
      null
    }
  }

  private fun hasBluetoothPermission(): Boolean = ContextCompat.checkSelfPermission(
    appContext,
    Manifest.permission.BLUETOOTH_CONNECT,
  ) == PackageManager.PERMISSION_GRANTED

  companion object {
    private val BLUETOOTH_AUDIO_DEVICE_TYPES = setOf(
      AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
      AudioDeviceInfo.TYPE_BLE_HEADSET,
      AudioDeviceInfo.TYPE_BLE_SPEAKER,
      AudioDeviceInfo.TYPE_BLE_BROADCAST,
      AudioDeviceInfo.TYPE_HEARING_AID,
    )
  }

  private data class BluetoothAudioDevice(
    val type: Int,
    val address: String?,
  )
}


@Suppress("FunctionName")
fun BluetoothTimingResolver(
  settings: SettingsRepository,
  timings: DeviceTimingRepository,
  routeMonitor: BluetoothRouteMonitor,
): BluetoothTimingResolver = BluetoothTimingResolver(settings, timings, routeMonitor.activeRoute)
