"""Pinned release arguments plus the explicit additive FreeType manager settings."""
import json


def arguments(target, sdk_path, clang, clangxx):
    if target not in ("ios", "iosSim"):
        raise ValueError("Only iOS device/simulator arm64 are supported")
    # The common entries and RTTI/Metal/modules match the pinned build.py's
    # Release Apple path. ios_min_target applies the intended12.0 minimum to
    # simulator assembler commands too; the original script only set C flags.
    entries = {
        "is_official_build": True, "target_cpu": "arm64", "target_os": "ios",
        "ios_use_simulator": target == "iosSim", "ios_min_target": "12.0",
        "xcode_sysroot": sdk_path, "cc": clang, "cxx": clangxx,
        "skia_use_system_expat": False, "skia_use_system_libjpeg_turbo": False,
        "skia_use_system_libpng": False, "skia_use_system_libwebp": False,
        # Pinned upstream build.py still passes skia_use_sfntly, but M144 has
        # removed that argument. Do not pass no-op flags under fail-on-unused.
        "skia_use_system_zlib": False,
        "skia_use_system_freetype2": False, "skia_use_system_harfbuzz": False,
        "skia_pdf_subset_harfbuzz": True, "skia_use_system_icu": False,
        "skia_enable_skottie": True, "skia_use_metal": True,
        "extra_cflags": ["-USK_HIDE_PATH_EDIT_METHODS"],
        "extra_cflags_cc": ["-frtti", "-USK_HIDE_PATH_EDIT_METHODS"],
        "skia_use_freetype": True, "skia_use_fonthost_mac": True,
        "skia_enable_fontmgr_custom_embedded": True,
        "skia_enable_fontmgr_custom_directory": False,
        "skia_enable_fontmgr_custom_empty": False,
        "skia_enable_fontmgr_android": False,
        "skia_enable_fontmgr_fontconfig": False,
        "skia_ios_use_signing": False,
    }
    return "\n".join(f"{key} = {json.dumps(value)}" for key, value in entries.items()) + "\n"
