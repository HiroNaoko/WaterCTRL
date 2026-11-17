package dev.inacap.waterctrl

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

object BluetoothManagerHelper {

    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private const val APP_NAME = "WaterCTRL_OT"

    @Volatile private var socketActivo: BluetoothSocket? = null
    @Volatile private var outputStream: OutputStream? = null
    @Volatile private var inputStream: InputStream? = null
    @Volatile private var serverSocket: BluetoothServerSocket? = null

    @Volatile private var ultimoDispositivoCliente: BluetoothDevice? = null
    @Volatile private var reconexionAutomaticaActiva = false
    @Volatile private var reconectandoActualmente = false

    var alRecibirMensaje: ((String) -> Unit)? = null
    var alCambiarEstado: ((String) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun iniciarServidor(adapter: BluetoothAdapter) {
        cerrarConexion()
        Thread {
            try {
                alCambiarEstado?.invoke("Esperando conexión de Consola SCADA...")
                val servidor = adapter.listenUsingRfcommWithServiceRecord(APP_NAME, SPP_UUID)
                serverSocket = servidor
                val socket = servidor.accept()
                servidor.close()
                serverSocket = null
                gestionarSocketConectado(socket)
            } catch (e: IOException) {
                alCambiarEstado?.invoke("Servidor detenido: ${e.message}")
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    fun conectarComoCliente(device: BluetoothDevice) {
        cerrarConexion()
        ultimoDispositivoCliente = device
        reconexionAutomaticaActiva = true
        intentarConexionCliente(device)
    }

    @SuppressLint("MissingPermission")
    private fun intentarConexionCliente(device: BluetoothDevice) {
        Thread {
            var socket: BluetoothSocket? = null
            try {
                alCambiarEstado?.invoke("Conectando a ${device.name ?: device.address}...")
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                reconectandoActualmente = false
                gestionarSocketConectado(socket)
            } catch (e: IOException) {
                try { socket?.close() } catch (_: IOException) {}
                alCambiarEstado?.invoke("Fallo de conexión: ${e.message}")
                programarReconexion()
            }
        }.start()
    }

    private fun programarReconexion() {
        val dispositivo = ultimoDispositivoCliente ?: return
        if (!reconexionAutomaticaActiva || reconectandoActualmente) return

        reconectandoActualmente = true
        Thread {
            try {
                alCambiarEstado?.invoke("Reconectando automáticamente en 4s...")
                Thread.sleep(4000)
                reconectandoActualmente = false
                if (reconexionAutomaticaActiva && socketActivo == null) {
                    intentarConexionCliente(dispositivo)
                }
            } catch (_: InterruptedException) {
                reconectandoActualmente = false
            }
        }.start()
    }

    private fun gestionarSocketConectado(socket: BluetoothSocket) {
        try {
            socketActivo = socket
            outputStream = socket.outputStream
            inputStream = socket.inputStream
            alCambiarEstado?.invoke("ENLACE ACTIVO")

            Thread {
                val buffer = ByteArray(1024)
                val mensajeAcumulado = StringBuilder()
                try {
                    while (socketActivo === socket && socket.isConnected) {
                        val bytes = inputStream?.read(buffer) ?: -1
                        if (bytes == -1) break
                        if (bytes > 0) {
                            val fragmento = String(buffer, 0, bytes, StandardCharsets.UTF_8)
                            mensajeAcumulado.append(fragmento)

                            var posicion = mensajeAcumulado.indexOf("\n")
                            while (posicion != -1) {
                                val mensaje = mensajeAcumulado.substring(0, posicion).trim()
                                mensajeAcumulado.delete(0, posicion + 1)
                                if (mensaje.isNotEmpty()) {
                                    alRecibirMensaje?.invoke(mensaje)
                                }
                                posicion = mensajeAcumulado.indexOf("\n")
                            }
                        }
                    }
                } catch (_: IOException) {
                } finally {
                    desconectarSocket(socket)
                }
            }.start()
        } catch (e: IOException) {
            alCambiarEstado?.invoke("Error al configurar Bluetooth: ${e.message}")
            desconectarSocket(socket)
        }
    }

    fun enviarDatos(trama: String): Boolean {
        return try {
            val salida = outputStream ?: return false
            synchronized(salida) {
                val mensaje = trama.trimEnd('\r', '\n') + "\n"
                salida.write(mensaje.toByteArray(StandardCharsets.UTF_8))
                salida.flush()
            }
            true
        } catch (e: IOException) {
            alCambiarEstado?.invoke("Error al enviar datos: ${e.message}")
            false
        }
    }

    private fun desconectarSocket(socket: BluetoothSocket) {
        if (socketActivo === socket) {
            socketActivo = null
            inputStream = null
            outputStream = null
            try { socket.close() } catch (_: IOException) {}
            alCambiarEstado?.invoke("Conexión perdida")
            programarReconexion()
        }
    }

    fun cerrarConexion() {
        reconexionAutomaticaActiva = false
        reconectandoActualmente = false
        val socket = socketActivo
        val entrada = inputStream
        val salida = outputStream
        val servidor = serverSocket

        socketActivo = null
        inputStream = null
        outputStream = null
        serverSocket = null

        try { entrada?.close() } catch (_: IOException) {}
        try { salida?.close() } catch (_: IOException) {}
        try { socket?.close() } catch (_: IOException) {}
        try { servidor?.close() } catch (_: IOException) {}
    }
}