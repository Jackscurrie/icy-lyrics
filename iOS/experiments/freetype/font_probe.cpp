// Standalone public-Skia-API experiment, never linked into IcyShared or the app.
#include "include/core/SkBitmap.h"
#include "include/core/SkCanvas.h"
#include "include/core/SkData.h"
#include "include/core/SkFont.h"
#include "include/core/SkFontArguments.h"
#include "include/core/SkFontMetrics.h"
#include "include/core/SkFontMgr.h"
#include "include/core/SkPaint.h"
#include "include/core/SkStream.h"
#include "include/core/SkTypeface.h"
#include "include/encode/SkPngEncoder.h"
#include "include/ports/SkFontMgr_data.h"
#include "include/ports/SkFontMgr_mac_ct.h"
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <string>
#include <vector>

namespace {
constexpr char kHeader[] = "Play something in Spotify";
struct Sample {
    std::string id;
    const char* file;
    SkUnichar codepoint;
    SkGlyphID explicitGlyph; // Explicit GSUB result for the exact hash-pinned flag file.
    float size;
    int weight;
    bool linear;
};

bool render(const char* backend, const sk_sp<SkFontMgr>& manager, const Sample& sample,
            const std::string& fonts, const std::string& output, std::ostream& json) {
    json << "{\"backend\":\"" << backend << "\",\"id\":\"" << sample.id
         << "\",\"fontFile\":\"" << sample.file << "\",\"sizePx\":" << sample.size
         << ",\"requestedWeight\":" << sample.weight
         << ",\"linearMetrics\":" << (sample.linear ? "true" : "false")
         << ",\"subpixel\":false,\"hinting\":\"normal\"";
    auto data = SkData::MakeFromFileName((fonts + "/" + sample.file).c_str());
    auto face = data && manager ? manager->makeFromData(data, 0) : nullptr;
    if (face && sample.weight) {
        const SkFontArguments::VariationPosition::Coordinate axes[] = {
            {SkSetFourByteTag('w','g','h','t'), static_cast<float>(sample.weight)},
            {SkSetFourByteTag('w','d','t','h'), 100},
            {SkSetFourByteTag('i','t','a','l'), 0},
        };
        SkFontArguments args;
        args.setVariationDesignPosition({axes, 3});
        face = face->makeClone(args);
    }
    if (!face) {
        json << ",\"loaded\":false}";
        return false;
    }
    if (sample.weight && face->fontStyle().weight() != sample.weight) {
        json << ",\"loaded\":true,\"variationWeightMismatch\":true}";
        return false;
    }
    SkFont font(face, sample.size);
    font.setEdging(SkFont::Edging::kAntiAlias);
    font.setHinting(SkFontHinting::kNormal);
    font.setSubpixel(false);
    font.setLinearMetrics(sample.linear);
    font.setForceAutoHinting(false);
    font.setEmbeddedBitmaps(true);
    std::vector<SkGlyphID> glyphs;
    if (sample.explicitGlyph) {
        glyphs.push_back(sample.explicitGlyph);
    } else if (sample.codepoint) {
        glyphs.push_back(font.unicharToGlyph(sample.codepoint));
    } else {
        glyphs.resize(sizeof(kHeader) - 1);
        const auto count = font.textToGlyphs(kHeader, sizeof(kHeader) - 1,
                                            SkTextEncoding::kUTF8,
                                            {glyphs.data(), glyphs.size()});
        glyphs.resize(count);
    }
    for (auto glyph : glyphs) {
        if (!glyph || glyph >= face->countGlyphs()) {
            json << ",\"loaded\":true,\"glyphsValid\":false}";
            return false;
        }
    }
    std::vector<SkScalar> widths(glyphs.size());
    std::vector<SkRect> bounds(glyphs.size());
    font.getWidthsBounds({glyphs.data(), glyphs.size()}, {widths.data(), widths.size()},
                         {bounds.data(), bounds.size()}, nullptr);
    std::vector<SkPoint> positions;
    float advance = 0;
    for (auto width : widths) {
        positions.push_back({advance, 0});
        advance += width;
    }
    SkFontMetrics metrics;
    font.getMetrics(&metrics);
    const bool header = sample.weight != 0;
    SkBitmap bitmap;
    if (!bitmap.tryAllocPixels(SkImageInfo::MakeN32Premul(header ? 1400 : 256, 256))) {
        json << ",\"loaded\":true,\"allocationFailed\":true}";
        return false;
    }
    bitmap.eraseColor(SK_ColorTRANSPARENT);
    SkCanvas canvas(bitmap);
    SkPaint paint;
    paint.setColor(SK_ColorWHITE);
    paint.setAntiAlias(true);
    canvas.drawGlyphs({glyphs.data(), glyphs.size()}, {positions.data(), positions.size()},
                      {32, 180}, font, paint);
    const std::string filename = std::string(backend) + "-" + sample.id + ".png";
    SkFILEWStream stream((output + "/" + filename).c_str());
    if (!stream.isValid() || !SkPngEncoder::Encode(&stream, bitmap.pixmap(), {})) {
        json << ",\"loaded\":true,\"encodeFailed\":true}";
        return false;
    }
    json << ",\"loaded\":true,\"glyphsValid\":true,\"png\":\"" << filename
         << "\",\"fontStyleWeight\":" << face->fontStyle().weight()
         << ",\"unshapedGlyphAdvanceSumPx\":" << advance
         << ",\"fontMetrics\":{\"ascent\":" << metrics.fAscent
         << ",\"descent\":" << metrics.fDescent << ",\"leading\":" << metrics.fLeading
         << "},\"glyphs\":[";
    for (size_t i = 0; i < glyphs.size(); ++i) {
        if (i) json << ',';
        json << "{\"id\":" << glyphs[i] << ",\"advance\":" << widths[i]
             << ",\"bounds\":[" << bounds[i].left() << ',' << bounds[i].top() << ','
             << bounds[i].right() << ',' << bounds[i].bottom() << "]}";
    }
    json << "]}";
    return std::isfinite(advance) && advance > 0;
}
} // namespace

int main(int argc, char** argv) {
    if (argc != 3) {
        std::fprintf(stderr, "Usage: icy_freetype_probe FONT_DIRECTORY OUTPUT_DIRECTORY\n");
        return 2;
    }
    const std::string fonts(argv[1]), output(argv[2]);
    std::vector<sk_sp<SkData>> data;
    for (const auto* name : {"Roboto-Regular.ttf", "NotoColorEmoji.ttf", "NotoColorEmojiFlags.ttf"}) {
        auto blob = SkData::MakeFromFileName((fonts + "/" + name).c_str());
        if (!blob) return 3;
        data.push_back(std::move(blob));
    }
    auto freetype = SkFontMgr_New_Custom_Data({data.data(), data.size()});
    auto coretext = SkFontMgr_New_CoreText(nullptr);
    if (!freetype || !coretext) return 4;
    std::vector<Sample> samples = {
        {"colrv1-snowflake", "NotoColorEmoji.ttf", 0x2744, 0, 109, 0, false},
        {"colrv1-musical-note", "NotoColorEmoji.ttf", 0x1f3b5, 0, 109, 0, false},
        {"colrv1-heart", "NotoColorEmoji.ttf", 0x2764, 0, 109, 0, false},
        // Bitmap glyph indices, not Unicode shaping: hash-pinned GSUB results documented in lock.
        {"cbdt-canada", "NotoColorEmojiFlags.ttf", 0, 65, 109, 0, false},
        {"cbdt-us", "NotoColorEmojiFlags.ttf", 0, 261, 109, 0, false},
    };
    for (int weight : {400, 700}) {
        for (float size : {73.0f, 73.5f, 84.0f}) {
            for (bool linear : {false, true}) {
                char id[100];
                std::snprintf(id, sizeof(id), "roboto-%d-%.1f-linear-%d", weight, size, linear);
                samples.push_back({id, "Roboto-Regular.ttf", 0, 0, size, weight, linear});
            }
        }
    }
    std::ofstream json(output + "/metrics.json");
    if (!json) return 5;
    json << std::setprecision(9)
         << "{\"schemaVersion\":1,\"scope\":\"Standalone macOS CPU raster glyphs; no shaping, "
            "Compose, UIKit, iOS execution, or pixel parity claim\",\"header\":\""
         << kHeader << "\",\"samples\":[";
    bool first = true, passed = true;
    for (const auto* backend : {"freetype", "coretext"}) {
        for (const auto& sample : samples) {
            if (!first) json << ',';
            first = false;
            const bool ok = render(backend, std::strcmp(backend, "freetype") == 0 ? freetype : coretext,
                                   sample, fonts, output, json);
            // CoreText color support is an observed comparison, not a prerequisite.
            if (std::strcmp(backend, "freetype") == 0 || sample.weight) passed &= ok;
        }
    }
    json << "]}\n";
    json.close();
    return passed && json ? 0 : 6;
}
