package dev.inacap.waterctrl
import org.json.JSONObject
import java.util.Locale

/**
 * Mensajes admitidos por el protocolo WaterCTRL v1.
 */
sealed class WaterCtrlMessage {

    data class Command(
        val id: Long,
        val command: String,
        val value: String?
    ) : WaterCtrlMessage()

    data class Acknowledgement(
        val id: Long,
        val success: Boolean,
        val code: String,
        val pumpOn: Boolean?,
        val mode: String?
    ) : WaterCtrlMessage()

    data class Telemetry(
        val sequence: Long,
        val distanceCm: Double,
        val levelPct: Double,
        val pumpOn: Boolean,
        val mode: String,
        val sensorOk: Boolean,
        val emergencyStop: Boolean
    ) : WaterCtrlMessage()

    data class Event(
        val sequence: Long,
        val event: String,
        val source: String
    ) : WaterCtrlMessage()
}

/**
 * Procesador de mensajes JSON de WaterCTRL.
 *
 * IMPORTANTE:
 * Esta clase valida la estructura y los valores del mensaje.
 * La autenticación criptográfica se implementará posteriormente,
 * antes de entregar los mensajes a este procesador.
 */
object MessageParser {

    private const val PROTOCOL_VERSION = 1
    private const val MAX_MESSAGE_LENGTH = 2048

    private val allowedCommands = setOf(
        "GET_STATUS",
        "SET_MODE",
        "SET_PUMP",
        "ESTOP",
        "RESET_ESTOP",
        "PING",
        "SENS_READ",
        "SET_ENERGY_MODE"
    )

    private val allowedSources = setOf(
        "AUTO",
        "MANUAL",
        "ANDROID",
        "SENSOR",
        "SYSTEM"
    )

    fun parse(rawMessage: String): WaterCtrlMessage? {

        if (rawMessage.length > MAX_MESSAGE_LENGTH) return null

        val content = rawMessage.trim()

        if (content.isEmpty()) return null

        return try {

            val json = JSONObject(content)

            if (json.getInt("v") != PROTOCOL_VERSION) {
                return null
            }

            when (
                json.getString("type").uppercase(Locale.ROOT)
            ) {

                "CMD" -> parseCommand(json)

                "ACK" -> parseAcknowledgement(json)

                "TEL" -> parseTelemetry(json)

                "EVT" -> parseEvent(json)

                else -> null
            }

        } catch (_: Exception) {
            null
        }
    }

    private fun parseCommand(
        json: JSONObject
    ): WaterCtrlMessage.Command? {

        val id = json.requiredLong("id") ?: return null

        val command = json.requiredString("cmd")
            ?.uppercase(Locale.ROOT) ?: return null

        if (id <= 0 || command !in allowedCommands) {
            return null
        }

        val value = json.optionalString("value")
            ?.uppercase(Locale.ROOT)

        val validValue = when (command) {

            "SET_MODE" ->
                value == "AUTO" || value == "MANUAL"

            "SET_PUMP" ->
                value == "ON" || value == "OFF"

            "SET_ENERGY_MODE" ->
                value == "NORMAL" || value == "ECO"

            else -> value == null
        }

        if (!validValue) return null

        return WaterCtrlMessage.Command(
            id = id,
            command = command,
            value = value
        )
    }

    private fun parseAcknowledgement(
        json: JSONObject
    ): WaterCtrlMessage.Acknowledgement? {

        val id = json.requiredLong("id") ?: return null
        val success = json.requiredBoolean("ok") ?: return null
        val code = json.requiredString("code") ?: return null

        if (id <= 0 || code.length > 32) return null

        val pumpOn = json.optionalBoolean("pump_on")

        val mode = json.optionalString("mode")
            ?.uppercase(Locale.ROOT)

        if (mode != null && mode !in setOf("AUTO", "MANUAL")) {
            return null
        }

        return WaterCtrlMessage.Acknowledgement(
            id = id,
            success = success,
            code = code,
            pumpOn = pumpOn,
            mode = mode
        )
    }

    private fun parseTelemetry(
        json: JSONObject
    ): WaterCtrlMessage.Telemetry? {

        val sequence = json.requiredLong("seq") ?: return null
        val distance = json.requiredDouble("distance_cm") ?: return null
        val percentage = json.requiredDouble("level_pct") ?: return null
        val pump = json.requiredBoolean("pump_on") ?: return null
        val mode = json.requiredString("mode")
            ?.uppercase(Locale.ROOT) ?: return null

        val sensorOk = json.requiredBoolean("sensor_ok") ?: return null
        val estop = json.requiredBoolean("estop") ?: return null

        if (
            sequence <= 0 ||
            distance !in 0.0..400.0 ||
            percentage !in 0.0..100.0 ||
            mode !in setOf("AUTO", "MANUAL")
        ) {
            return null
        }

        return WaterCtrlMessage.Telemetry(
            sequence = sequence,
            distanceCm = distance,
            levelPct = percentage,
            pumpOn = pump,
            mode = mode,
            sensorOk = sensorOk,
            emergencyStop = estop
        )
    }

    private fun parseEvent(
        json: JSONObject
    ): WaterCtrlMessage.Event? {

        val sequence = json.requiredLong("seq") ?: return null
        val event = json.requiredString("event") ?: return null
        val source = json.requiredString("source")
            ?.uppercase(Locale.ROOT) ?: return null

        if (
            sequence <= 0 ||
            event.length > 48 ||
            !event.matches(Regex("[A-Z0-9_]+")) ||
            source !in allowedSources
        ) {
            return null
        }

        return WaterCtrlMessage.Event(
            sequence = sequence,
            event = event,
            source = source
        )
    }

    private fun JSONObject.requiredString(key: String): String? {
        if (!has(key) || isNull(key)) return null

        val value = opt(key) as? String ?: return null

        return value.takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optionalString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return opt(key) as? String
    }

    private fun JSONObject.requiredLong(key: String): Long? {
        if (!has(key)) return null

        val value = opt(key) as? Number ?: return null
        val number = value.toDouble()

        if (!number.isFinite() ||
            number % 1.0 != 0.0 ||
            number > Long.MAX_VALUE.toDouble() ||
            number < Long.MIN_VALUE.toDouble()
        ) return null

        return number.toLong()
    }

    private fun JSONObject.requiredDouble(key: String): Double? {
        if (!has(key)) return null

        val value = opt(key) as? Number ?: return null
        val number = value.toDouble()

        return number.takeIf { it.isFinite() }
    }

    private fun JSONObject.requiredBoolean(key: String): Boolean? {
        if (!has(key)) return null
        return opt(key) as? Boolean
    }

    private fun JSONObject.optionalBoolean(key: String): Boolean? {
        if (!has(key) || isNull(key)) return null
        return opt(key) as? Boolean
    }
}
