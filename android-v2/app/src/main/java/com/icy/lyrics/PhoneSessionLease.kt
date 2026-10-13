package com.icy.lyrics

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** One controller for phone surfaces, released when the final Activity/service owner goes away. */
class PhoneSession(private val application: Application) : ViewModelStoreOwner {
  override val viewModelStore = ViewModelStore()
  private var owners = 0

  val viewModel: IcyLyricsViewModel
    get() {
      check(owners > 0) { "Acquire a phone session before observing lyrics" }
      return ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[
        IcyLyricsViewModel::class.java,
      ]
    }

  fun acquire(): PhoneSessionLease {
    owners++
    return PhoneSessionLease(this)
  }

  internal fun release() {
    check(owners > 0)
    owners--
    if (owners == 0) viewModelStore.clear()
  }
}

/** Activity-owned instances survive rotation; the overlay service explicitly closes its lease. */
class PhoneSessionLease internal constructor(private val session: PhoneSession) : ViewModel(), AutoCloseable {
  private var closed = false
  val viewModel get() = session.viewModel

  override fun close() {
    if (!closed) {
      closed = true
      session.release()
    }
  }

  override fun onCleared() = close()
}
