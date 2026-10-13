package dev.inacap.waterctrl

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import dev.inacap.waterctrl.databinding.ActivityActivadorBinding

@SuppressLint("SetTextI18n")
class ActivadorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityActivadorBinding
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var rolUsuario = "PERSONAL"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityActivadorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        rolUsuario = intent.getStringExtra("ROL_USUARIO") ?: "PERSONAL"

        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter

        aplicarSeguridadNodoPlanta()
        iniciarServidorBluetooth()

        // Enviar trama al mover el slider de nivel
        binding.sliderDistancia.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                actualizarTelemetriaPlanta(value)
                transmitirEstado(value, binding.switchReleBomba.isChecked)
            }
        }

        // Enviar trama al cambiar el interruptor de la motobomba
        binding.switchReleBomba.setOnCheckedChangeListener { _, isChecked ->
            if (binding.switchReleBomba.isPressed) {
                transmitirEstado(binding.sliderDistancia.value, isChecked)
            }
            actualizarConsolaSerial(binding.sliderDistancia.value, isChecked)
        }
    }

    private fun iniciarServidorBluetooth() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Active el Bluetooth para enlazar la planta", Toast.LENGTH_LONG).show()
            return
        }

        // Callback de mensajes entrantes desde la Consola SCADA (ej: Paro de emergencia)
        BluetoothManagerHelper.alRecibirMensaje = { trama ->
            runOnUiThread {
                if (trama.contains("ESTOP")) {
                    binding.switchReleBomba.isChecked = false
                    actualizarTelemetriaPlanta(binding.sliderDistancia.value)
                    Toast.makeText(this, "¡PARO DE EMERGENCIA RECIBIDO POR BT!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        BluetoothManagerHelper.alCambiarEstado = { estado ->
            runOnUiThread {
                binding.tvLogSerial.text = "BT: $estado"
            }
        }

        BluetoothManagerHelper.iniciarServidor(adapter)
    }

    private fun transmitirEstado(distancia: Float, bomba: Boolean) {
        val trama = "{\"dist\":%.1f,\"pump\":%b,\"user\":\"%s\"}".format(distancia, bomba, rolUsuario)
        BluetoothManagerHelper.enviarDatos(trama)
        actualizarConsolaSerial(distancia, bomba)
    }

    private fun aplicarSeguridadNodoPlanta() {
        if (rolUsuario == "PERSONAL") {
            binding.switchReleBomba.isEnabled = false
            binding.tvProteccionMotor.text = "Acceso Restringido: Sin privilegios de maniobra (Rol: PERSONAL)"
            binding.tvProteccionMotor.setTextColor("#C62828".toColorInt())
        } else {
            binding.switchReleBomba.isEnabled = true
            binding.tvProteccionMotor.text = "Control Habilitado | Protección anti-cavitación: Activa"
            binding.tvProteccionMotor.setTextColor("#546E7A".toColorInt())
        }
    }

    private fun actualizarTelemetriaPlanta(distanciaCm: Float) {
        binding.tvDistanciaSlider.text = "%.1f cm".format(distanciaCm)
        val porcentajeAgua = ((60.0f - distanciaCm) / 60.0f * 100.0f).coerceIn(0.0f, 100.0f)
        binding.progressNivelAgua.progress = porcentajeAgua.toInt()
        binding.tvPorcentajePlanta.text = "Volumen: %.1f %%".format(porcentajeAgua)

        if (distanciaCm > 25.0f) {
            binding.tvAlertaNivelPlanta.text = "CRÍTICO (> 25 cm)"
            binding.tvAlertaNivelPlanta.setTextColor(Color.RED)
        } else {
            binding.tvAlertaNivelPlanta.text = "NIVEL SEGURO"
            binding.tvAlertaNivelPlanta.setTextColor("#2E7D32".toColorInt())
        }
    }

    private fun actualizarConsolaSerial(distancia: Float, bombaActiva: Boolean) {
        binding.tvLogSerial.text = "TX_BT >> {dist: %.1f, pump: %b}".format(distancia, bombaActiva)
    }

    override fun onDestroy() {
        super.onDestroy()
        BluetoothManagerHelper.cerrarConexion()
    }
}