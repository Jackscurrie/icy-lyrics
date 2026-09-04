package com.icy.lyrics.core.platform.timing

import com.icy.lyrics.core.platform.database.DeviceTimingDao
import com.icy.lyrics.core.platform.database.DeviceTimingEntity
import com.icy.lyrics.core.platform.runtime.epochMillis
import com.icy.lyrics.core.platform.settings.SettingsDefaults
import com.icy.lyrics.core.platform.settings.SettingsRepository
import com.icy.lyrics.core.platform.storage.TrackKeys
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

data class BluetoothRoute(
  val deviceKey: String,
  val displayName: String,
  val routeType: Int,
  val identityKind: BluetoothIdentityKind,
)

enum class BluetoothIdentityKind {
  ADDRESS_HASH,
  FALLBACK_TYPE_AND_NAME,
  PLATFORM_ROUTE_UID,
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
  private val clock: () -> Long = ::epochMillis,
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

object BluetoothDeviceKeyFactory {
  /** Opaque OS route identifiers are hashed before persisting device profiles. */
  fun fromPlatformRouteUid(uid: String): String {
    require(uid.isNotBlank()) { "Route identifier cannot be blank" }
    return "bt:route:${TrackKeys.privacyHash(uid)}"
  }

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
  private val route: Flow<BluetoothRoute?>,
) {

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
