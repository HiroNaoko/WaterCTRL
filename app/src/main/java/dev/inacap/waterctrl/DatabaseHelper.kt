package dev.inacap.waterctrl

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.firebase.database.FirebaseDatabase
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RegistroHistorial(
    val id: Long,
    val fechaHora: String,
    val distancia: Float,
    val bombaActiva: Boolean,
    val evento: String,
    val usuario: String,
    val sesionId: Long?,
    val sincronizado: Boolean = false
)

class DatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "waterctrl_local.db"
        private const val DATABASE_VERSION = 5

        const val TABLA_USUARIOS = "usuarios"
        const val COL_USUARIO_ID = "id"
        const val COL_USUARIO_NOMBRE = "usuario"
        const val COL_USUARIO_PASS = "password_hash"
        const val COL_USUARIO_ROL = "rol"

        const val TABLA_BITACORA = "bitacora"
        const val COL_BITACORA_ID = "id"
        const val COL_BITACORA_TIMESTAMP = "timestamp"
        const val COL_BITACORA_DISTANCIA = "distancia"
        const val COL_BITACORA_BOMBA = "bomba_activa"
        const val COL_BITACORA_EVENTO = "evento"
        const val COL_BITACORA_USUARIO = "usuario_responsable"
        const val COL_BITACORA_SESION_ID = "sesion_id"
        const val COL_BITACORA_SINCRONIZADO = "sincronizado"

        const val TABLA_SESIONES = "sesiones"
        const val COL_SESION_ID = "id"
        const val COL_SESION_USUARIO = "usuario"
        const val COL_SESION_INICIO = "inicio"
        const val COL_SESION_FIN = "fin"

        fun hashSha256(input: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        crearTablaUsuarios(db)
        crearTablaBitacora(db)
        crearTablaSesiones(db)
        insertarUsuarioInicial(db, "empresa", hashSha256("empresa123"), "EMPRESA")
        insertarUsuarioInicial(db, "personal", hashSha256("personal123"), "PERSONAL")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 4) {
            crearTablaSesiones(db)
            crearTablaBitacora(db)
            if (!existeColumna(db, COL_BITACORA_SESION_ID)) {
                db.execSQL("ALTER TABLE $TABLA_BITACORA ADD COLUMN $COL_BITACORA_SESION_ID INTEGER")
            }
        }
        if (oldVersion < 5) {
            if (!existeColumna(db, COL_BITACORA_SINCRONIZADO)) {
                db.execSQL("ALTER TABLE $TABLA_BITACORA ADD COLUMN $COL_BITACORA_SINCRONIZADO INTEGER DEFAULT 0")
            }
        }
    }

    private fun crearTablaUsuarios(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLA_USUARIOS (
                $COL_USUARIO_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_USUARIO_NOMBRE TEXT UNIQUE NOT NULL,
                $COL_USUARIO_PASS TEXT NOT NULL,
                $COL_USUARIO_ROL TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun crearTablaBitacora(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLA_BITACORA (
                $COL_BITACORA_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_BITACORA_TIMESTAMP TEXT NOT NULL,
                $COL_BITACORA_DISTANCIA REAL NOT NULL,
                $COL_BITACORA_BOMBA INTEGER NOT NULL,
                $COL_BITACORA_EVENTO TEXT NOT NULL,
                $COL_BITACORA_USUARIO TEXT NOT NULL,
                $COL_BITACORA_SESION_ID INTEGER,
                $COL_BITACORA_SINCRONIZADO INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
    }

    private fun crearTablaSesiones(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLA_SESIONES (
                $COL_SESION_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_SESION_USUARIO TEXT NOT NULL,
                $COL_SESION_INICIO TEXT NOT NULL,
                $COL_SESION_FIN TEXT
            )
            """.trimIndent()
        )
    }

    private fun existeColumna(db: SQLiteDatabase, columna: String): Boolean {
        db.rawQuery("PRAGMA table_info($TABLA_BITACORA)", null).use { cursor ->
            val indiceNombre = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(indiceNombre) == columna) return true
            }
        }
        return false
    }

    private fun obtenerFechaActual(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date())
    }

    private fun insertarUsuarioInicial(db: SQLiteDatabase, user: String, passHash: String, rol: String) {
        val valores = ContentValues().apply {
            put(COL_USUARIO_NOMBRE, user)
            put(COL_USUARIO_PASS, passHash)
            put(COL_USUARIO_ROL, rol)
        }
        db.insertWithOnConflict(TABLA_USUARIOS, null, valores, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun validarCredenciales(usuario: String, claveTextoPlano: String): String? {
        val claveHash = hashSha256(claveTextoPlano)
        readableDatabase.query(
            TABLA_USUARIOS,
            arrayOf(COL_USUARIO_ROL),
            "$COL_USUARIO_NOMBRE = ? AND $COL_USUARIO_PASS = ?",
            arrayOf(usuario, claveHash),
            null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndexOrThrow(COL_USUARIO_ROL))
            }
        }
        return null
    }

    fun obtenerRolUsuario(usuario: String): String? {
        readableDatabase.query(
            TABLA_USUARIOS,
            arrayOf(COL_USUARIO_ROL),
            "$COL_USUARIO_NOMBRE = ?",
            arrayOf(usuario),
            null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndexOrThrow(COL_USUARIO_ROL))
            }
        }
        return null
    }

    fun iniciarSesion(usuario: String): Long {
        val valores = ContentValues().apply {
            put(COL_SESION_USUARIO, usuario)
            put(COL_SESION_INICIO, obtenerFechaActual())
            putNull(COL_SESION_FIN)
        }
        return writableDatabase.insert(TABLA_SESIONES, null, valores)
    }

    fun cerrarSesion(sesionId: Long): Boolean {
        val valores = ContentValues().apply { put(COL_SESION_FIN, obtenerFechaActual()) }
        return writableDatabase.update(
            TABLA_SESIONES,
            valores,
            "$COL_SESION_ID = ? AND $COL_SESION_FIN IS NULL",
            arrayOf(sesionId.toString())
        ) > 0
    }

    fun registrarEvento(
        distancia: Float,
        bombaActiva: Boolean,
        evento: String,
        usuario: String,
        sesionId: Long? = null
    ) {
        val valores = ContentValues().apply {
            put(COL_BITACORA_TIMESTAMP, obtenerFechaActual())
            put(COL_BITACORA_DISTANCIA, distancia)
            put(COL_BITACORA_BOMBA, if (bombaActiva) 1 else 0)
            put(COL_BITACORA_EVENTO, evento)
            put(COL_BITACORA_USUARIO, usuario)
            put(COL_BITACORA_SINCRONIZADO, 0)
            if (sesionId != null) put(COL_BITACORA_SESION_ID, sesionId) else putNull(COL_BITACORA_SESION_ID)
        }
        writableDatabase.insert(TABLA_BITACORA, null, valores)
        sincronizarPendientesConNube()
    }

    // Sincronizacion hacia almacenamiento externo (Firebase Realtime Database)
    fun sincronizarPendientesConNube() {
        try {
            val pendientes = obtenerRegistrosNoSincronizados()
            if (pendientes.isEmpty()) return

            val refNube = FirebaseDatabase.getInstance().getReference("waterctrl_bitacora")
            for (reg in pendientes) {
                val datosMapa = mapOf(
                    "id_local" to reg.id,
                    "timestamp" to reg.fechaHora,
                    "distancia_cm" to reg.distancia,
                    "bomba_activa" to reg.bombaActiva,
                    "evento" to reg.evento,
                    "rol_operador" to if (reg.usuario == "empresa") "EMPRESA" else "PERSONAL"
                )
                refNube.child("registro_${reg.id}").setValue(datosMapa)
                    .addOnSuccessListener {
                        marcarComoSincronizado(reg.id)
                    }
            }
        } catch (_: Exception) {
            // Si no hay conexion o Firebase no esta configurado, permanece en SQLite local
        }
    }

    private fun obtenerRegistrosNoSincronizados(): List<RegistroHistorial> {
        val lista = mutableListOf<RegistroHistorial>()
        readableDatabase.query(
            TABLA_BITACORA,
            null,
            "$COL_BITACORA_SINCRONIZADO = 0",
            null, null, null,
            "$COL_BITACORA_ID ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val sesIdx = cursor.getColumnIndexOrThrow(COL_BITACORA_SESION_ID)
                lista.add(
                    RegistroHistorial(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_BITACORA_ID)),
                        fechaHora = cursor.getString(cursor.getColumnIndexOrThrow(COL_BITACORA_TIMESTAMP)),
                        distancia = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_BITACORA_DISTANCIA)),
                        bombaActiva = cursor.getInt(cursor.getColumnIndexOrThrow(COL_BITACORA_BOMBA)) == 1,
                        evento = cursor.getString(cursor.getColumnIndexOrThrow(COL_BITACORA_EVENTO)),
                        usuario = cursor.getString(cursor.getColumnIndexOrThrow(COL_BITACORA_USUARIO)),
                        sesionId = if (cursor.isNull(sesIdx)) null else cursor.getLong(sesIdx),
                        sincronizado = false
                    )
                )
            }
        }
        return lista
    }

    private fun marcarComoSincronizado(idRegistro: Long) {
        try {
            val valores = ContentValues().apply { put(COL_BITACORA_SINCRONIZADO, 1) }
            writableDatabase.update(TABLA_BITACORA, valores, "$COL_BITACORA_ID = ?", arrayOf(idRegistro.toString()))
        } catch (_: Exception) {
        }
    }

    fun obtenerHistorial(): List<RegistroHistorial> {
        val registros = mutableListOf<RegistroHistorial>()
        readableDatabase.query(
            TABLA_BITACORA,
            null, null, null, null, null,
            "$COL_BITACORA_ID DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val sesIdx = cursor.getColumnIndexOrThrow(COL_BITACORA_SESION_ID)
                val syncIdx = cursor.getColumnIndex(COL_BITACORA_SINCRONIZADO)
                registros.add(
                    RegistroHistorial(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_BITACORA_ID)),
                        fechaHora = cursor.getString(cursor.getColumnIndexOrThrow(COL_BITACORA_TIMESTAMP)),
                        distancia = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_BITACORA_DISTANCIA)),
                        bombaActiva = cursor.getInt(cursor.getColumnIndexOrThrow(COL_BITACORA_BOMBA)) == 1,
                        evento = cursor.getString(cursor.getColumnIndexOrThrow(COL_BITACORA_EVENTO)),
                        usuario = cursor.getString(cursor.getColumnIndexOrThrow(COL_BITACORA_USUARIO)),
                        sesionId = if (cursor.isNull(sesIdx)) null else cursor.getLong(sesIdx),
                        sincronizado = if (syncIdx != -1) cursor.getInt(syncIdx) == 1 else false
                    )
                )
            }
        }
        return registros
    }
}