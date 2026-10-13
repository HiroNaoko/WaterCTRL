package dev.inacap.waterctrl

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

object BluetoothManagerHelper {

    // UUID estándar SPP (Serial Port Profile) compatible con Android, HC-05 y ESP32
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private const val APP_NAME = "WaterCTRL_OT"

    private var socketActivo: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var serverSocket: BluetoothServerSocket? = null

    var alRecibirMensaje: ((String) -> Unit)? = null
    var alCambiarEstado: ((String) -> Unit)? = null

    // Servidor: Escucha conexiones entrantes (se usa en Nodo Planta)
    @SuppressLint("MissingPermission")
    fun iniciarServidor(adapter: BluetoothAdapter) {
        cerrarConexion()
        Thread {
            try {
                alCambiarEstado?.invoke("Esperando conexión de Consola SCADA...")
                serverSocket = adapter.listenUsingRfcommWithServiceRecord(APP_NAME, SPP_UUID)
                val socket = serverSocket?.accept()
                serverSocket?.close() // Ya aceptó un cliente

                if (socket != null) {
                    gestionarSocketConectado(socket)
                }
            } catch (e: IOException) {
                alCambiarEstado?.invoke("Servidor detenido: ${e.message}")
            }
        }.start()
    }

    // Cliente: Se conecta a un dispositivo emparejado (se usa en Consola SCADA)
    @SuppressLint("MissingPermission")
    fun conectarComoCliente(device: BluetoothDevice) {
        cerrarConexion()
        Thread {
            try {
                alCambiarEstado?.invoke("Conectando a ${device.name}...")
                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                gestionarSocketConectado(socket)
            } catch (e: IOException) {
                alCambiarEstado?.invoke("Fallo de conexión: ${e.message}")
                cerrarConexion()
            }
        }.start()
    }

    private fun gestionarSocketConectado(socket: BluetoothSocket) {
        socketActivo = socket
        outputStream = socket.outputStream
        inputStream = socket.inputStream
        alCambiarEstado?.invoke("ENLACE ACTIVO")

        // Hilo de lectura continua
        Thread {
            val buffer = ByteArray(1024)
            while (socketActivo?.isConnected == true) {
                try {
                    val bytes = inputStream?.read(buffer) ?: -1
                    if (bytes > 0) {
                        val mensaje = String(buffer, 0, bytes).trim()
                        alRecibirMensaje?.invoke(mensaje)
                    }
                } catch (e: IOException) {
                    alCambiarEstado?.invoke("Conexión perdida")
                    break
                }
            }
        }.start()
    }

    // Envío de tramas de telemetría o mandos
    fun enviarDatos(trama: String): Boolean {
        return try {
            outputStream?.write((trama + "\n").toByteArray())
            outputStream?.flush()
            true
        } catch (e: IOException) {
            false
        }
    }

    fun cerrarConexion() {
        try {
            inputStream?.close()
            outputStream?.close()
            socketActivo?.close()
            serverSocket?.close()
        } catch (_: IOException) {}
        socketActivo = null
        outputStream = null
        inputStream = null
    }
}