package com.icy.lyrics.overlay

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.icy.lyrics.IcyLyricsApplication
import com.icy.lyrics.MainActivity
import com.icy.lyrics.PhoneSessionLease
import com.icy.lyrics.R
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A user-started foreground overlay; never starts at boot, from playback, or from network callbacks. */
class LyricsOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
  private val registry = LifecycleRegistry(this)
  private val savedStateController = SavedStateRegistryController.create(this)
  override val lifecycle: Lifecycle get() = registry
  override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
  override val viewModelStore = ViewModelStore()
  private val mainHandler = Handler(Looper.getMainLooper())
  private lateinit var preferences: OverlayPreferences
  private lateinit var phoneSessionLease: PhoneSessionLease
  private lateinit var overlayContext: Context
  private lateinit var windows: WindowManager
  private var root: View? = null
  private var layout: WindowManager.LayoutParams? = null
  private var expanded = false
  private var bubblePosition = OverlayPosition(0, 0)
  private var bubbleInitialized = false
  // Preserve the chosen height through collapse, screen locking and orientation changes.
  private var panelHeightDp: Float? = null
  private var started = false
  private var receiverRegistered = false
  private var appOps: AppOpsManager? = null
  private val permissionListener = AppOpsManager.OnOpChangedListener { operation, changedPackage ->
    if (operation == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW &&
      (changedPackage == null || changedPackage == packageName)) {
      mainHandler.post { if (!Settings.canDrawOverlays(this)) disableAndStop() }
    }
  }
  private val screenReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      when (intent?.action) {
        Intent.ACTION_SCREEN_OFF -> {
          // No frame clock or artwork animation is left running behind the lock screen.
          expanded = false
          removeWindow()
          registry.currentState = Lifecycle.State.CREATED
        }
        Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
          if (started && !getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            registry.currentState = Lifecycle.State.RESUMED
            if (root == null) showBubble()
          }
        }
      }
    }
  }

  override fun onCreate() {
    super.onCreate()
    phoneSessionLease = (application as IcyLyricsApplication).container.acquirePhoneSession()
    preferences = OverlayPreferences(this)
    savedStateController.performAttach()
    savedStateController.performRestore(null)
    registry.currentState = Lifecycle.State.CREATED
    val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    val windowContext = createDisplayContext(display)
      .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    overlayContext = ContextThemeWrapper(windowContext, R.style.Theme_IcyLyrics)
    windows = overlayContext.getSystemService(WindowManager::class.java)
    appOps = getSystemService(AppOpsManager::class.java).also {
      it.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, permissionListener)
    }
    ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
      addAction(Intent.ACTION_SCREEN_OFF)
      addAction(Intent.ACTION_SCREEN_ON)
      addAction(Intent.ACTION_USER_PRESENT)
    }, ContextCompat.RECEIVER_NOT_EXPORTED)
    receiverRegistered = true
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.RESUMED) {
        combine(
          phoneSessionLease.viewModel.state.map {
            it.nowPlaying?.artwork to (it.nowPlaying?.isPlaying == true)
          }.distinctUntilChanged(),
          preferences.options.map { it.spinArtwork }.distinctUntilChanged(),
        ) { playback, spin -> Triple(playback.first, playback.second, spin) }
          .collect { (artwork, isPlaying, spin) ->
            // Reuse the existing session artwork; the bubble never fetches artwork or lyrics.
            (root as? OverlayBubbleView)?.updateArtwork(artwork, isPlaying, spin)
          }
      }
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP || !Settings.canDrawOverlays(this)) {
      disableAndStop()
      return START_NOT_STICKY
    }
    try {
      if (!started) {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(),
          if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        started = true
      }
      preferences.setEnabled(true)
      if (!getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
        registry.currentState = Lifecycle.State.RESUMED
        if (root == null) showBubble()
      }
    } catch (error: RuntimeException) {
      // Includes permission revocation between canDrawOverlays and addView, or an FGS refusal.
      Log.w("LyricsOverlay", "Android could not start the floating window", error)
      disableAndStop()
    }
    // Process recreation waits for the next explicit, visible app visit. It must not surprise-start
    // an overlay from the background (Android 15+ requires an already visible overlay for that).
    return START_NOT_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    if (root == null) return
    if (expanded) showPanel() else showBubble()
  }

  private fun notification(): Notification {
    getSystemService(NotificationManager::class.java).createNotificationChannel(
      NotificationChannel(CHANNEL, "Lyrics overlay", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Controls for the floating lyrics window you enabled"
        setShowBadge(false)
      },
    )
    val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    val open = PendingIntent.getActivity(this, 130, Intent(this, MainActivity::class.java), pendingFlags)
    val stop = PendingIntent.getService(this, 131,
      Intent(this, LyricsOverlayService::class.java).setAction(ACTION_STOP), pendingFlags)
    return NotificationCompat.Builder(this, CHANNEL)
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      .setContentTitle("Icy Lyrics overlay is on")
      .setContentText("Tap the floating album cover for lyrics. Turn off here at any time.")
      .setContentIntent(open)
      .setOngoing(true)
      .setSilent(true)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Turn off", stop)
      .build()
  }

  private fun availableSize(): OverlaySize {
    val metrics = windows.currentWindowMetrics
    val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
      WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
    )
    return OverlaySize(
      (metrics.bounds.width() - insets.left - insets.right).coerceAtLeast(1),
      (metrics.bounds.height() - insets.top - insets.bottom).coerceAtLeast(1),
    )
  }

  private fun bubbleSize(): OverlaySize {
    val side = (56 * resources.displayMetrics.density).roundToInt()
    return OverlaySize(side, side)
  }

  private fun panelPlacementBounds(available: OverlaySize): OverlaySize = available.copy(
    height = clampOverlayHeight(Int.MAX_VALUE, 0, available, resources.displayMetrics.density),
  )

  private fun showBubble() {
    if (!Settings.canDrawOverlays(this)) { disableAndStop(); return }
    expanded = false
    val size = bubbleSize()
    val available = availableSize()
    if (!bubbleInitialized) {
      bubblePosition = OverlayPosition(available.width - size.width - 12, available.height / 3)
      bubbleInitialized = true
    }
    bubblePosition = clampOverlayPosition(bubblePosition, size, available)
    val icon = OverlayBubbleView(overlayContext).apply {
      contentDescription = "Open floating Icy Lyrics"
      elevation = 8 * resources.displayMetrics.density
      isClickable = true
      setOnClickListener { showPanel() }
      val playing = phoneSessionLease.viewModel.state.value.nowPlaying
      updateArtwork(playing?.artwork, playing?.isPlaying == true, preferences.options.value.spinArtwork)
    }
    var downX = 0f
    var downY = 0f
    var initial = bubblePosition
    var dragging = false
    val slop = ViewConfiguration.get(overlayContext).scaledTouchSlop
    icon.setOnTouchListener { view, event ->
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          downX = event.rawX; downY = event.rawY; initial = bubblePosition; dragging = false
          true
        }
        MotionEvent.ACTION_MOVE -> {
          val dx = event.rawX - downX
          val dy = event.rawY - downY
          if (abs(dx) > slop || abs(dy) > slop) dragging = true
          if (dragging) {
            bubblePosition = clampOverlayPosition(
              OverlayPosition(initial.x + dx.roundToInt(), initial.y + dy.roundToInt()), size, availableSize(),
            )
            moveWindow(bubblePosition)
          }
          true
        }
        MotionEvent.ACTION_UP -> { if (!dragging) view.performClick(); true }
        MotionEvent.ACTION_CANCEL -> { dragging = false; true }
        else -> false
      }
    }
    attachWindow(icon, size, bubblePosition)
  }

  private fun showPanel() {
    if (!Settings.canDrawOverlays(this)) { disableAndStop(); return }
    val available = availableSize()
    val density = resources.displayMetrics.density
    val defaultSize = overlayPanelSize(available, density)
    val size = defaultSize.copy(height = clampOverlayHeight(
      panelHeightDp?.let { (it * density).roundToInt() } ?: defaultSize.height,
      0, available, density,
    ))
    val position = clampOverlayPosition(
      if (expanded) layout?.let { OverlayPosition(it.x, it.y) } ?: bubblePosition else bubblePosition,
      size, panelPlacementBounds(available),
    )
    expanded = true
    val panel = ComposeView(overlayContext).apply {
      contentDescription = "Floating Icy Lyrics window"
      setViewTreeLifecycleOwner(this@LyricsOverlayService)
      setViewTreeSavedStateRegistryOwner(this@LyricsOverlayService)
      setViewTreeViewModelStoreOwner(this@LyricsOverlayService)
      setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
      setContent {
        LyricsOverlayContent(
          (application as IcyLyricsApplication).container.phoneViewModel,
          preferences,
          onMove = { amount ->
            layout?.let {
              val next = clampOverlayPosition(
                OverlayPosition(it.x + amount.x.roundToInt(), it.y + amount.y.roundToInt()),
                OverlaySize(it.width, it.height), panelPlacementBounds(availableSize()),
              )
              bubblePosition = clampOverlayPosition(next, bubbleSize(), availableSize())
              moveWindow(next)
            }
          },
          onCollapse = ::showBubble,
          onClose = ::disableAndStop,
          onResizeHeight = ::resizePanelHeight,
        )
      }
    }
    attachWindow(panel, size, position)
  }

  private fun attachWindow(view: View, size: OverlaySize, position: OverlayPosition) {
    removeWindow()
    val params = WindowManager.LayoutParams(
      size.width, size.height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
      PixelFormat.TRANSLUCENT,
    ).apply {
      gravity = Gravity.TOP or Gravity.LEFT
      x = position.x; y = position.y
      layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
      title = "Icy Lyrics floating window"
    }
    try {
      windows.addView(view, params)
      root = view
      layout = params
    } catch (error: RuntimeException) {
      Log.w("LyricsOverlay", "Android could not attach the floating window", error)
      (view as? ComposeView)?.disposeComposition()
      disableAndStop()
    }
  }

  private fun moveWindow(position: OverlayPosition) {
    val view = root ?: return
    val params = layout ?: return
    params.x = position.x; params.y = position.y
    try { windows.updateViewLayout(view, params) }
    catch (error: RuntimeException) {
      Log.w("LyricsOverlay", "Android could not move the floating window", error)
      disableAndStop()
    }
  }

  private fun resizePanelHeight(deltaY: Float) {
    if (!expanded || !deltaY.isFinite()) return
    val view = root ?: return
    val params = layout ?: return
    val density = resources.displayMetrics.density
    val height = clampOverlayHeight(
      (params.height + deltaY).roundToInt(), params.y, availableSize(), density,
    )
    if (params.height == height) return
    params.height = height
    panelHeightDp = height / density
    // Width and top/left are deliberately unchanged. The grabber stretches only the bottom edge.
    try { windows.updateViewLayout(view, params) }
    catch (error: RuntimeException) {
      Log.w("LyricsOverlay", "Android could not resize the floating window", error)
      disableAndStop()
    }
  }

  private fun removeWindow() {
    val view = root ?: return
    root = null
    layout = null
    // Explicitly dispose even on a collapse/screen-off, not just at service destruction.
    (view as? ComposeView)?.disposeComposition()
    runCatching { windows.removeViewImmediate(view) }
  }

  private fun disableAndStop() {
    preferences.setEnabled(false)
    removeWindow()
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onDestroy() {
    removeWindow()
    appOps?.stopWatchingMode(permissionListener)
    if (receiverRegistered) unregisterReceiver(screenReceiver)
    mainHandler.removeCallbacksAndMessages(null)
    registry.currentState = Lifecycle.State.DESTROYED
    viewModelStore.clear()
    phoneSessionLease.close()
    preferences.close()
    stopForeground(STOP_FOREGROUND_REMOVE)
    super.onDestroy()
  }

  companion object {
    private const val CHANNEL = "lyrics_overlay"
    private const val NOTIFICATION_ID = 130
    private const val ACTION_STOP = "com.icy.lyrics.overlay.STOP"

    /** Call from the visible Activity after opt-in, never from background playback callbacks. */
    fun start(context: Context): Boolean {
      if (!Settings.canDrawOverlays(context)) {
        OverlayPreferences(context).use { it.setEnabled(false) }
        return false
      }
      return try {
        ContextCompat.startForegroundService(context, Intent(context, LyricsOverlayService::class.java))
        true
      } catch (error: RuntimeException) {
        Log.w("LyricsOverlay", "Android rejected the overlay service start", error)
        OverlayPreferences(context).use { it.setEnabled(false) }
        false
      }
    }

    fun stop(context: Context) {
      OverlayPreferences(context).use { it.setEnabled(false) }
      context.stopService(Intent(context, LyricsOverlayService::class.java))
    }
  }
}
