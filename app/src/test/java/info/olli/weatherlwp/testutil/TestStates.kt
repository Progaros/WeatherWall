package info.olli.weatherlwp.testutil

import info.olli.weatherlwp.simulation.SimulationState
import info.olli.weatherlwp.simulation.inHourWindow

/**
 * [base] moved to [hours] with every time-derived field set the way SimulationController
 * produces it once time smoothing has settled. Setting only `timeOfDayHours` leaves
 * `smoothedTimeOfDayHours` (which the sky reads) at the base value, e.g. a noon sky at night.
 * Day flags are derived from [base]'s sunrise/sunset, so override those in [base], not afterwards.
 */
fun stateAt(hours: Float, base: SimulationState = SimulationState.default()): SimulationState {
    val sunrise = base.sunriseHours
    val sunset = base.sunsetHours
    return base.copy(
        timeOfDayHours = hours,
        smoothedTimeOfDayHours = hours,
        dayProgress = hours / 24f,
        smoothedProgress = hours / 24f,
        isNight = hours < sunrise || hours >= sunset,
        isDawn = hours >= sunrise && hours < sunrise + 1.5f,
        isDusk = hours >= sunset - 3f && hours < sunset,
        isGoldenHour = inHourWindow(hours, sunrise, sunrise + 0.75f) ||
            inHourWindow(hours, sunset - 0.75f, sunset),
        isBlueHour = inHourWindow(hours, sunrise - 0.5f, sunrise) ||
            inHourWindow(hours, sunset, sunset + 0.5f)
    )
}
