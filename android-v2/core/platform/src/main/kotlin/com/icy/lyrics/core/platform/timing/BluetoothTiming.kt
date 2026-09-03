package com.icy.lyrics.core.platform.timing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import androidx.core.content.ContextCompat
import com.icy.lyrics.core.platform.database.DeviceTimingDao
import com.icy.lyrics.core.platform.database.DeviceTimingEntity
import com.icy.lyrics.core.platform.settings.SettingsDefaults
import com.icy.lyrics.core.platform.settings.SettingsRepository
import com.icy.lyrics.core.platform.storage.TrackKeys
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ExperimentalCoroutinesApi

data class BluetoothRoute(
  val deviceKey: String,
  val displayName: String,
  val routeType: Int,
  val identityKind: BluetoothIdentityKind,
)

enum class BluetoothIdentityKind {
  ADDRESS_HASH,
  FALLBACK_TYPE_AND_NAME,
}

data class DeviceTiming(
  val deviceKey: String,
  val displayName: String,
  val routeType: Int,
  val identityKind: BluetoothIdentityKind,
  val offsetMs: Int,
  val updatedAtEpochMs: Long,
)

data class EffectiveTimingOffset(
  val offsetMs: Int,
  val route: BluetoothRoute?,
  val usingRememberedDeviceOffset: Boolean,
)

class DeviceTimingRepository(
  private val dao: DeviceTimingDao,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  suspend fun get(deviceKey: String): DeviceTiming? = dao.get(deviceKey)?.toModel()

  fun observe(deviceKey: String): Flow<DeviceTiming?> = dao.observe(deviceKey).map { it?.toModel() }

  fun observeAll(): Flow<List<DeviceTiming>> = dao.observeAll().map { rows ->
    rows.map { it.toModel() }
  }

  suspend fun save(route: BluetoothRoute, offsetMs: Int) {
    dao.upsert(
      DeviceTimingEntity(
        deviceKey = route.deviceKey,
        displayName = route.displayName.take(120),
        routeType = route.routeType,
        identityKind = route.identityKind.name,
        offsetMs = offsetMs.coerceIn(
          SettingsDefaults.MIN_TIMING_OFFSET_MS,
          SettingsDefaults.MAX_TIMING_OFFSET_MS,
        ),
        updatedAtEpochMs = clock(),
      ),
    )
  }

  suspend fun delete(deviceKey: String): Boolean = dao.delete(deviceKey) > 0

  private fun DeviceTimingEntity.toModel() = DeviceTiming(
    deviceKey,
    displayName,
    routeType,
    runCatching { BluetoothIdentityKind.valueOf(identityKind) }
      .getOrDefault(BluetoothIdentityKind.FALLBACK_TYPE_AND_NAME),
    offsetMs,
    updatedAtEpochMs,
  )
}

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

object BluetoothDeviceKeyFactory {
  fun create(address: String?, routeType: Int, displayName: String): String {
    val stableMaterial = address?.trim()?.lowercase()?.takeIf(String::isNotBlank)
    return if (stableMaterial != null) {
      "bt:address:${TrackKeys.privacyHash(stableMaterial)}"
    } else {
      val normalizedName = displayName.trim().lowercase().replace(Regex("""\s+"""), " ")
      "bt:fallback:${TrackKeys.privacyHash("$routeType|$normalizedName")}"
    }
  }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothTimingResolver(
  private val settings: SettingsRepository,
  private val timings: DeviceTimingRepository,
  routeMonitor: BluetoothRouteMonitor,
) {
  private val route = routeMonitor.activeRoute

  val effectiveOffset: Flow<EffectiveTimingOffset> = combine(
    settings.settings,
    route,
  ) { appSettings, activeRoute -> appSettings to activeRoute }
    .flatMapLatest { (appSettings, activeRoute) ->
      if (!appSettings.rememberBluetoothTiming || activeRoute == null) {
        flowOf(
          EffectiveTimingOffset(
            offsetMs = appSettings.globalTimingOffsetMs,
            route = activeRoute,
            usingRememberedDeviceOffset = false,
          ),
        )
      } else {
        timings.observe(activeRoute.deviceKey).map { remembered ->
          EffectiveTimingOffset(
            offsetMs = remembered?.offsetMs ?: appSettings.globalTimingOffsetMs,
            route = activeRoute,
            usingRememberedDeviceOffset = remembered != null,
          )
        }
      }
    }
    .distinctUntilChanged()

  suspend fun rememberForCurrentRoute(offsetMs: Int): Boolean {
    val selected = route.first() ?: return false
    timings.save(selected, offsetMs)
    return true
  }
}
