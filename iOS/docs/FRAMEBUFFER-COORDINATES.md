# Exact framebuffer coordinates

The twelfth macOS verification artifact (run `33870670256`, commit `591c35d8f8d82fda3408702c6b900b8153dabda5`, artifact `9936823537`) contains 17 successful public `simctl` framebuffer captures. All are **1179 × 2556 RGBA8 PNGs in fixed portrait coordinates**, including the three captures whose measured UIKit window and Compose draw were landscape, **2556 × 1179**. PNG orientation is 1 and the sRGB rendering intent is 0. Fourteen portrait captures satisfy the existing geometry checks; the three landscape captures were correctly rejected. The original artifact SHA-256 is `5a3925e5bde231851a5e1697dc1820ac374ef5379edb99b61e329cefe82158cf`.

This observation motivates an explicit coordinate representation for future captures. Existing evidence is unchanged. The new mapping has synthetic byte/geometry tests and accepts the 17 actual raw PNG formats, but **has not yet executed against the new measured corner metadata on macOS**. It does not establish appearance parity.

## Public measurements

Apple documents that [`UIScreen.fixedCoordinateSpace`](https://developer.apple.com/documentation/uikit/uiscreen/fixedcoordinatespace) and [`nativeBounds`](https://developer.apple.com/documentation/uikit/uiscreen/nativebounds) stay in portrait-up display coordinates. A window is a [`UICoordinateSpace`](https://developer.apple.com/documentation/uikit/uicoordinatespace); public [`convert(_:to:)`](https://developer.apple.com/documentation/uikit/uicoordinatespace/convert(_:to:)-2ub7a) measures its point correspondence with that fixed space. Interface-orientation enum values are descriptive only and never select the pixel operation.

DEBUG capture metadata records `fixedCoordinateMapping`, schema version 1:

| Fields | Meaning |
| --- | --- |
| `windowBoundsPoints`, `contentBoundsInWindowPoints`, `fixedBoundsPoints`, `screenNativeBoundsPixels` | Rectangles `[x,y,width,height]` in their named spaces. |
| `displayScale`, `nativeDisplayScale` | Actual window screen scales; this capture lane requires equality. |
| `cornerOrder` | Exactly `TL, TR, BR, BL`. |
| `windowCornersInWindowPoints`, `windowCornersInFixedPoints`, `roundTripsInWindowPoints` | Ordered window corner conversions and independently measured return conversions. |
| `contentCornersInWindowPoints`, `contentCornersInFixedPoints`, `contentRoundTripsInWindowPoints` | Content position and its independently measured conversions. |
| `sampleOrder` | Exactly `center, centerPlusOnePixelX, centerPlusOnePixelY`. |
| `sampleWindowPoints`, `sampleFixedPoints`, `sampleRoundTripsInWindowPoints` | Independently converted window center and steps of `1 / displayScale` points along each axis. |

Window/content/native bounds and both scales must also agree with the corresponding top-level UIKit metadata. The test remeasures after capture and records `fixedCoordinateMappingAfterCapture` and `fixedCoordinateMappingStableDuringCapture: true` only when the complete measured object is unchanged. The host processes the request before that second measurement exists; extraction also verifies the completed stability attestation.

## Pixel proof

`scripts/framebuffer_coordinates.py` exposes:

```python
window_png, certificate = map_framebuffer(raw_png, metadata, expected_size)
verified_certificate = verify_framebuffer_mapping(
    raw_png, window_png, metadata, certificate
)
```

The module converts measured fixed corners to pixel edges and certifies an origin plus two orthogonal signed-unit axes with determinant **+1**. The edges must cover the whole original framebuffer once, with equal source/destination pixel counts. Center, pixel-step, content and return measurements must agree. It rejects skew, mirroring, scaling, fractional translation, cropped coverage and unsupported dimensions. Only identity, either quarter turn, or a half turn can satisfy this full-window proof.

Floating-point checks allow **four binary64 ULPs at the compared operand magnitude, with magnitude bounded below by 1**. This covers only point arithmetic and serialized IEEE-754 values; it is not a visual threshold or fraction-of-a-pixel tolerance. Tests accept a one-ULP perturbation and reject a five-ULP perturbation at that bound. No larger tolerance has been justified by native measurements; unexpected geometry fails for inspection.

The operation is a byte permutation of the four RGBA channels per pixel. There is no interpolation, resizing, masking, synthesized border or color conversion. Every output channel byte must equal the independently reconstructed forward mapping; applying the inverse must recover every original RGBA byte. Verification reads the supplied output PNG and does not require two hosts' PNG encoders or zlib versions to produce identical compressed bytes.

## Raw and derivative evidence

The raw PNG remains the primary capture, with its original bytes and SHA-256. Identity returns those same PNG bytes. A transformed window PNG is a separately identified derivative. The certificate includes both PNG hashes, both decoded RGBA hashes, inverse recovery hash, raw/window dimensions, measured mapping hash, signed axes/origin, operation, and ancillary chunk hashes. A changed pixel, metadata field or certificate fails independent verification.

The bounded PNG reader validates chunk lengths, CRCs, ordering, compressed scanlines and format. It accepts noninterlaced RGBA8 only, requires orientation 1, and rejects unsupported color/format chunks instead of flattening them. It preserves all supported color interpretation chunks and EXIF color values. The actual simulator EXIF also stores pixel width and height: the derivative updates **only known scalar EXIF image-dimension fields** to the new dimensions. All other EXIF bytes remain unchanged. The original EXIF is untouched in the raw capture. Unknown/non-scalar EXIF dimensions and nonsquare physical-pixel metadata fail closed.

The capture service and extractor retain raw evidence and verify this certificate before using a derivative for geometry or comparison. This makes the coordinate change reviewable; full screen coverage, exact Android comparisons, motion validation and physical iPhone testing remain separate acceptance requirements.
