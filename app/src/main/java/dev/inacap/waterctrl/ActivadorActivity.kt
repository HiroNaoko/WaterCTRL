package dev.inacap.waterctrl

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import dev.inacap.waterctrl.databinding.ActivityActivadorBinding
import org.json.JSONObject
import java.util.Locale

@SuppressLint("SetTextI18n")
class ActivadorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityActivadorBinding
    private lateinit var dbHelper: DatabaseHelper
    private var bluetoothAdapter: BluetoothAdapter? = null

    private var rolUsuario = "PERSONAL"
    private var usuarioActual = ""
    private var sesionId = -1L
    private var ultimaBombaRegistrada: Boolean? = null

    private var secuenciaTelemetria = 1L
    private var modoActual = "AUTO"
    private var paroEmergenciaActivo = false

    private val permisoBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
            if (concedido) iniciarServidorBluetooth()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityActivadorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dbHelper = DatabaseHelper(this)
        rolUsuario = intent.getStringExtra("ROL_USUARIO") ?: "PERSONAL"
        usuarioActual = intent.getStringExtra("USUARIO") ?: ""
        sesionId = intent.getLongExtra("SESION_ID", -1L)

        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter

        aplicarSeguridadNodoPlanta()
        verificarPermisoYArrancarServidor()

        binding.sliderDistancia.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                actualizarTelemetriaPlanta(value)
                transmitirEstado(value, binding.switchReleBomba.isChecked)
            }
        }

        binding.switchReleBomba.setOnCheckedChangeListener { _, isChecked ->
            if (binding.switchReleBomba.isPressed) {
                transmitirEstado(binding.sliderDistancia.value, isChecked)
            }
        }
    }

    private fun verificarPermisoYArrancarServidor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permisoBluetoothLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                return
            }
        }
        iniciarServidorBluetooth()
    }

    private fun iniciarServidorBluetooth() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Active el Bluetooth para enlazar la planta", Toast.LENGTH_LONG).show()
            return
        }

        BluetoothManagerHelper.alRecibirMensaje = { tramaCruda ->
            val jsonValido = SecurityHelper.verificarYExtraer(tramaCruda)
            val msg = jsonValido?.let { MessageParser.parse(it) }
            runOnUiThread {
                if (msg is WaterCtrlMessage.Command) {
                    when (msg.command) {
                        "ESTOP" -> {
                            paroEmergenciaActivo = true
                            binding.switchReleBomba.isChecked = false
                            registrarEventoLocal("PARO_EMERGENCIA_RECIBIDO")
                            transmitirEstado(binding.sliderDistancia.value, false)
                        }
                        "SET_MODE" -> {
                            modoActual = msg.value ?: "AUTO"
                            transmitirEstado(binding.sliderDistancia.value, binding.switchReleBomba.isChecked)
                        }
                        "SET_PUMP" -> {
                            if (modoActual == "MANUAL" && !paroEmergenciaActivo) {
                                val encender = msg.value == "ON"
                                binding.switchReleBomba.isChecked = encender
                                transmitirEstado(binding.sliderDistancia.value, encender)
                            }
                        }
                    }
                }
            }
        }

        BluetoothManagerHelper.alCambiarEstado = { estado ->
            runOnUiThread {
                binding.tvEstadoBTActivador.text = "Estado BT: $estado"
            }
        }

        BluetoothManagerHelper.iniciarServidor(adapter)
    }

    private fun transmitirEstado(distancia: Float, bomba: Boolean) {
        val porcentaje = ((60.0f - distancia) / 60.0f * 100.0f).coerceIn(0.0f, 100.0f)
        val json = JSONObject()
            .put("v", 1)
            .put("type", "TEL")
            .put("seq", secuenciaTelemetria++)
            .put("distance_cm", String.format(Locale.US, "%.1f", distancia).toDouble())
            .put("level_pct", String.format(Locale.US, "%.1f", porcentaje).toDouble())
            .put("pump_on", bomba)
            .put("mode", modoActual)
            .put("sensor_ok", true)
            .put("estop", paroEmergenciaActivo)

        val tramaFirmada = SecurityHelper.firmarJson(json)
        BluetoothManagerHelper.enviarDatos(tramaFirmada)
        binding.tvLogSerial.text = "TX_HMAC >> $json"

        if (ultimaBombaRegistrada != bomba) {
            val evento = if (bomba) "BOMBA_ACTIVADA_SIMULACION" else "BOMBA_DESACTIVADA_SIMULACION"
            registrarEventoLocal(evento)
            ultimaBombaRegistrada = bomba
        }
    }

    private fun aplicarSeguridadNodoPlanta() {
        binding.switchReleBomba.isEnabled = true
        binding.tvProteccionMotor.text =
            if (rolUsuario == "EMPRESA") "Control habilitado | Administrador" else "Control habilitado | Operador"
        binding.tvProteccionMotor.setTextColor("#546E7A".toColorInt())
    }

    private fun actualizarTelemetriaPlanta(distanciaCm: Float) {
        binding.tvDistanciaSlider.text = String.format(Locale.US, "%.1f cm", distanciaCm)
        val porcentajeAgua = ((60.0f - distanciaCm) / 60.0f * 100.0f).coerceIn(0.0f, 100.0f)
        binding.progressNivelAgua.progress = porcentajeAgua.toInt()
        binding.tvPorcentajePlanta.text = String.format(Locale.US, "Volumen: %.1f %%", porcentajeAgua)

        if (distanciaCm > 25.0f) {
            binding.tvAlertaNivelPlanta.text = "CRÍTICO (> 25 cm)"
            binding.tvAlertaNivelPlanta.setTextColor(Color.RED)
        } else {
            binding.tvAlertaNivelPlanta.text = "NIVEL SEGURO"
            binding.tvAlertaNivelPlanta.setTextColor("#2E7D32".toColorInt())
        }
    }

    private fun registrarEventoLocal(evento: String) {
        dbHelper.registrarEvento(
            distancia = binding.sliderDistancia.value,
            bombaActiva = binding.switchReleBomba.isChecked,
            evento = evento,
            usuario = usuarioActual,
            sesionId = sesionId.takeIf { it > 0 }
        )
    }

    override fun onDestroy() {
        if (isFinishing) {
            registrarEventoLocal("FIN_SESION")
            if (sesionId > 0) dbHelper.cerrarSesion(sesionId)
            BluetoothManagerHelper.cerrarConexion()
            dbHelper.close()
        }
        super.onDestroy()
    }
}