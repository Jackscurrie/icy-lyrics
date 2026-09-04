/* Copyright 2026 Icy Lyrics contributors. SPDX-License-Identifier: Apache-2.0 */
package org.jetbrains.skia

import org.jetbrains.skia.impl.*
import org.jetbrains.skia.impl.Library.Companion.staticLoad

/**
 * An additive iOS-only factory for bundled fonts, using Skia's FreeType backend.
 * FontMgr.default remains CoreText. The caller owns and must close the returned
 * Typeface. The face retains its font data: [data] may be closed after this call.
 * Unrecognized font bytes or unavailable nonnegative collection indices return null.
 */
fun makeTypefaceFromDataFreeType(data: Data, ttcIndex: Int = 0): Typeface? {
    require(ttcIndex >= 0) { "The collection index must be nonnegative" }
    check(!data.isClosed) { "Font data is already closed" }
    staticLoad()
    return try {
        Stats.onNativeCall()
        val pointer = _nMakeFromDataFreeType(getPtr(data), ttcIndex)
        if (pointer == Native.NullPointer) null else Typeface(pointer)
    } finally {
        reachabilityBarrier(data)
    }
}

@ExternalSymbolName("org_jetbrains_skia_FreeTypeTypeface__1nMakeFromData")
private external fun _nMakeFromDataFreeType(data: NativePointer, ttcIndex: Int): NativePointer
