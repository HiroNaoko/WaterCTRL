package dev.inacap.waterctrl

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import dev.inacap.waterctrl.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var dbHelper: DatabaseHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Inicializar SQLite.
        dbHelper = DatabaseHelper(this)

        binding.btnIngresar.setOnClickListener {

            val usuario = binding.etUsuario.text.toString().trim()
            val contrasena = binding.etClave.text.toString().trim()

            if (usuario.isEmpty() || contrasena.isEmpty()) {
                Toast.makeText(
                    this,
                    "Por favor ingrese usuario y contraseña",
                    Toast.LENGTH_SHORT
                ).show()

                return@setOnClickListener
            }

            // Validar credenciales.
            val rolDetectado = dbHelper.validarCredenciales(
                usuario,
                contrasena
            )

            if (rolDetectado != null) {

                // Crear una nueva sesión.
                val sesionId = dbHelper.iniciarSesion(usuario)

                if (sesionId == -1L) {
                    Toast.makeText(
                        this,
                        "No se pudo registrar la sesión",
                        Toast.LENGTH_SHORT
                    ).show()

                    return@setOnClickListener
                }

                // Registrar el inicio de sesión en la bitácora.
                dbHelper.registrarEvento(
                    distancia = 0.0f,
                    bombaActiva = false,
                    evento = "INICIO_SESION_EXITOSO",
                    usuario = usuario,
                    sesionId = sesionId
                )

                abrirSelectorDeRol(rolDetectado, usuario, sesionId)

            } else {
                Toast.makeText(
                    this,
                    "Credenciales incorrectas en la base local",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun abrirSelectorDeRol(
        rol: String,
        usuario: String,
        sesionId: Long
    ) {

        val opciones = arrayOf(
            "Nodo Planta (Activador / Gemelo Digital)",
            "Consola SCADA (Monitoreo OT)"
        )

        AlertDialog.Builder(this)
            .setTitle("Selecciona interfaz a probar ($rol)")
            .setItems(opciones) { _, which ->

                val intent = if (which == 0) {
                    Intent(this, ActivadorActivity::class.java)
                } else {
                    Intent(this, MonitoreoActivity::class.java)
                }

                // Enviar los datos de autenticación y sesión.
                intent.putExtra("ROL_USUARIO", rol)
                intent.putExtra("USUARIO", usuario)
                intent.putExtra("SESION_ID", sesionId)

                startActivity(intent)
            }
            .setNegativeButton("Cancelar") { _, _ ->

                // Si cancela la selección, cerrar la sesión creada.
                dbHelper.cerrarSesion(sesionId)

                dbHelper.registrarEvento(
                    distancia = 0.0f,
                    bombaActiva = false,
                    evento = "SELECCION_INTERFAZ_CANCELADA",
                    usuario = usuario,
                    sesionId = sesionId
                )
            }
            .show()
    }

    override fun onDestroy() {
        if (::dbHelper.isInitialized) {
            dbHelper.close()
        }

        super.onDestroy()
    }
}
