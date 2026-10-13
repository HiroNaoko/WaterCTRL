package dev.inacap.waterctrl

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "waterctrl_local.db"
        private const val DATABASE_VERSION = 2

        // Tabla Usuarios
        const val TABLA_USUARIOS = "usuarios"
        const val COL_USUARIO_ID = "id"
        const val COL_USUARIO_NOMBRE = "usuario"
        const val COL_USUARIO_PASS = "password"
        const val COL_USUARIO_ROL = "rol"

        // Tabla Bitácora / Telemetría
        const val TABLA_BITACORA = "bitacora"
        const val COL_BITACORA_ID = "id"
        const val COL_BITACORA_TIMESTAMP = "timestamp"
        const val COL_BITACORA_DISTANCIA = "distancia"
        const val COL_BITACORA_BOMBA = "bomba_activa"
        const val COL_BITACORA_EVENTO = "evento"
        const val COL_BITACORA_USUARIO = "usuario_responsable"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val crearTablaUsuarios = """
            CREATE TABLE $TABLA_USUARIOS (
                $COL_USUARIO_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_USUARIO_NOMBRE TEXT UNIQUE NOT NULL,
                $COL_USUARIO_PASS TEXT NOT NULL,
                $COL_USUARIO_ROL TEXT NOT NULL
            )
        """.trimIndent()
        db.execSQL(crearTablaUsuarios)

        val crearTablaBitacora = """
            CREATE TABLE $TABLA_BITACORA (
                $COL_BITACORA_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_BITACORA_TIMESTAMP TEXT NOT NULL,
                $COL_BITACORA_DISTANCIA REAL NOT NULL,
                $COL_BITACORA_BOMBA INTEGER NOT NULL,
                $COL_BITACORA_EVENTO TEXT NOT NULL,
                $COL_BITACORA_USUARIO TEXT NOT NULL
            )
        """.trimIndent()
        db.execSQL(crearTablaBitacora)

        //DEMO
        insertarUsuarioInicial(db, "empresa", "empresa123", "EMPRESA")
        insertarUsuarioInicial(db, "personal", "personal123", "PERSONAL")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLA_USUARIOS")
        db.execSQL("DROP TABLE IF EXISTS $TABLA_BITACORA")
        onCreate(db)
    }

    private fun insertarUsuarioInicial(db: SQLiteDatabase, user: String, pass: String, rol: String) {
        val valores = ContentValues().apply {
            put(COL_USUARIO_NOMBRE, user)
            put(COL_USUARIO_PASS, pass)
            put(COL_USUARIO_ROL, rol)
        }
        db.insert(TABLA_USUARIOS, null, valores)
    }
    fun validarCredenciales(usuario: String, clave: String): String? {
        val db = readableDatabase
        val cursor = db.query(
            TABLA_USUARIOS,
            arrayOf(COL_USUARIO_ROL),
            "$COL_USUARIO_NOMBRE = ? AND $COL_USUARIO_PASS = ?",
            arrayOf(usuario, clave),
            null, null, null
        )

        var rolEncontrado: String? = null
        if (cursor.moveToFirst()) {
            rolEncontrado = cursor.getString(cursor.getColumnIndexOrThrow(COL_USUARIO_ROL))
        }
        cursor.close()
        return rolEncontrado
    }

    fun registrarEvento(distancia: Float, bombaActiva: Boolean, evento: String, usuario: String) {
        val db = writableDatabase
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val ahora = sdf.format(Date())

        val valores = ContentValues().apply {
            put(COL_BITACORA_TIMESTAMP, ahora)
            put(COL_BITACORA_DISTANCIA, distancia)
            put(COL_BITACORA_BOMBA, if (bombaActiva) 1 else 0)
            put(COL_BITACORA_EVENTO, evento)
            put(COL_BITACORA_USUARIO, usuario)
        }
        db.insert(TABLA_BITACORA, null, valores)
    }
}