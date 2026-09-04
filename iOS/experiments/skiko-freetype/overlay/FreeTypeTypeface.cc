/* Copyright 2026 Icy Lyrics contributors. SPDX-License-Identifier: Apache-2.0 */
#include "include/core/SkTypes.h"

#if defined(SK_BUILD_FOR_IOS)
#include "SkData.h"
#include "SkFontMgr.h"
#include "SkTypeface.h"
#include "include/ports/SkFontMgr_data.h"
#include "common.h"

// This bridge is compiled into the same Skiko package as its managed Kotlin
// wrapper. No native pointer or private ABI is exposed to consuming application code.
SKIKO_EXPORT KNativePointer org_jetbrains_skia_FreeTypeTypeface__1nMakeFromData(
        KNativePointer dataPtr, KInt ttcIndex) {
    auto* data = reinterpret_cast<SkData*>(dataPtr);
    if (!data || ttcIndex < 0) return nullptr;
    // Data.makeWithoutCopy can have a Kotlin-only memory owner. Give this face
    // its own SkData allocation so closing either caller-owned object is safe.
    sk_sp<SkData> retained[] = {SkData::MakeWithCopy(data->data(), data->size())};
    if (!retained[0]) return nullptr;
    auto manager = SkFontMgr_New_Custom_Data({retained, 1});
    if (!manager) return nullptr;
    return manager->makeFromData(retained[0], ttcIndex).release();
}
#endif
