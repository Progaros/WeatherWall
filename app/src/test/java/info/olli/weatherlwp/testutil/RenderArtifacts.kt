package info.olli.weatherlwp.testutil

import android.graphics.Bitmap
import java.io.File

/** Render tests always render and assert; writing PNGs is opt-in via -Drender.artifacts=true. */
object RenderArtifacts {
    val enabled: Boolean = System.getProperty("render.artifacts") == "true"

    fun savePng(file: File, bitmap: Bitmap) {
        if (!enabled) return
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
