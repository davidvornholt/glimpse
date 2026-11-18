package io.github.davidvornholt.glimpse.shared.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * The app's haptics, built from the vibrator's primitives where the phone
 * has them, as Pixels do, and from the view's feedback otherwise. Both
 * follow the system's touch feedback setting.
 */
class Haptics(
    context: Context,
) {
    private val vibrator: Vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val rich =
        vibrator.hasVibrator() &&
            vibrator.areAllPrimitivesSupported(
                Composition.PRIMITIVE_CLICK,
                Composition.PRIMITIVE_TICK,
                Composition.PRIMITIVE_LOW_TICK,
                Composition.PRIMITIVE_QUICK_RISE,
                Composition.PRIMITIVE_THUD,
            )

    private val newFeedback = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    private val segmentTick = if (newFeedback) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
    private val frequentTick = if (newFeedback) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK

    /** The shutter: a firm click with a soft thud behind it. */
    fun shutter(view: View) =
        play(view, HapticFeedbackConstants.CONFIRM) {
            addPrimitive(Composition.PRIMITIVE_CLICK, 1f)
            addPrimitive(Composition.PRIMITIVE_THUD, 0.35f, 30)
        }

    /** A selection changing, such as the mode switch. */
    fun tick(view: View) =
        play(view, segmentTick) {
            addPrimitive(Composition.PRIMITIVE_TICK, 0.7f)
        }

    /** Barely there: an agent just looked at a photo. */
    fun light(view: View) =
        play(view, frequentTick) {
            addPrimitive(Composition.PRIMITIVE_LOW_TICK, 0.6f)
            addPrimitive(Composition.PRIMITIVE_TICK, 0.4f, 60)
        }

    /** Something worked out, such as pairing. */
    fun success(view: View) =
        play(view, HapticFeedbackConstants.CONFIRM) {
            addPrimitive(Composition.PRIMITIVE_QUICK_RISE, 0.6f)
            addPrimitive(Composition.PRIMITIVE_CLICK, 1f, 40)
        }

    fun reject(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
    }

    private fun play(
        view: View,
        fallback: Int,
        compose: Composition.() -> Unit,
    ) {
        if (!rich) {
            view.performHapticFeedback(fallback)
            return
        }
        val effect = VibrationEffect.startComposition().apply(compose).compose()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect)
        }
    }
}
