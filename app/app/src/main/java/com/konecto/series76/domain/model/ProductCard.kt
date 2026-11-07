package com.konecto.series76.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Actuator card exactly as the backend sends it (`ProductCard` in backend/app/api/schemas.py).
 * The numbers always come from the backend DB, never from the LLM, so the app shows them as-is.
 * It is @Serializable because it is also stored as JSON in Room (an immutable snapshot of the answer).
 */

@Serializable
data class ProductCard(
    @SerialName("part_number") val partNumber: String,
    val role: String = "match",
    val enclosure: String,
    val certifications: List<String> = emptyList(),
    @SerialName("csa_certified") val csaCertified: Boolean = false,
    @SerialName("power_supply") val powerSupply: PowerSupply,
    @SerialName("motor_power_w") val motorPowerW: Int? = null,
    @SerialName("data_available") val dataAvailable: Boolean = true,
    val ratings: List<ServiceRating> = emptyList(),
    val electrical: List<ElectricalPoint> = emptyList(),
    val source: ProductSource? = null,
) {
    val isAlternative: Boolean get() = role == "alternative"
}

@Serializable
data class PowerSupply(
    @SerialName("voltage_v") val voltageV: Int,
    val phase: Int? = null,
    @SerialName("current_type") val currentType: String,
    val label: String,
)

@Serializable
data class ServiceRating(
    /** `on_off`, `modulating` or `general` (3-phase table: one rating without the split). */
    @SerialName("service_type") val serviceType: String,
    @SerialName("torque_inlb") val torqueInLb: Int,
    @SerialName("torque_nm") val torqueNm: Int,
    @SerialName("duty_cycle_pct") val dutyCyclePct: Int,
    @SerialName("cycles_per_hour") val cyclesPerHour: Int? = null,
    @SerialName("starts_per_hour") val startsPerHour: Int? = null,
)

@Serializable
data class ElectricalPoint(
    /** 60, 50 or null (24V models: not frequency-specific). */
    @SerialName("frequency_hz") val frequencyHz: Int? = null,
    @SerialName("operating_speed_s") val operatingSpeedS: Int,
    @SerialName("fla_a") val flaA: Double,
    @SerialName("lra_a") val lraA: Double,
)

@Serializable
data class ProductSource(val table: String, val page: Int)
