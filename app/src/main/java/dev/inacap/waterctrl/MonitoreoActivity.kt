package dev.inacap.waterctrl

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import dev.inacap.waterctrl.databinding.ActivityMonitoreoBinding
import org.json.JSONObject
import java.util.Locale

class MonitoreoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMonitoreoBinding
    private lateinit var dbHelper: DatabaseHelper
    private var bluetoothAdapter: BluetoothAdapter? = null

    private var rolUsuario = "PERSONAL"
    private var usuarioActual = ""
    private var sesionId = -1L

    private var distanciaActual = 0f
    private var bombaActual = false
    private var siguienteIdComando = 1L
    private var actualizandoInterfaz = false
    private var modoEcoActivo = false
    private var ultimoEstadoCriticoRegistrado: String? = null
    private var ultimoEstadoBombaTelemetria: Boolean? = null

    // Cálculo de eficiencia energética en tiempo real
    private var ultimoTimestampMs = 0L
    private var energiaBombaWh = 0.0
    private var energiaAhorradaIoTmWh = 0.0
    private val potenciaBombaWatts = 370.0
    private val ahorroNodoEcoMilliWatts = 100.0

    private val handlerEnergia = Handler(Looper.getMainLooper())
    private val runnableEnergia = object : Runnable {
        override fun run() {
            actualizarCalculoEnergetico()
            handlerEnergia.postDelayed(this, 1000L)
        }
    }

    private val permisoBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
            if (concedido) {
                mostrarSelectorDispositivos()
            } else {
                Toast.makeText(
                    this,
                    "Se necesita permiso Bluetooth para conectar con el ESP32",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMonitoreoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dbHelper = DatabaseHelper(this)
        rolUsuario = intent.getStringExtra("ROL_USUARIO")?.uppercase(Locale.ROOT) ?: "PERSONAL"
        usuarioActual = intent.getStringExtra("USUARIO") ?: "desconocido"
        sesionId = intent.getLongExtra("SESION_ID", -1L)

        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter

        binding.tvDistanciaSCADA.text = getString(R.string.distancia_inicial)
        binding.tvPorcentajeSCADA.text = getString(R.string.porcentaje_inicial)
        binding.tvEstadoEnlaceBT.text = getString(R.string.estado_bt_desconectado)
        binding.tvEstadoEnlaceBT.setTextColor("#C62828".toColorInt())

        ultimoTimestampMs = SystemClock.elapsedRealtime()

        configurarSeguridadSegunRol()
        configurarRecepcionBluetooth()
        configurarControles()
        solicitarPermisoBluetooth()
    }

    override fun onResume() {
        super.onResume()
        ultimoTimestampMs = SystemClock.elapsedRealtime()
        handlerEnergia.post(runnableEnergia)
    }

    override fun onPause() {
        super.onPause()
        handlerEnergia.removeCallbacks(runnableEnergia)
    }

    private fun actualizarCalculoEnergetico() {
        val ahora = SystemClock.elapsedRealtime()
        val deltaHoras = (ahora - ultimoTimestampMs) / 3_600_000.0
        ultimoTimestampMs = ahora

        if (deltaHoras <= 0.0) return

        if (bombaActual) {
            energiaBombaWh += potenciaBombaWatts * deltaHoras
        }
        if (modoEcoActivo) {
            energiaAhorradaIoTmWh += ahorroNodoEcoMilliWatts * deltaHoras
        }

        binding.tvMetricasEnergia.text = getString(
            R.string.scada_energia_consumo_formato,
            energiaBombaWh,
            energiaAhorradaIoTmWh
        )
    }

    private fun aplicarCambioModoEnergia(activarEco: Boolean) {
        if (modoEcoActivo == activarEco) return
        modoEcoActivo = activarEco
        val nuevoModoEnergia = if (modoEcoActivo) "ECO" else "NORMAL"

        actualizandoInterfaz = true
        binding.switchModoEco.isChecked = modoEcoActivo
        actualizandoInterfaz = false

        binding.tvFrecuenciaTelemetria.text = if (modoEcoActivo) {
            getString(R.string.scada_energia_telemetria_eco)
        } else {
            getString(R.string.scada_energia_telemetria_normal)
        }

        if (enviarComando("SET_ENERGY_MODE", nuevoModoEnergia)) {
            registrarEventoLocal("MODO_ENERGIA_$nuevoModoEnergia")
            Toast.makeText(this, "Modo energético: $nuevoModoEnergia", Toast.LENGTH_SHORT).show()
        }
    }

    private fun solicitarPermisoBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permisoBluetoothLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                return
            }
        }
        mostrarSelectorDispositivos()
    }

    @SuppressLint("MissingPermission")
    private fun mostrarSelectorDispositivos() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            Toast.makeText(this, "Activa el Bluetooth del dispositivo para continuar", Toast.LENGTH_LONG).show()
            return
        }

        val emparejados = adapter.bondedDevices?.toList() ?: emptyList()
        if (emparejados.isEmpty()) {
            Toast.makeText(this, "No hay dispositivos Bluetooth vinculados en Android", Toast.LENGTH_LONG).show()
            return
        }

        val nombres = emparejados.map { "${it.name ?: "Dispositivo"} (${it.address})" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Vincular Nodo Planta (ESP32_ACTUADORES)")
            .setItems(nombres) { _, which ->
                val seleccionado: BluetoothDevice = emparejados[which]
                BluetoothManagerHelper.conectarComoCliente(seleccionado)
            }
            .setCancelable(false)
            .show()
    }

    private fun configurarControles() {
        binding.tvEstadoEnlaceBT.setOnClickListener {
            aplicarCambioModoEnergia(!modoEcoActivo)
        }

        binding.switchModoEco.setOnCheckedChangeListener { _, isChecked ->
            if (actualizandoInterfaz) return@setOnCheckedChangeListener
            aplicarCambioModoEnergia(isChecked)
        }

        binding.btnAbrirHistorial.visibility = if (rolUsuario == "EMPRESA") View.VISIBLE else View.GONE
        binding.btnAbrirHistorial.setOnClickListener {
            if (rolUsuario != "EMPRESA") return@setOnClickListener
            val intent = Intent(this, HistorialActivity::class.java)
            intent.putExtra("USUARIO", usuarioActual)
            startActivity(intent)
        }

        binding.rgModosSCADA.setOnCheckedChangeListener { _, checkedId ->
            if (actualizandoInterfaz) return@setOnCheckedChangeListener
            val modo = when (checkedId) {
                R.id.rbManual -> "MANUAL"
                R.id.rbAuto -> "AUTO"
                else -> return@setOnCheckedChangeListener
            }

            binding.switchMandoManual.isEnabled = (modo == "MANUAL")
            if (modo == "AUTO") {
                actualizandoInterfaz = true
                binding.switchMandoManual.isChecked = false
                actualizandoInterfaz = false
            }

            if (enviarComando("SET_MODE", modo)) {
                registrarEventoLocal("SOLICITUD_CAMBIO_MODO_$modo")
                Toast.makeText(this, "Modo seleccionado: $modo", Toast.LENGTH_SHORT).show()
            }
        }

        binding.switchMandoManual.setOnCheckedChangeListener { _, isChecked ->
            if (actualizandoInterfaz || !binding.switchMandoManual.isPressed) return@setOnCheckedChangeListener
            if (!binding.rbManual.isChecked) {
                actualizandoInterfaz = true
                binding.switchMandoManual.isChecked = false
                actualizandoInterfaz = false
                return@setOnCheckedChangeListener
            }

            val valor = if (isChecked) "ON" else "OFF"
            if (enviarComando("SET_PUMP", valor)) {
                registrarEventoLocal(if (isChecked) "SOLICITUD_ENCENDER_BOMBA" else "SOLICITUD_APAGAR_BOMBA")
            }
        }

        binding.btnParoEmergencia.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("ALERTA CRÍTICA: E-STOP")
                .setMessage("¿Ejecutar paro de emergencia inmediato en la planta?")
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton("DETENER") { _, _ ->
                    if (enviarComando("ESTOP")) {
                        registrarEventoLocal("SOLICITUD_PARO_EMERGENCIA")
                        actualizandoInterfaz = true
                        binding.switchMandoManual.isChecked = false
                        actualizandoInterfaz = false
                        Toast.makeText(this, "Orden E-STOP enviada", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun enviarComando(comando: String, valor: String? = null): Boolean {
        val json = JSONObject()
            .put("v", 1)
            .put("type", "CMD")
            .put("id", siguienteIdComando++)
            .put("cmd", comando)
        if (valor != null) {
            json.put("value", valor)
        }
        val tramaFirmada = SecurityHelper.firmarJson(json)
        return BluetoothManagerHelper.enviarDatos(tramaFirmada)
    }

    private fun registrarEventoLocal(evento: String) {
        if (!::dbHelper.isInitialized) return
        dbHelper.registrarEvento(
            distancia = distanciaActual,
            bombaActiva = bombaActual,
            evento = evento,
            usuario = usuarioActual,
            sesionId = if (sesionId > 0) sesionId else null
        )
    }

    private fun configurarRecepcionBluetooth() {
        BluetoothManagerHelper.alCambiarEstado = { estado ->
            runOnUiThread {
                binding.tvEstadoEnlaceBT.text = getString(R.string.estado_bt_formato, estado)
                if (estado.contains("ACTIVO", ignoreCase = true)) {
                    binding.tvEstadoEnlaceBT.setTextColor("#2E7D32".toColorInt())
                    dbHelper.sincronizarPendientesConNube()
                } else {
                    binding.tvEstadoEnlaceBT.setTextColor("#C62828".toColorInt())
                }
            }
        }

        BluetoothManagerHelper.alRecibirMensaje = { mensajeCrudo ->
            val jsonValido = SecurityHelper.verificarYExtraer(mensajeCrudo)
            val mensajeProcesado = jsonValido?.let { MessageParser.parse(it) }

            runOnUiThread {
                when (mensajeProcesado) {
                    is WaterCtrlMessage.Telemetry -> actualizarTelemetria(mensajeProcesado)
                    is WaterCtrlMessage.Acknowledgement -> {
                        if (!mensajeProcesado.success) {
                            Toast.makeText(this, "Comando rechazado: ${mensajeProcesado.code}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    is WaterCtrlMessage.Event -> {
                        registrarEventoLocal("ESP32_${mensajeProcesado.event}")
                        Toast.makeText(this, "Evento planta: ${mensajeProcesado.event}", Toast.LENGTH_SHORT).show()
                    }
                    else -> {}
                }
            }
        }
    }

    private fun actualizarTelemetria(telemetria: WaterCtrlMessage.Telemetry) {
        distanciaActual = telemetria.distanceCm.toFloat()
        bombaActual = telemetria.pumpOn

        binding.tvDistanciaSCADA.text = String.format(Locale.getDefault(), "%.1f cm", telemetria.distanceCm)
        binding.tvPorcentajeSCADA.text = String.format(Locale.getDefault(), "%.1f %%", telemetria.levelPct)

        if (ultimoEstadoBombaTelemetria != null && ultimoEstadoBombaTelemetria != telemetria.pumpOn) {
            val evBomba = if (telemetria.pumpOn) "BOMBA_ENCENDIDA_AUTO" else "BOMBA_APAGADA_AUTO"
            registrarEventoLocal(evBomba)
        }
        ultimoEstadoBombaTelemetria = telemetria.pumpOn

        val estadoNivelActual = when {
            telemetria.emergencyStop -> "ESTOP"
            !telemetria.sensorOk -> "FALLA_SENSOR"
            telemetria.distanceCm > 25.0 -> "CRITICO_BAJO"
            telemetria.distanceCm < 8.0 -> "CRITICO_LLENO"
            else -> "NORMAL"
        }

        if (ultimoEstadoCriticoRegistrado != estadoNivelActual) {
            when (estadoNivelActual) {
                "CRITICO_BAJO" -> registrarEventoLocal("ALERTA_NIVEL_CRITICO_BAJO")
                "CRITICO_LLENO" -> registrarEventoLocal("ALERTA_NIVEL_LLENO_MAXIMO")
                "FALLA_SENSOR" -> registrarEventoLocal("ALERTA_FALLA_SENSOR_HCSR04")
                "NORMAL" -> if (ultimoEstadoCriticoRegistrado != null) {
                    registrarEventoLocal("NIVEL_AGUA_ESTABILIZADO")
                }
            }
            ultimoEstadoCriticoRegistrado = estadoNivelActual
        }

        when (estadoNivelActual) {
            "ESTOP" -> {
                binding.tvSemaforoAlerta.text = getString(R.string.scada_estado_estop)
                binding.tvSemaforoAlerta.setTextColor("#C62828".toColorInt())
                binding.tvSemaforoAlerta.setBackgroundColor("#FFEBEE".toColorInt())
            }
            "FALLA_SENSOR" -> {
                binding.tvSemaforoAlerta.text = getString(R.string.scada_estado_falla_sensor)
                binding.tvSemaforoAlerta.setTextColor("#E65100".toColorInt())
                binding.tvSemaforoAlerta.setBackgroundColor("#FFF3E0".toColorInt())
            }
            "CRITICO_BAJO" -> {
                binding.tvSemaforoAlerta.text = getString(R.string.scada_estado_critico)
                binding.tvSemaforoAlerta.setTextColor("#C62828".toColorInt())
                binding.tvSemaforoAlerta.setBackgroundColor("#FFEBEE".toColorInt())
            }
            "CRITICO_LLENO" -> {
                binding.tvSemaforoAlerta.text = getString(R.string.scada_estado_lleno)
                binding.tvSemaforoAlerta.setTextColor("#0277BD".toColorInt())
                binding.tvSemaforoAlerta.setBackgroundColor("#E1F5FE".toColorInt())
            }
            else -> {
                binding.tvSemaforoAlerta.text = getString(R.string.scada_estado_seguro)
                binding.tvSemaforoAlerta.setTextColor("#2E7D32".toColorInt())
                binding.tvSemaforoAlerta.setBackgroundColor("#E8F5E9".toColorInt())
            }
        }

        actualizandoInterfaz = true
        when (telemetria.mode) {
            "AUTO" -> {
                binding.rgModosSCADA.check(R.id.rbAuto)
                binding.switchMandoManual.isEnabled = false
            }
            "MANUAL" -> {
                binding.rgModosSCADA.check(R.id.rbManual)
                binding.switchMandoManual.isEnabled = true
            }
        }
        binding.switchMandoManual.isChecked = telemetria.pumpOn
        actualizandoInterfaz = false
    }

    private fun configurarSeguridadSegunRol() {
        binding.rbAuto.isEnabled = true
        binding.rbManual.isEnabled = true
        binding.rgModosSCADA.isEnabled = true
        binding.switchMandoManual.isEnabled = binding.rbManual.isChecked
        binding.btnParoEmergencia.isEnabled = true
        binding.btnParoEmergencia.alpha = 1f
        binding.btnAbrirHistorial.visibility = if (rolUsuario == "EMPRESA") View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        handlerEnergia.removeCallbacks(runnableEnergia)
        if (isFinishing && sesionId > 0) {
            registrarEventoLocal("FIN_SESION")
            dbHelper.cerrarSesion(sesionId)
        }
        BluetoothManagerHelper.alRecibirMensaje = null
        BluetoothManagerHelper.alCambiarEstado = null
        BluetoothManagerHelper.cerrarConexion()
        if (::dbHelper.isInitialized) dbHelper.close()
        super.onDestroy()
    }
}