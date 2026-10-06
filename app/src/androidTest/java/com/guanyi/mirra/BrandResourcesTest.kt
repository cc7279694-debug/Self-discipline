package com.guanyi.mirra

import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/**
 * Resource-only checks: no Activity, application repository, preferences or system settings.
 * Masks and day/night tints are controlled previews, not evidence of an OEM launcher's behavior.
 * Evidence uses a dedicated, canonically isolated subdirectory of the target app's external cache.
 */
@RunWith(AndroidJUnit4::class)
class BrandResourcesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val renderSizes = listOf(32, 64, 128, 256, 512, 1024)
    private val brandBackground: Int
        get() = targetContext.getColor(R.color.mirra_brand_background)

    @Test
    fun manifestIconResolvesAdaptiveLayersAndApi33Monochrome() {
        val info = targetContext.packageManager.getApplicationInfo(targetContext.packageName, 0)
        assertEquals("Manifest must use the launcher mipmap", R.mipmap.ic_launcher, info.icon)
        val actualIcon = targetContext.packageManager.getApplicationIcon(info)
        if (Build.VERSION.SDK_INT >= 26) {
            assertTrue("API 26+ Manifest icon must be adaptive", actualIcon is AdaptiveIconDrawable)
            val adaptive = actualIcon as AdaptiveIconDrawable
            assertSameRendering("adaptive foreground", adaptive.foreground, drawable(R.drawable.ic_mirra_foreground))
            withBitmap(render(adaptive.background, 128)) { bitmap ->
                assertTrue("Adaptive background must be the opaque brand color", pixels(bitmap).all { it == brandBackground })
            }
            if (Build.VERSION.SDK_INT >= 33) {
                val monochrome = adaptive.monochrome
                assertNotNull("API 33+ actual Manifest icon must expose monochrome", monochrome)
                assertSameRendering("adaptive monochrome", requireNotNull(monochrome), drawable(R.drawable.ic_mirra_monochrome))
            }
        } else {
            assertTrue("API 23–25 must retain a scalable fallback", actualIcon is LayerDrawable)
            withBitmap(render(actualIcon, 128)) { bitmap ->
                assertEquals(brandBackground, bitmap.getPixel(0, 0))
                assertTrue("Legacy icon must render artwork", pixels(bitmap).any { it != brandBackground })
            }
        }
    }

    @Test
    fun foregroundAlphaStaysInsideCentralSixtySixDpCircleAtAllSizes() {
        renderSizes.forEach { size ->
            withBitmap(render(drawable(R.drawable.ic_mirra_foreground), size)) { bitmap ->
                assertSafeCircle(bitmap, "foreground $size px")
                assertTransparentEdges(bitmap, "foreground $size px")
                assertTrue("Foreground $size px must retain opaque artwork", pixels(bitmap).any { Color.alpha(it) == 255 })
            }
        }
    }

    @Test
    fun monochromeUsesOneWhiteInkWithTransparentNegativeSpace() {
        renderSizes.forEach { size ->
            withBitmap(render(drawable(R.drawable.ic_mirra_monochrome), size)) { bitmap ->
                assertOneInkAndNegativeSpace(bitmap, Color.WHITE, "monochrome $size px")
                assertSafeCircle(bitmap, "monochrome $size px")
            }
        }
    }

    @Test
    fun dayAndNightTintPreviewsPreserveAlphaAndRemainReadable() {
        val palettes = listOf(
            TintPreview("Day tint", Configuration.UI_MODE_NIGHT_NO, Color.rgb(232, 231, 221), Color.rgb(52, 53, 50)),
            TintPreview("Night tint", Configuration.UI_MODE_NIGHT_YES, Color.rgb(41, 42, 40), Color.rgb(232, 227, 215)),
        )
        palettes.forEach { palette ->
            val themedContext = tintContext(palette.nightMode)
            assertEquals(palette.nightMode, themedContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
            assertTrue("${palette.label} must have at least 4.5:1 contrast", contrastRatio(palette.ink, palette.background) >= 4.5)
            renderSizes.forEach { size ->
                withBitmap(render(monochrome(themedContext), size)) { original ->
                    withBitmap(render(monochrome(themedContext).apply { setTint(palette.ink) }, size)) { tinted ->
                        assertSameAlpha(original, tinted, "${palette.label} $size px")
                        assertOneInkAndNegativeSpace(tinted, palette.ink, "${palette.label} $size px")
                        withBitmap(composite(tinted, palette.background)) { opaquePreview ->
                            assertEquals("Negative space must expose the theme tile", palette.background, opaquePreview.getPixel(size / 2, size / 2))
                            assertTrue("${palette.label} must keep visible ink", pixels(opaquePreview).any { it == palette.ink })
                        }
                    }
                }
            }
        }
    }

    @Test
    fun notificationSmallIconIsWhiteArtworkWithoutAnOpaqueTile() {
        renderSizes.forEach { size ->
            withBitmap(render(drawable(R.drawable.ic_launcher), size)) { bitmap ->
                assertOneInkAndNegativeSpace(bitmap, Color.WHITE, "notification $size px", maximumCoverage = 0.6)
            }
        }
    }

    @Test
    fun manifestStartingThemeReferencesStaticBrandResources() {
        val launcherActivity = ComponentName(targetContext.packageName, "${targetContext.packageName}.MainActivity")
        val activityInfo = targetContext.packageManager.getActivityInfo(launcherActivity, 0)
        assertEquals("Launcher Activity must select the starting theme", R.style.Theme_Mirra_Starting, activityInfo.theme)
        val startingContext = ContextThemeWrapper(targetContext, R.style.Theme_Mirra_Starting)
        if (Build.VERSION.SDK_INT >= 31) {
            assertThemeResource(startingContext, android.R.attr.windowBackground, R.color.mirra_brand_background)
            assertThemeResource(startingContext, android.R.attr.windowSplashScreenBackground, R.color.mirra_brand_background)
            assertThemeResource(startingContext, android.R.attr.windowSplashScreenAnimatedIcon, R.drawable.ic_mirra_foreground)
        } else {
            assertThemeResource(startingContext, android.R.attr.windowBackground, R.drawable.mirra_starting_window)
        }
        assertFalse("Splash artwork must remain static", drawable(R.drawable.ic_mirra_foreground, startingContext) is Animatable)
        val legacyWindow = drawable(R.drawable.mirra_starting_window, startingContext)
        assertTrue("Legacy starting window must be a layer list", legacyWindow is LayerDrawable)
        assertEquals(2, (legacyWindow as LayerDrawable).numberOfLayers)
        val references = mutableListOf<Int>()
        targetContext.resources.getXml(R.drawable.mirra_starting_window).use { parser ->
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "item") {
                    references += parser.getAttributeResourceValue("http://schemas.android.com/apk/res/android", "drawable", 0)
                }
                parser.next()
            }
        }
        assertEquals("Legacy splash must use the same background and static vector", listOf(R.color.mirra_brand_background, R.drawable.ic_mirra_foreground), references)
        withBitmap(render(legacyWindow.getDrawable(0), 64)) { bitmap ->
            assertTrue("Legacy splash background must stay opaque", pixels(bitmap).all { it == brandBackground })
        }
    }

    @Test
    fun previewMasksPreserveForegroundAndWriteIsolatedCacheContactSheet() {
        val cellWidth = 220
        val cellHeight = 244
        val headerHeight = 94
        val labels = PreviewMask.entries.map { it.label } + listOf("Day tint preview", "Night tint preview", "Notification alpha", "Manifest device mask")
        val sheet = Bitmap.createBitmap(labels.size * cellWidth, headerHeight + renderSizes.size * cellHeight, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(sheet)
            canvas.drawColor(Color.rgb(238, 240, 242))
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(43, 48, 55)
                textSize = 18f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            }
            canvas.drawText("Mirra brand | actual Android Drawable rasterization", 16f, 28f, labelPaint)
            labelPaint.textSize = 14f
            canvas.drawText("Preview masks only; adaptive layer 108dp / visible viewport 72dp / authoritative safe circle 66dp.", 16f, 52f, labelPaint)
            canvas.drawText("Alpha checks compare foreground only. Day/night tints are controlled previews; no OEM compatibility claim.", 16f, 75f, labelPaint)
            renderSizes.forEachIndexed { row, size ->
                val y = headerHeight + row * cellHeight
                withBitmap(renderVisibleAdaptiveLayer(drawable(R.drawable.ic_mirra_foreground), size)) { foreground ->
                    PreviewMask.entries.forEachIndexed { column, mask ->
                        withBitmap(applyMask(foreground, mask)) { maskedForeground ->
                            assertSameAlpha(foreground, maskedForeground, "${mask.label} $size px foreground; tile excluded")
                            withBitmap(maskedTile(maskedForeground, mask, brandBackground)) { preview ->
                                drawCell(canvas, preview, column * cellWidth, y, labels[column], size, labelPaint, cellWidth)
                            }
                        }
                    }
                }
                val palettes = listOf(
                    TintPreview("Day tint", Configuration.UI_MODE_NIGHT_NO, Color.rgb(232, 231, 221), Color.rgb(52, 53, 50)),
                    TintPreview("Night tint", Configuration.UI_MODE_NIGHT_YES, Color.rgb(41, 42, 40), Color.rgb(232, 227, 215)),
                )
                palettes.forEachIndexed { index, palette ->
                    val column = PreviewMask.entries.size + index
                    withBitmap(renderVisibleAdaptiveLayer(monochrome(tintContext(palette.nightMode)).apply { setTint(palette.ink) }, size)) { foreground ->
                        withBitmap(applyMask(foreground, PreviewMask.Circle)) { maskedForeground ->
                            assertSameAlpha(foreground, maskedForeground, "${palette.label} circular preview $size px")
                            withBitmap(maskedTile(maskedForeground, PreviewMask.Circle, palette.background)) { preview ->
                                drawCell(canvas, preview, column * cellWidth, y, labels[column], size, labelPaint, cellWidth)
                            }
                        }
                    }
                }
                val notificationColumn = PreviewMask.entries.size + 2
                withBitmap(render(drawable(R.drawable.ic_launcher), size)) { notification ->
                    withBitmap(composite(notification, Color.rgb(52, 53, 50))) { preview ->
                        drawCell(canvas, preview, notificationColumn * cellWidth, y, labels[notificationColumn], size, labelPaint, cellWidth)
                    }
                }
                val manifestColumn = PreviewMask.entries.size + 3
                withBitmap(render(targetContext.packageManager.getApplicationIcon(targetContext.packageName), size)) { preview ->
                    drawCell(canvas, preview, manifestColumn * cellWidth, y, labels[manifestColumn], size, labelPaint, cellWidth)
                }
            }
            val cacheDirectory = requireNotNull(targetContext.externalCacheDir).canonicalFile
            val directory = File(cacheDirectory, "mirra-brand-validation").canonicalFile
            assertEquals("Evidence directory must remain directly inside app external cache", cacheDirectory, directory.parentFile)
            assertEquals("Evidence must use the dedicated cache subdirectory", "mirra-brand-validation", directory.name)
            assertTrue("Cannot create isolated evidence cache directory", directory.isDirectory || directory.mkdirs())
            val output = File(directory, "mirra-brand-contact-sheet.png").canonicalFile
            assertEquals("Contact sheet must remain inside the evidence directory", directory, output.parentFile)
            output.outputStream().use { stream ->
                assertTrue("PNG contact sheet must encode successfully", sheet.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
            assertTrue("PNG contact sheet must be nonempty", output.length() > 0)
            exportColdStartFramesIfRequested(directory)
        } finally {
            sheet.recycle()
        }
    }

    private fun drawable(resource: Int, context: Context = targetContext): Drawable =
        requireNotNull(context.getDrawable(resource)).mutate()

    /** Optional real-video evidence; accepts only the designated file in the isolated cache. */
    private fun exportColdStartFramesIfRequested(directory: File) {
        val argument = InstrumentationRegistry.getArguments().getString("brandLaunchVideo") ?: return
        val videoDirectory = File(targetContext.cacheDir, "mirra-brand-validation").canonicalFile
        assertEquals("Video directory must remain directly inside app cache", targetContext.cacheDir.canonicalFile, videoDirectory.parentFile)
        val expectedVideo = File(videoDirectory, "cold-start.mp4").canonicalFile
        assertEquals("Cold-start video must remain inside the isolated cache", videoDirectory, expectedVideo.parentFile)
        val suppliedVideo = File(argument).canonicalFile
        assertEquals("brandLaunchVideo must name the designated cache evidence file", expectedVideo, suppliedVideo)
        assertTrue("Cold-start video must exist and be nonempty", expectedVideo.isFile && expectedVideo.length() > 0)
        val retriever = MediaMetadataRetriever()
        try {
            // Pass an already-open descriptor: the media service cannot resolve app-scoped paths.
            FileInputStream(expectedVideo).use { retriever.setDataSource(it.fd) }
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            assertTrue("Cold-start video must cover at least eight seconds", durationMs != null && durationMs >= 8000)
            listOf(0L, 100L, 200L, 300L, 400L, 600L, 800L, 1000L, 1500L, 2000L, 3000L, 4000L, 5000L, 6000L).forEach { timeMs ->
                val frame = requireNotNull(retriever.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)) {
                    "No actual video frame available near ${timeMs}ms"
                }
                withBitmap(frame) { bitmap ->
                    val output = File(directory, "cold-start-${timeMs.toString().padStart(4, '0')}ms.png").canonicalFile
                    assertEquals("Cold-start frame must remain inside the evidence directory", directory, output.parentFile)
                    output.outputStream().use { stream ->
                        assertTrue("Cold-start frame PNG must encode", bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                    }
                    assertTrue("Cold-start frame PNG must be nonempty", output.length() > 0)
                }
            }
        } finally {
            retriever.release()
        }
    }

    private fun monochrome(context: Context): Drawable {
        if (Build.VERSION.SDK_INT >= 33) {
            val icon = drawable(R.mipmap.ic_launcher, context) as AdaptiveIconDrawable
            return requireNotNull(icon.monochrome).mutate()
        }
        return drawable(R.drawable.ic_mirra_monochrome, context)
    }

    private fun tintContext(nightMode: Int): Context {
        val configuration = Configuration(targetContext.resources.configuration)
        configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
        return ContextThemeWrapper(targetContext.createConfigurationContext(configuration), R.style.Theme_Mirra)
    }

    private fun render(drawable: Drawable, size: Int): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))
        }

    /** Adaptive artwork has an 18dp overscan margin around the central 72dp launcher viewport. */
    private fun renderVisibleAdaptiveLayer(drawable: Drawable, size: Int): Bitmap {
        val layerSize = size * 3 / 2
        return withBitmap(render(drawable, layerSize)) { fullLayer ->
            val visible = Bitmap.createBitmap(fullLayer, size / 4, size / 4, size, size)
            assertEquals("Adaptive viewport must not crop artwork before a mask is applied", alphaSum(fullLayer), alphaSum(visible))
            visible
        }
    }

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun alphaSum(bitmap: Bitmap): Long = pixels(bitmap).sumOf { Color.alpha(it).toLong() }

    private fun assertSafeCircle(bitmap: Bitmap, description: String) {
        val center = bitmap.width / 2.0
        val radius = bitmap.width * 33.0 / 108.0
        // One raster pixel allows the vector edge's antialias footprint, not extra design margin.
        val tolerance = 1.0
        val source = pixels(bitmap)
        assertTrue("$description must have artwork", source.any { Color.alpha(it) > 0 })
        source.forEachIndexed { index, pixel ->
            if (Color.alpha(pixel) > 0) {
                val dx = index % bitmap.width + 0.5 - center
                val dy = index / bitmap.width + 0.5 - center
                assertTrue("$description escaped central 66/108 circle at ${index % bitmap.width},${index / bitmap.width}", sqrt(dx * dx + dy * dy) <= radius + tolerance)
            }
        }
    }

    private fun assertTransparentEdges(bitmap: Bitmap, description: String) {
        for (position in 0 until bitmap.width) {
            assertEquals("$description top must be transparent", 0, Color.alpha(bitmap.getPixel(position, 0)))
            assertEquals("$description bottom must be transparent", 0, Color.alpha(bitmap.getPixel(position, bitmap.height - 1)))
            assertEquals("$description left must be transparent", 0, Color.alpha(bitmap.getPixel(0, position)))
            assertEquals("$description right must be transparent", 0, Color.alpha(bitmap.getPixel(bitmap.width - 1, position)))
        }
    }

    private fun assertOneInkAndNegativeSpace(bitmap: Bitmap, ink: Int, description: String, maximumCoverage: Double = 0.4) {
        val source = pixels(bitmap)
        val inkPixels = source.count { Color.alpha(it) > 0 }
        assertTrue("$description must contain ink", inkPixels > 0)
        assertTrue("$description must contain fully opaque ink", source.any { Color.alpha(it) == 255 })
        assertTrue("$description must not be an opaque tile", inkPixels.toDouble() / source.size < maximumCoverage)
        assertTransparentEdges(bitmap, description)
        assertEquals("$description arch must retain transparent negative space", 0, Color.alpha(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)))
        // Opaque samples are authoritative for color. Low-alpha premultiplication can round RGB.
        source.filter { Color.alpha(it) == 255 }.forEach { pixel ->
            assertEquals("$description must use one ink color", ink or Color.BLACK, pixel)
        }
        source.filter { Color.alpha(it) > 0 }.forEach { pixel ->
            val alpha = Color.alpha(pixel)
            val roundingTolerance = 255.0 / alpha + 1.0
            assertTrue("$description red channel has a second ink", abs(Color.red(pixel) - Color.red(ink)) <= roundingTolerance)
            assertTrue("$description green channel has a second ink", abs(Color.green(pixel) - Color.green(ink)) <= roundingTolerance)
            assertTrue("$description blue channel has a second ink", abs(Color.blue(pixel) - Color.blue(ink)) <= roundingTolerance)
        }
    }

    private fun assertSameAlpha(before: Bitmap, after: Bitmap, description: String) {
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        val expected = pixels(before)
        val actual = pixels(after)
        val changed = expected.indices.count { Color.alpha(expected[it]) != Color.alpha(actual[it]) }
        assertEquals("$description must preserve every foreground alpha pixel", 0, changed)
        assertEquals("$description must preserve foreground alpha sum", alphaSum(before), alphaSum(after))
    }

    private fun assertSameRendering(description: String, first: Drawable, second: Drawable) {
        withBitmap(render(first, 256)) { expected ->
            withBitmap(render(second, 256)) { actual ->
                assertTrue("$description must render the declared resource", pixels(expected).contentEquals(pixels(actual)))
            }
        }
    }

    private fun assertThemeResource(context: Context, attribute: Int, resource: Int) {
        val value = TypedValue()
        assertTrue("Starting theme must resolve attribute $attribute", context.theme.resolveAttribute(attribute, value, true))
        assertEquals("Starting theme references the wrong resource for $attribute", resource, value.resourceId)
    }

    private fun applyMask(foreground: Bitmap, mask: PreviewMask): Bitmap =
        Bitmap.createBitmap(foreground.width, foreground.height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.save()
            canvas.clipPath(mask.path(bitmap.width.toFloat()))
            canvas.drawBitmap(foreground, 0f, 0f, null)
            canvas.restore()
        }

    private fun maskedTile(foreground: Bitmap, mask: PreviewMask, background: Int): Bitmap =
        Bitmap.createBitmap(foreground.width, foreground.height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawPath(mask.path(bitmap.width.toFloat()), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background })
            canvas.drawBitmap(foreground, 0f, 0f, null)
        }

    private fun composite(foreground: Bitmap, background: Int): Bitmap =
        Bitmap.createBitmap(foreground.width, foreground.height, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(background)
            Canvas(bitmap).drawBitmap(foreground, 0f, 0f, null)
        }

    private fun drawCell(canvas: Canvas, bitmap: Bitmap, x: Int, y: Int, label: String, size: Int, paint: Paint, cellWidth: Int) {
        canvas.drawText(label, x + 10f, y + 20f, paint)
        canvas.drawText("Rendered ${size}px${if (size > 180) " (thumbnail)" else ""}", x + 10f, y + 41f, paint)
        val displaySize = minOf(size, 180).toFloat()
        val left = x + (cellWidth - displaySize) / 2f
        val top = y + 56f + (180f - displaySize) / 2f
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + displaySize, top + displaySize), Paint(Paint.FILTER_BITMAP_FLAG))
    }

    private fun contrastRatio(first: Int, second: Int): Double {
        fun luminance(color: Int): Double {
            fun channel(value: Int): Double {
                val normalized = value / 255.0
                return if (normalized <= 0.04045) normalized / 12.92 else ((normalized + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(Color.red(color)) + 0.7152 * channel(Color.green(color)) + 0.0722 * channel(Color.blue(color))
        }
        val firstLuminance = luminance(first)
        val secondLuminance = luminance(second)
        return (maxOf(firstLuminance, secondLuminance) + 0.05) / (minOf(firstLuminance, secondLuminance) + 0.05)
    }

    private inline fun <T> withBitmap(bitmap: Bitmap, action: (Bitmap) -> T): T =
        try {
            action(bitmap)
        } finally {
            bitmap.recycle()
        }

    private data class TintPreview(val label: String, val nightMode: Int, val background: Int, val ink: Int)

    private enum class PreviewMask(val label: String) {
        Circle("Circle preview"),
        RoundedSquare("Rounded square preview"),
        Squircle("Squircle preview"),
        Teardrop("Teardrop preview");

        fun path(size: Float): Path = Path().apply {
            when (this@PreviewMask) {
                Circle -> addCircle(size / 2, size / 2, size / 2, Path.Direction.CW)
                RoundedSquare -> addRoundRect(0f, 0f, size, size, size * 0.22f, size * 0.22f, Path.Direction.CW)
                Squircle -> {
                    // Superellipse exponent 4; explicit preview geometry, not an OEM mask claim.
                    for (step in 0..360) {
                        val angle = Math.toRadians(step.toDouble())
                        val cosine = cos(angle)
                        val sine = sin(angle)
                        val x = (size / 2 + size / 2 * Math.copySign(abs(cosine).pow(0.5), cosine)).toFloat()
                        val y = (size / 2 + size / 2 * Math.copySign(abs(sine).pow(0.5), sine)).toFloat()
                        if (step == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                Teardrop -> {
                    moveTo(size / 2, 0f)
                    cubicTo(size * 0.776f, 0f, size, size * 0.224f, size, size / 2)
                    lineTo(size, size)
                    lineTo(size / 2, size)
                    cubicTo(size * 0.224f, size, 0f, size * 0.776f, 0f, size / 2)
                    cubicTo(0f, size * 0.224f, size * 0.224f, 0f, size / 2, 0f)
                    close()
                }
            }
        }
    }
}
