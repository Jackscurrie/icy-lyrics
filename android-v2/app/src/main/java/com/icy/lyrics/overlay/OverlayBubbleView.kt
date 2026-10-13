package com.icy.lyrics.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.icy.lyrics.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The draggable window stays axis-aligned: only its artwork is rotated while drawing. Rotating the
 * View itself would also rotate touch coordinates and make a moving record difficult to grab.
 * Artwork belongs to the shared now-playing snapshot; this view never fetches or recycles it.
 */
internal class OverlayBubbleView(context: Context) : View(context) {
  private val density = resources.displayMetrics.density
  private val logo = requireNotNull(context.getDrawable(R.mipmap.ic_launcher)).mutate()
  private val artworkPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
  private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = Color.argb(90, 255, 255, 255)
    strokeWidth = density
  }
  private val circleBounds = RectF()
  private val cropMatrix = Matrix()
  private val clipPath = Path()
  private var artwork: Bitmap? = null
  private var playing = false
  private var spinEnabled = true
  private var animator: ValueAnimator? = null

  internal var recordRotationDegrees = 0f
    private set
  internal val isSpinning: Boolean get() = animator?.isStarted == true
  internal val hasAlbumArtwork: Boolean get() = artwork != null

  init {
    contentDescription = "Open floating Icy Lyrics"
    isClickable = true
    elevation = 8f * density
    outlineProvider = object : ViewOutlineProvider() {
      override fun getOutline(view: View, outline: Outline) {
        outline.setOval(
          circleBounds.left.roundToInt(), circleBounds.top.roundToInt(),
          circleBounds.right.roundToInt(), circleBounds.bottom.roundToInt(),
        )
      }
    }
  }

  fun updateArtwork(artwork: ImageBitmap?, isPlaying: Boolean, spinEnabled: Boolean) {
    // A null snapshot/artwork switches back to the familiar Icy logo, without an extra image load.
    val nextBitmap = artwork?.asAndroidBitmap()?.takeUnless { it.isRecycled }
    if (this.artwork !== nextBitmap) {
      stopSpinning()
      this.artwork = nextBitmap
      recordRotationDegrees = 0f
      artworkPaint.shader = nextBitmap?.let {
        BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
      }
      updateArtworkCrop()
      invalidate()
    }
    playing = isPlaying
    this.spinEnabled = spinEnabled
    syncAnimation()
  }

  // Both the drag listener's tap path and accessibility ACTION_CLICK use the normal View action.
  override fun performClick(): Boolean = super.performClick()

  override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
    super.onSizeChanged(width, height, oldWidth, oldHeight)
    val diameter = (min(width, height) - borderPaint.strokeWidth).coerceAtLeast(0f)
    val left = (width - diameter) / 2f
    val top = (height - diameter) / 2f
    circleBounds.set(left, top, left + diameter, top + diameter)
    clipPath.reset()
    clipPath.addOval(circleBounds, Path.Direction.CW)
    logo.setBounds(
      circleBounds.left.roundToInt(), circleBounds.top.roundToInt(),
      circleBounds.right.roundToInt(), circleBounds.bottom.roundToInt(),
    )
    updateArtworkCrop()
    invalidateOutline()
  }

  private fun updateArtworkCrop() {
    val bitmap = artwork ?: return
    val scale = max(circleBounds.width() / bitmap.width, circleBounds.height() / bitmap.height)
    cropMatrix.setScale(scale, scale)
    cropMatrix.postTranslate(
      circleBounds.centerX() - bitmap.width * scale / 2f,
      circleBounds.centerY() - bitmap.height * scale / 2f,
    )
    artworkPaint.shader?.setLocalMatrix(cropMatrix)
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    if (circleBounds.isEmpty) return
    val saved = canvas.save()
    canvas.clipPath(clipPath)
    if (artwork != null) {
      canvas.rotate(recordRotationDegrees, circleBounds.centerX(), circleBounds.centerY())
      canvas.drawOval(circleBounds, artworkPaint)
    } else {
      logo.draw(canvas)
    }
    canvas.restoreToCount(saved)
    canvas.drawOval(circleBounds, borderPaint)
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    syncAnimation()
  }

  override fun onVisibilityAggregated(isVisible: Boolean) {
    super.onVisibilityAggregated(isVisible)
    if (isVisible) syncAnimation() else stopSpinning()
  }

  override fun onWindowVisibilityChanged(visibility: Int) {
    super.onWindowVisibilityChanged(visibility)
    if (visibility == VISIBLE) syncAnimation() else stopSpinning()
  }

  override fun onDetachedFromWindow() {
    stopSpinning()
    super.onDetachedFromWindow()
  }

  private fun syncAnimation() {
    val shouldSpin = artwork != null && playing && spinEnabled && isAttachedToWindow &&
      isShown && windowVisibility == VISIBLE && ValueAnimator.areAnimatorsEnabled()
    if (!shouldSpin) {
      stopSpinning()
      return
    }
    if (animator != null) return
    val startingAngle = recordRotationDegrees
    animator = ValueAnimator.ofFloat(0f, 360f).apply {
      duration = 12_000L
      repeatCount = ValueAnimator.INFINITE
      interpolator = LinearInterpolator()
      addUpdateListener {
        // The system may turn off animation while an existing record is spinning.
        if (!ValueAnimator.areAnimatorsEnabled()) {
          stopSpinning()
        } else {
          recordRotationDegrees = (startingAngle + (it.animatedValue as Float)) % 360f
          invalidate()
        }
      }
      start()
    }
  }

  private fun stopSpinning() {
    animator?.apply {
      removeAllUpdateListeners()
      cancel()
    }
    animator = null
  }
}
