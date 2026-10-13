package dev.inacap.waterctrl

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import dev.inacap.waterctrl.databinding.ActivityMonitoreoBinding
import org.json.JSONObject

class MonitoreoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMonitoreoBinding
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var rolUsuario = "PERSONAL"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMonitoreoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Recuperar el rol transmitido desde el Login
        rolUsuario = intent.getStringExtra("ROL_USUARIO") ?: "PERSONAL"

        // Inicializar Bluetooth
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter

        // Valores iniciales en la interfaz
        binding.tvDistanciaSCADA.text = "--.- cm"
        binding.tvPorcentajeSCADA.text = "--.- %"
        binding.tvEstadoEnlaceBT.text = "Enlace BT: Desconectado"
        binding.tvEstadoEnlaceBT.setTextColor(Color.parseColor("#C62828"))

        // Aplicar reglas de acceso basadas en roles (RBAC)
        configurarSeguridadSegunRol()

        // Configurar recepción de datos por Bluetooth
        configurarRecepcionBluetooth()

        // Cuadro para seleccionar y conectarse al teléfono de Planta
        mostrarSelectorDispositivos()

        // Control de Modos (Auto / Manual)
        binding.rgModosSCADA.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbManual) {
                if (rolUsuario == "EMPRESA") {
                    binding.switchMandoManual.isEnabled = true
                    Toast.makeText(this, "Modo Manual: Control local habilitado", Toast.LENGTH_SHORT).show()
                    BluetoothManagerHelper.enviarDatos("MODE:MANUAL")
                }
            } else {
                binding.switchMandoManual.isEnabled = false
                binding.switchMandoManual.isChecked = false
                Toast.makeText(this, "Modo Automático: Control por consigna de nivel", Toast.LENGTH_SHORT).show()
                BluetoothManagerHelper.enviarDatos("MODE:AUTO")
            }
        }

        // Switch de Mando Manual de Bomba -> transmite la orden por BT
        binding.switchMandoManual.setOnCheckedChangeListener { _, isChecked ->
            if (binding.switchMandoManual.isPressed) {
                val comando = if (isChecked) "PUMP:ON" else "PUMP:OFF"
                BluetoothManagerHelper.enviarDatos(comando)
                val accion = if (isChecked) "FORZADO ON (Cavitación prevenida)" else "APAGADO"
                Toast.makeText(this, "Comando OT transmitido: $accion", Toast.LENGTH_SHORT).show()
            }
        }

        // Botón Prioritario: Paro de Emergencia E-Stop
        binding.btnParoEmergencia.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("ALERTA CRÍTICA: E-STOP")
                .setMessage("¡Paro de Emergencia disparado desde la consola SCADA!\nSe ha ordenado la desenergización inmediata del circuito de potencia.")
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton("DETENER") { _, _ ->
                    BluetoothManagerHelper.enviarDatos("ESTOP:TRUE")
                    binding.switchMandoManual.isChecked = false
                    Toast.makeText(this, "E-Stop enviado por Bluetooth al Nodo Planta", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun mostrarSelectorDispositivos() {
        val emparejados = bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        if (emparejados.isEmpty()) {
            Toast.makeText(this, "No hay dispositivos Bluetooth vinculados en Android", Toast.LENGTH_LONG).show()
            return
        }

        val nombres = emparejados.map { "${it.name} (${it.address})" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Vincular Nodo Planta")
            .setItems(nombres) { _, which ->
                val seleccionado: BluetoothDevice = emparejados[which]
                BluetoothManagerHelper.conectarComoCliente(seleccionado)
            }
            .setCancelable(false)
            .show()
    }

    private fun configurarRecepcionBluetooth() {
        // Actualiza el indicador de conexión
        BluetoothManagerHelper.alCambiarEstado = { estado ->
            runOnUiThread {
                binding.tvEstadoEnlaceBT.text = "Enlace BT: $estado"
                if (estado.contains("ACTIVO", ignoreCase = true)) {
                    binding.tvEstadoEnlaceBT.setTextColor(Color.parseColor("#2E7D32"))
                } else {
                    binding.tvEstadoEnlaceBT.setTextColor(Color.parseColor("#C62828"))
                }
            }
        }

        // Procesa la telemetría que envía el teléfono Planta
        BluetoothManagerHelper.alRecibirMensaje = { mensaje ->
            runOnUiThread {
                try {
                    val json = JSONObject(mensaje)
                    val distancia = json.optDouble("dist", 0.0).toFloat()
                    val bomba = json.optBoolean("pump", false)

                    // Cálculo del porcentaje útil del estanque
                    val porcentaje = ((60.0f - distancia) / 60.0f * 100.0f).coerceIn(0.0f, 100.0f)

                    binding.tvDistanciaSCADA.text = "%.1f cm".format(distancia)
                    binding.tvPorcentajeSCADA.text = "%.1f %%".format(porcentaje)

                    // Sincronizar el switch si cambió en terreno
                    if (!binding.switchMandoManual.isPressed) {
                        binding.switchMandoManual.isChecked = bomba
                    }
                } catch (_: Exception) {
                    // Si llega texto plano en lugar de JSON
                }
            }
        }
    }

    private fun configurarSeguridadSegunRol() {
        if (rolUsuario == "PERSONAL") {
            // Perfil Operador Básico: Solo lectura telemétrica
            binding.rbManual.isEnabled = false
            binding.switchMandoManual.isEnabled = false
            binding.btnParoEmergencia.isEnabled = false
            binding.btnParoEmergencia.alpha = 0.35f
            Toast.makeText(this, "Perfil PERSONAL: Modo Solo Lectura activo", Toast.LENGTH_LONG).show()
        } else {
            // Perfil Administrador / Empresa: Control total
            Toast.makeText(this, "Perfil EMPRESA: Acceso de Control Habilitado", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        BluetoothManagerHelper.cerrarConexion()
    }
}