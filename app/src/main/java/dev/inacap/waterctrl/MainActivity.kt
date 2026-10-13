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

        // Inicializar el gestor de SQLite local
        dbHelper = DatabaseHelper(this)

        binding.btnIngresar.setOnClickListener {
            val usuario = binding.etUsuario.text.toString().trim()
            val contrasena = binding.etClave.text.toString().trim()

            if (usuario.isEmpty() || contrasena.isEmpty()) {
                Toast.makeText(this, "Por favor ingrese usuario y contraseña", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Consulta directa a la base de datos SQLite local
            val rolDetectado = dbHelper.validarCredenciales(usuario, contrasena)

            if (rolDetectado != null) {
                // Registro de auditoría local: Login exitoso
                dbHelper.registrarEvento(
                    distancia = 0.0f,
                    bombaActiva = false,
                    evento = "INICIO_SESION_EXITOSO",
                    usuario = usuario
                )
                abrirSelectorDeRol(rolDetectado)
            } else {
                Toast.makeText(this, "Credenciales incorrectas en la base local", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun abrirSelectorDeRol(rol: String) {
        val opciones = arrayOf(
            "Nodo Planta (Activador / Gemelo Digital)",
            "Consola SCADA (Monitoreo OT)"
        )

        AlertDialog.Builder(this)
            .setTitle("Selecciona interfaz a probar ($rol)")
            .setItems(opciones) { _, which ->
                if (which == 0) {
                    val intent = Intent(this, ActivadorActivity::class.java)
                    intent.putExtra("ROL_USUARIO", rol)
                    startActivity(intent)
                } else {
                    val intent = Intent(this, MonitoreoActivity::class.java)
                    intent.putExtra("ROL_USUARIO", rol)
                    startActivity(intent)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}