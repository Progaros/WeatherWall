package info.olli.weatherlwp.rendering

import android.graphics.Canvas
import android.util.Log
import android.view.SurfaceHolder

/**
 * Shared canvas acquisition helper.
 * Attempts hardware canvas first, falls back to software canvas.
 *
 * @param holder The SurfaceHolder to acquire a canvas from.
 * @param preferHardware Whether to attempt hardware canvas first.
 * @param onHardwareFailed Callback invoked when hardware canvas fails (to update caller's flag).
 * @return The acquired Canvas, or null if both attempts fail.
 */
object CanvasHelper {
    private const val TAG = "CanvasHelper"

    fun acquire(
        holder: SurfaceHolder,
        preferHardware: Boolean,
        onHardwareFailed: (() -> Unit)? = null
    ): Canvas? {
        if (preferHardware) {
            try {
                return holder.lockHardwareCanvas()
            } catch (e: Throwable) {
                onHardwareFailed?.invoke()
                Log.w(TAG, "lockHardwareCanvas failed, falling back to lockCanvas", e)
            }
        }
        return try {
            holder.lockCanvas()
        } catch (e: Throwable) {
            Log.e(TAG, "lockCanvas failed", e)
            null
        }
    }
}
