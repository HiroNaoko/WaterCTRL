package dev.inacap.waterctrl

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object SecurityHelper {
    private const val HMAC_SECRET = "WaterCTRL_INACAP_2026_SecretKey!"

    fun calcularHmacSha256(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(HMAC_SECRET.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
        mac.init(keySpec)
        val bytes = mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun firmarJson(json: JSONObject): String {
        val sinFirma = json.toString()
        val firma = calcularHmacSha256(sinFirma)
        return json.put("sig", firma).toString()
    }

    fun verificarYExtraer(tramaCruda: String): String? {
        return try {
            val idx = tramaCruda.indexOf(",\"sig\":\"")
            if (idx == -1) return null
            val obj = JSONObject(tramaCruda)
            val sigRecibida = obj.optString("sig", "")
            if (sigRecibida.isBlank()) return null

            val payloadOriginal = tramaCruda.substring(0, idx) + "}"
            if (calcularHmacSha256(payloadOriginal) == sigRecibida) {
                payloadOriginal
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}