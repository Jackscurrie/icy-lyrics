package com.icy.lyrics.ui

internal fun advanceKawarpPhase(currentSeconds: Float, deltaNanos: Long, isPlaying: Boolean): Float {
  val speed = if (isPlaying) PLAYING_ANIMATION_SPEED else PAUSED_ANIMATION_SPEED
  return currentSeconds + deltaNanos.coerceIn(0L, MAX_FRAME_DELTA_NANOS) / 1_000_000_000f * speed
}

internal const val BLUR_SIZE = 128
internal const val BLUR_PASSES = 8
internal const val WARP_INTENSITY = 1f
internal const val PLAYING_ANIMATION_SPEED = 1f
internal const val PAUSED_ANIMATION_SPEED = 0.1f
internal const val MAX_FRAME_DELTA_NANOS = 250_000_000L
internal const val SATURATION = 1.5f
internal const val DITHERING = 0.008f
internal const val FIRST_CROSSFADE_NANOS = 500_000_000L
internal const val SUBSEQUENT_CROSSFADE_NANOS = 1_000_000_000L

internal const val KAWARP_SHADER = """
  uniform shader fromImage;
  uniform shader toImage;
  uniform float2 resolution;
  uniform float time;
  uniform float blend;
  uniform float intensity;
  uniform float saturation;
  uniform float dithering;

  float3 mod289_3(float3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
  float2 mod289_2(float2 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
  float3 permute(float3 x) { return mod289_3(((x * 34.0) + 1.0) * x); }

  float snoise(float2 v) {
    float4 C = float4(0.211324865405187, 0.366025403784439,
                      -0.577350269189626, 0.024390243902439);
    float2 i = floor(v + dot(v, C.yy));
    float2 x0 = v - i + dot(i, C.xx);
    float2 i1 = x0.x > x0.y ? float2(1.0, 0.0) : float2(0.0, 1.0);
    float4 x12 = x0.xyxy + C.xxzz;
    x12.xy -= i1;
    i = mod289_2(i);
    float3 p = permute(permute(i.y + float3(0.0, i1.y, 1.0)) + i.x + float3(0.0, i1.x, 1.0));
    float3 m = max(0.5 - float3(dot(x0,x0), dot(x12.xy,x12.xy), dot(x12.zw,x12.zw)), 0.0);
    m = m * m; m = m * m;
    float3 x = 2.0 * fract(p * C.www) - 1.0;
    float3 h = abs(x) - 0.5;
    float3 ox = floor(x + 0.5);
    float3 a0 = x - ox;
    m *= 1.79284291400159 - 0.85373472095314 * (a0 * a0 + h * h);
    float3 g;
    g.x = a0.x * x0.x + h.x * x0.y;
    g.yz = a0.yz * x12.xz + h.yz * x12.yw;
    return 130.0 * dot(m, g);
  }

  float hash(float2 p) {
    return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
  }

  half4 main(float2 fragCoord) {
    float2 uv = fragCoord / resolution;
    float t = time * 0.05;
    float2 center = uv - 0.5;
    float centerWeight = 1.0 - smoothstep(0.0, 0.7, length(center));
    float n1 = snoise(uv * 0.35 + float2(t, t * 0.7));
    float n2 = snoise(uv * 0.35 + float2(-t * 0.8, t * 0.5) + float2(50.0));
    float n3 = snoise(uv * 0.9 + float2(t * 1.2, -t) + float2(100.0, 0.0));
    float n4 = snoise(uv * 0.9 + float2(-t, t * 1.1) + float2(0.0, 100.0));
    float2 warp = float2(n1 * 0.65 + n3 * 0.35, n2 * 0.65 + n4 * 0.35) * centerWeight;
    float2 warpedUV = clamp(uv + warp * intensity, 0.0, 1.0);
    float2 samplePoint = warpedUV * 128.0;
    float4 color = mix(float4(fromImage.eval(samplePoint)), float4(toImage.eval(samplePoint)), blend);
    float vignette = 1.0 - dot(center, center) * 0.3;
    color.rgb *= vignette;
    float gray = dot(color.rgb, float3(0.299, 0.587, 0.114));
    color.rgb = mix(float3(gray), color.rgb, saturation);
    float noise = hash(floor(fragCoord) + floor(time * 60.0));
    color.rgb += (noise - 0.5) * dithering;
    return half4(color.rgb, 1.0);
  }
"""
