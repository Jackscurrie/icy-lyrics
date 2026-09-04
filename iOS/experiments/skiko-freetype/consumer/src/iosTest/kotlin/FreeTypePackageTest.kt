@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.*
import org.jetbrains.skia.impl.use
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.TextStyle
import org.jetbrains.skia.paragraph.TypefaceFontProvider
import platform.Foundation.NSData
import platform.Foundation.NSProcessInfo
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy
import platform.posix.memset
import kotlin.test.*

/** Independent consumer of the published managed API. No app dependency is changed. */
class FreeTypePackageTest {
    @Test fun invalidDataClosedDataAndCollectionIndicesHaveDefinedBehavior() {
        Data.makeFromBytes(byteArrayOf(1, 2, 3)).use { assertNull(makeTypefaceFromDataFreeType(it)) }
        Data.makeFromBytes(fontBytes("Roboto-Regular.ttf")).use {
            assertFailsWith<IllegalArgumentException> { makeTypefaceFromDataFreeType(it, -1) }
            assertNull(makeTypefaceFromDataFreeType(it, 99))
        }
        val closed = Data.makeEmpty()
        closed.close()
        assertFailsWith<IllegalStateException> { makeTypefaceFromDataFreeType(closed) }
    }

    @Test fun returnedFaceSurvivesDataCloseAndVariationCloneWhileCoreTextDefaultStillWorks() {
        // makeWithoutCopy keeps its owner only in Kotlin. The factory promises
        // its own byte copy, not just another reference to borrowed SkData.
        val freeType = Data.makeFromBytes(fontBytes("Roboto-Regular.ttf")).use { owner ->
            val borrowed = Data.makeWithoutCopy(owner.writableData(), owner.size, owner)
            val face = try { assertNotNull(makeTypefaceFromDataFreeType(borrowed)) } finally { borrowed.close() }
            memset(assertNotNull(interpretCPointer<ByteVar>(owner.writableData())), 0, owner.size.toULong())
            face
        }
        // Keep the unchanged CoreText factory on its ordinary owned-data path;
        // do not impose the new FreeType factory's copy guarantee on CoreText.
        val coreText = Data.makeFromBytes(fontBytes("Roboto-Regular.ttf")).use {
            assertNotNull(FontMgr.default.makeFromData(it))
        }
        freeType.use { base ->
            val regular = base.makeClone(arrayOf(FontVariation("wght", 400f), FontVariation("wdth", 100f), FontVariation("ital", 0f)))
            val bold = base.makeClone(arrayOf(FontVariation("wght", 700f), FontVariation("wdth", 100f), FontVariation("ital", 0f)))
            regular.use { r -> bold.use { b ->
                assertEquals(400, r.fontStyle.weight)
                assertEquals(700, b.fontStyle.weight)
                val glyphs = r.getUTF32Glyphs("Play something in Spotify".map(Char::code).toIntArray())
                assertTrue(glyphs.all { it.toInt() != 0 })
                Font(r, 73.5f).use { normal -> Font(b, 73.5f).use { strong ->
                    val normalWidth = normal.getWidths(glyphs).sum()
                    val boldWidth = strong.getWidths(glyphs).sum()
                    assertTrue(normalWidth.isFinite() && normalWidth > 0)
                    assertTrue(boldWidth.isFinite() && boldWidth != normalWidth)
                    write("font-metrics.json", """{"backend":"FreeType managed consumer","normalGlyphAdvanceSum":$normalWidth,"boldGlyphAdvanceSum":$boldWidth,"scope":"Raw glyph metrics; no Android paragraph parity claim"}""".encodeToByteArray())
                } }
            } }
        }
        coreText.use { face ->
            assertTrue(face.getUTF32Glyphs(intArrayOf('A'.code)).single().toInt() != 0)
            Font(face, 84f).use { font -> assertTrue(font.metrics.ascent.isFinite() && font.metrics.ascent < 0) }
        }
    }

    @Test fun originalColrv1AndCbdtGlyphsRenderColoredPixelsAfterInputDataCloses() {
        val cases = listOf(
            Triple("colrv1-snowflake", "NotoColorEmoji.ttf", 0x2744),
            Triple("colrv1-musical-note", "NotoColorEmoji.ttf", 0x1f3b5),
            Triple("colrv1-heart", "NotoColorEmoji.ttf", 0x2764),
            Triple("cbdt-canada", "NotoColorEmojiFlags.ttf", -65),
            Triple("cbdt-us", "NotoColorEmojiFlags.ttf", -261),
        )
        for ((id, file, characterOrGlyph) in cases) {
            val data = Data.makeFromBytes(fontBytes(file))
            val face = assertNotNull(makeTypefaceFromDataFreeType(data))
            data.close()
            face.use { typeface ->
                val glyph = if (characterOrGlyph < 0) (-characterOrGlyph).toShort() else typeface.getUTF32Glyphs(intArrayOf(characterOrGlyph)).single()
                assertTrue(glyph.toInt() != 0)
                Font(typeface, 109f).use { font ->
                    val blob = assertNotNull(TextBlob.makeFromPosH(shortArrayOf(glyph), floatArrayOf(0f), 0f, font))
                    blob.use { text -> capture(id) { canvas -> Paint().use { paint -> canvas.drawTextBlob(text, 32f, 180f, paint) } } }
                }
            }
        }
    }

    @Test fun originalFlagSequencesShapeThroughTheFullSkParagraphAndHarfBuzzPackage() {
        val data = Data.makeFromBytes(fontBytes("NotoColorEmojiFlags.ttf"))
        val face = assertNotNull(makeTypefaceFromDataFreeType(data))
        data.close()
        face.use { typeface -> TypefaceFontProvider().use { provider ->
            provider.registerTypeface(typeface, "ExactAndroidFlags")
            FontCollection().use { collection ->
                collection.setAssetFontManager(provider)
                TextStyle().use { textStyle ->
                    textStyle.setFontFamilies(arrayOf("ExactAndroidFlags"))
                    textStyle.fontSize = 109f
                    ParagraphStyle().use { style ->
                        style.textStyle = textStyle
                        for ((id, sequence) in listOf("paragraph-canada" to "\uD83C\uDDE8\uD83C\uDDE6", "paragraph-us" to "\uD83C\uDDFA\uD83C\uDDF8")) {
                            ParagraphBuilder(style, collection).use { builder ->
                                builder.addText(sequence)
                                builder.build().use { paragraph ->
                                    paragraph.layout(200f)
                                    capture(id) { canvas -> paragraph.paint(canvas, 32f, 32f) }
                                }
                            }
                        }
                    }
                }
            }
        } }
    }

    private fun fontBytes(name: String): ByteArray {
        val root = assertNotNull(NSProcessInfo.processInfo.environment["ICY_SKIKO_FONT_ROOT"] as? String)
        val data = assertNotNull(NSData.dataWithContentsOfFile("$root/$name"))
        assertTrue(data.length > 0u && data.length <= Int.MAX_VALUE.toULong(), "Invalid fixture font length: $name")
        return ByteArray(data.length.toInt()).also { bytes -> bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) } }
    }

    private fun capture(id: String, draw: (Canvas) -> Unit) {
        Surface.makeRasterN32Premul(256, 256).use { surface ->
            surface.canvas.clear(0)
            draw(surface.canvas)
            surface.makeImageSnapshot().use { image ->
                Bitmap.makeFromImage(image).use { bitmap ->
                    val pixels = assertNotNull(bitmap.readPixels(ImageInfo(256, 256, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL, ColorSpace.sRGB)))
                    val colored = (pixels.indices step 4).count { i ->
                        val r = pixels[i].toInt() and 255; val g = pixels[i + 1].toInt() and 255; val b = pixels[i + 2].toInt() and 255
                        (pixels[i + 3].toInt() and 255) > 16 && maxOf(r, g, b) - minOf(r, g, b) > 8
                    }
                    assertTrue(colored > 0, "Original color font produced no chromatic pixels: $id")
                    write("$id.rgba", pixels)
                    assertNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { write("$id.png", it.bytes) }
                    write("$id.json", """{"id":"$id","chromaPixels":$colored,"backend":"Skia raster on native iOS simulator, experimental managed FreeType factory","metalVerified":false,"androidPixelParityVerified":false}""".encodeToByteArray())
                }
            }
        }
    }

    private fun write(name: String, bytes: ByteArray) {
        val root = assertNotNull(NSProcessInfo.processInfo.environment["ICY_SKIKO_OUTPUT_ROOT"] as? String)
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        assertTrue(data.writeToFile("$root/$name", atomically = true))
    }
}
