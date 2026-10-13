package com.icy.lyrics.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.icy.lyrics.MainActivity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayBubbleViewDeviceTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Test fun rectangularArtworkIsCenterCroppedInsideATransparentCircle() {
    // Both red and blue side bars must be cropped off, not squeezed into the circular image.
    val wideCover = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply {
      val paint = Paint()
      Canvas(this).apply {
        paint.color = Color.RED
        drawRect(0f, 0f, 50f, 100f, paint)
        paint.color = Color.GREEN
        drawRect(50f, 0f, 150f, 100f, paint)
        paint.color = Color.BLUE
        drawRect(150f, 0f, 200f, 100f, paint)
      }
    }
    instrumentation.runOnMainSync {
      val bubble = OverlayBubbleView(context)
      bubble.layout(0, 0, 100, 100)
      bubble.updateArtwork(wideCover.asImageBitmap(), isPlaying = true, spinEnabled = true)
      // An unattached view must not create a persistent frame loop.
      assertFalse(bubble.isSpinning)
      val drawn = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
      bubble.draw(Canvas(drawn))
      assertEquals(Color.GREEN, drawn.getPixel(20, 50))
      assertEquals(Color.GREEN, drawn.getPixel(50, 50))
      assertEquals(Color.GREEN, drawn.getPixel(80, 50))
      assertEquals(0, Color.alpha(drawn.getPixel(0, 0)))
      assertEquals(0, Color.alpha(drawn.getPixel(99, 99)))
      capture(drawn, "test3-bubble-album.png")
      assertEquals(0f, bubble.rotation, 0f)
      assertTrue(bubble.hasAlbumArtwork)
      bubble.updateArtwork(null, isPlaying = true, spinEnabled = true)
      assertFalse(bubble.hasAlbumArtwork)
      assertFalse(bubble.isSpinning)
      assertFalse(wideCover.isRecycled)
      val fallback = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
      bubble.draw(Canvas(fallback))
      capture(fallback, "test3-bubble-logo.png")
    }
  }

  @Test fun recordStopsForPauseToggleHiddenDetachAndMissingArtworkWithoutRotatingTheHitTarget() {
    val artwork = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
      eraseColor(Color.BLUE)
    }.asImageBitmap()
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        val bubble = OverlayBubbleView(activity)
        val parent = activity.findViewById<ViewGroup>(android.R.id.content)
        parent.addView(bubble, ViewGroup.LayoutParams(112, 112))
        try {
          bubble.updateArtwork(artwork, isPlaying = false, spinEnabled = true)
          assertFalse(bubble.isSpinning)
          bubble.updateArtwork(artwork, isPlaying = true, spinEnabled = true)
          assertEquals(ValueAnimator.areAnimatorsEnabled(), bubble.isSpinning)
          assertEquals(0f, bubble.rotation, 0f)
          var clicked = false
          bubble.setOnClickListener { clicked = true }
          assertTrue(bubble.performClick())
          assertTrue(clicked)

          bubble.updateArtwork(artwork, isPlaying = true, spinEnabled = false)
          assertFalse(bubble.isSpinning)
          assertTrue(bubble.hasAlbumArtwork)
          val pausedAngle = bubble.recordRotationDegrees
          bubble.updateArtwork(artwork, isPlaying = true, spinEnabled = true)
          assertEquals(pausedAngle, bubble.recordRotationDegrees, 0.01f)

          bubble.visibility = View.GONE
          assertFalse(bubble.isSpinning)
          bubble.visibility = View.VISIBLE
          assertEquals(ValueAnimator.areAnimatorsEnabled(), bubble.isSpinning)
          parent.removeView(bubble)
          assertFalse(bubble.isSpinning)
          parent.addView(bubble, ViewGroup.LayoutParams(112, 112))
          assertEquals(ValueAnimator.areAnimatorsEnabled(), bubble.isSpinning)

          bubble.updateArtwork(artwork, isPlaying = false, spinEnabled = true)
          assertFalse(bubble.isSpinning)
          bubble.updateArtwork(null, isPlaying = true, spinEnabled = true)
          assertFalse(bubble.isSpinning)
          assertFalse(bubble.hasAlbumArtwork)
          assertEquals(0f, bubble.recordRotationDegrees, 0f)
          assertEquals(0f, bubble.rotation, 0f)
        } finally {
          parent.removeView(bubble)
        }
      }
    }
  }

  @Test fun recordFramesAdvancePauseInPlaceAndResumeWithoutResetting() {
    assumeTrue("The device has disabled system animations", ValueAnimator.areAnimatorsEnabled())
    val artwork = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
      eraseColor(Color.BLUE)
    }.asImageBitmap()
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      lateinit var bubble: OverlayBubbleView
      scenario.onActivity { activity ->
        bubble = OverlayBubbleView(activity)
        activity.findViewById<ViewGroup>(android.R.id.content)
          .addView(bubble, ViewGroup.LayoutParams(112, 112))
        bubble.updateArtwork(artwork, isPlaying = true, spinEnabled = true)
      }
      fun angle(): Float {
        var result = 0f
        scenario.onActivity { result = bubble.recordRotationDegrees }
        return result
      }
      fun awaitAdvance(from: Float): Float {
        val deadline = SystemClock.uptimeMillis() + 3_000
        var observed = angle()
        while ((observed - from + 360f) % 360f < 1f && SystemClock.uptimeMillis() < deadline) {
          SystemClock.sleep(25)
          observed = angle()
        }
        assertTrue("The record should advance on actual display frames", (observed - from + 360f) % 360f >= 1f)
        return observed
      }
      try {
        awaitAdvance(0f)
        var pausedAngle = 0f
        scenario.onActivity {
          bubble.updateArtwork(artwork, isPlaying = false, spinEnabled = true)
          pausedAngle = bubble.recordRotationDegrees
          assertFalse(bubble.isSpinning)
          assertEquals(0f, bubble.rotation, 0f)
        }
        // This wait is intentionally off the UI thread, so a broken animator would keep advancing.
        SystemClock.sleep(200)
        assertEquals(pausedAngle, angle(), 0f)
        scenario.onActivity {
          bubble.updateArtwork(artwork, isPlaying = true, spinEnabled = true)
          assertEquals("Resume must preserve the current phase", pausedAngle, bubble.recordRotationDegrees, 0.01f)
          assertTrue(bubble.isSpinning)
        }
        awaitAdvance(pausedAngle)
      } finally {
        scenario.onActivity { (bubble.parent as? ViewGroup)?.removeView(bubble) }
      }
    }
  }

  private fun capture(bitmap: Bitmap, name: String) {
    val directory = File(context.getExternalFilesDir(null), "overlay/test3").apply { mkdirs() }
    File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }
}
