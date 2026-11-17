package dev.inacap.waterctrl

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import com.google.android.material.card.MaterialCardView
import dev.inacap.waterctrl.databinding.ActivityHistorialBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import androidx.core.view.isGone

class HistorialActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistorialBinding
    private lateinit var dbHelper: DatabaseHelper
    private var usuarioActual = ""
    private var todosLosRegistros: List<RegistroHistorial> = emptyList()
    private var registrosFiltradosActuales: List<RegistroHistorial> = emptyList()
    private var filtroCategoria = "TODOS"
    private var modoGrafico = "EVENTOS"

    private data class PuntoGrafico(
        val etiquetaEje: String,
        val nivelPct: Float,
        val bombaActiva: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistorialBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dbHelper = DatabaseHelper(this)
        usuarioActual = intent.getStringExtra("USUARIO").orEmpty()

        val rol = if (usuarioActual.isNotBlank()) {
            dbHelper.obtenerRolUsuario(usuarioActual)
        } else {
            null
        }

        if (rol != "EMPRESA") {
            Toast.makeText(
                this,
                "No tienes autorización para acceder al historial",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        binding.btnVolverHistorial.setOnClickListener { finish() }
        binding.btnExportarCsv.setOnClickListener { exportarReporteCsv() }

        cargarHistorial()
    }

    private fun cargarHistorial() {
        todosLosRegistros = dbHelper.obtenerHistorial()
        binding.tvResumenHistorial.text =
            getString(R.string.historial_registros, todosLosRegistros.size)

        binding.contenedorPanelFijo.removeAllViews()
        binding.contenedorFiltros.removeAllViews()

        if (todosLosRegistros.isEmpty()) {
            binding.contenedorHistorial.removeAllViews()
            val mensaje = TextView(this).apply {
                text = getString(R.string.historial_vacio)
                textSize = 15f
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(24), dp(20), dp(24))
            }
            binding.contenedorHistorial.addView(mensaje)
            return
        }

        construirDesplegablesControl()
        actualizarPanelAnalitico()
        aplicarFiltroYMostrarLista()
    }

    private fun exportarReporteCsv() {
        if (registrosFiltradosActuales.isEmpty()) {
            Toast.makeText(this, "No hay registros para exportar", Toast.LENGTH_SHORT).show()
            return
        }

        val sb = StringBuilder()
        sb.appendLine("ID;FechaHora;Operador;Evento;Distancia_cm;Nivel_pct;Bomba_ON;Sincronizado_Nube;SesionID")
        for (reg in registrosFiltradosActuales) {
            val pct = String.format(Locale.US, "%.1f", calcularPorcentajeEstanque(reg.distancia))
            val dist = String.format(Locale.US, "%.1f", reg.distancia)
            sb.appendLine(
                "${reg.id};${reg.fechaHora};${reg.usuario};${reg.evento};$dist;$pct;${reg.bombaActiva};${reg.sincronizado};${reg.sesionId ?: ""}"
            )
        }

        val intentCompartir = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "Reporte Auditoría WaterCTRL")
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        startActivity(Intent.createChooser(intentCompartir, "Exportar bitácora WaterCTRL"))
    }

    private fun construirDesplegablesControl() {
        binding.contenedorFiltros.removeAllViews()

        val opcionesCategoria = listOf(
            String.format(Locale.getDefault(), "Filtro: %s (%d)", getString(R.string.historial_filtro_todos), todosLosRegistros.size),
            String.format(Locale.getDefault(), "Filtro: %s (%d)", getString(R.string.historial_filtro_alertas), todosLosRegistros.count { esEventoAlerta(it.evento) }),
            String.format(Locale.getDefault(), "Filtro: %s (%d)", getString(R.string.historial_filtro_bomba), todosLosRegistros.count { it.evento.contains("BOMBA", ignoreCase = true) }),
            String.format(Locale.getDefault(), "Filtro: %s (%d)", getString(R.string.historial_filtro_sesiones_corto), todosLosRegistros.count { it.evento.contains("SESION", ignoreCase = true) })
        )

        val spinnerCategoria = crearSpinnerEstilizado(opcionesCategoria, 0) { pos ->
            filtroCategoria = when (pos) {
                1 -> "ALERTAS"
                2 -> "BOMBA"
                3 -> "SESIONES"
                else -> "TODOS"
            }
            aplicarFiltroYMostrarLista()
        }

        val opcionesTiempo = listOf(
            getString(R.string.historial_rango_eventos),
            getString(R.string.historial_rango_hora),
            getString(R.string.historial_rango_dia)
        )

        val spinnerTiempo = crearSpinnerEstilizado(opcionesTiempo, 6) { pos ->
            modoGrafico = when (pos) {
                1 -> "HORA"
                2 -> "DIA"
                else -> "EVENTOS"
            }
            actualizarPanelAnalitico()
        }

        binding.contenedorFiltros.addView(spinnerCategoria)
        binding.contenedorFiltros.addView(spinnerTiempo)
    }

    private fun crearSpinnerEstilizado(
        opciones: List<String>,
        margenIzqDp: Int,
        alSeleccionar: (Int) -> Unit
    ): Spinner {
        return Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (margenIzqDp > 0) marginStart = dp(margenIzqDp)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), "#CBD5E1".toColorInt())
            }
            setPopupBackgroundDrawable(GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), "#CBD5E1".toColorInt())
            })
            setPadding(dp(8), dp(4), dp(8), dp(4))

            val adapter = object : ArrayAdapter<String>(
                this@HistorialActivity,
                android.R.layout.simple_spinner_item,
                opciones
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = super.getView(position, convertView, parent) as TextView
                    v.textSize = 12f
                    v.setTypeface(null, Typeface.BOLD)
                    v.setTextColor("#0F172A".toColorInt())
                    return v
                }

                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = super.getDropDownView(position, convertView, parent) as TextView
                    v.textSize = 13f
                    v.setPadding(dp(14), dp(12), dp(14), dp(12))
                    v.setBackgroundColor(Color.WHITE)
                    v.setTextColor("#0F172A".toColorInt())
                    return v
                }
            }
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            this.adapter = adapter

            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    alSeleccionar(position)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
    }

    private fun actualizarPanelAnalitico() {
        binding.contenedorPanelFijo.removeAllViews()

        val totalAlertas = todosLosRegistros.count { esEventoAlerta(it.evento) }
        val activacionesBomba = todosLosRegistros.count {
            it.evento.contains("ENCEND", ignoreCase = true) ||
                    it.evento.contains("ACTIVADA", ignoreCase = true)
        }
        val tiempoUsoBombaTexto = calcularTiempoUsoBomba(todosLosRegistros)

        val cardPanel = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            radius = dp(14).toFloat()
            cardElevation = dp(2).toFloat()
            strokeWidth = dp(1)
            strokeColor = "#CBD5E1".toColorInt()
            setCardBackgroundColor(Color.WHITE)
        }

        val layoutPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        val filaKpis = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        filaKpis.addView(crearCeldaMetrica(getString(R.string.historial_kpi_uso_bomba), tiempoUsoBombaTexto, "#059669".toColorInt()))
        filaKpis.addView(crearCeldaMetrica("Ciclos Bomba", activacionesBomba.toString(), "#0284C7".toColorInt()))
        filaKpis.addView(crearCeldaMetrica("Alertas", totalAlertas.toString(), "#DC2626".toColorInt()))

        val subtituloGrafico = when (modoGrafico) {
            "HORA" -> "PROMEDIO DE NIVEL Y BOMBA POR HORA"
            "DIA" -> "PROMEDIO DE NIVEL Y BOMBA POR DÍA"
            else -> getString(R.string.historial_grafico_titulo)
        }

        val tvLeyenda = TextView(this).apply {
            text = subtituloGrafico
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor("#64748B".toColorInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(6)
            }
        }

        val puntosAgrupados = construirPuntosGrafico(todosLosRegistros, modoGrafico)
        val graficoView = GraficoTelemetriaView(this, puntosAgrupados).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(92)
            )
        }

        layoutPanel.addView(filaKpis)
        layoutPanel.addView(tvLeyenda)
        layoutPanel.addView(graficoView)
        cardPanel.addView(layoutPanel)
        binding.contenedorPanelFijo.addView(cardPanel)
    }

    private fun calcularTiempoUsoBomba(registros: List<RegistroHistorial>): String {
        val formato = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val cronologicos = registros.reversed()
        var inicioEncendidoMs: Long? = null
        var totalMs = 0L

        for (reg in cronologicos) {
            val ts = try {
                formato.parse(reg.fechaHora)?.time
            } catch (_: Exception) {
                null
            } ?: continue

            if (reg.bombaActiva && inicioEncendidoMs == null) {
                inicioEncendidoMs = ts
            } else if (!reg.bombaActiva && inicioEncendidoMs != null) {
                if (ts > inicioEncendidoMs) {
                    totalMs += (ts - inicioEncendidoMs)
                }
                inicioEncendidoMs = null
            }
        }

        val totalSeg = totalMs / 1000L
        val min = totalSeg / 60L
        val seg = totalSeg % 60L
        return String.format(Locale.getDefault(), "%dm %02ds", min, seg)
    }

    private fun construirPuntosGrafico(
        registros: List<RegistroHistorial>,
        modo: String
    ): List<PuntoGrafico> {
        val cronologicos = registros.reversed()
        return when (modo) {
            "HORA" -> {
                cronologicos.groupBy { extraerClaveHora(it.fechaHora) }
                    .toList()
                    .takeLast(12)
                    .map { (hora, grupo) ->
                        val promPct = grupo.map { calcularPorcentajeEstanque(it.distancia) }.average().toFloat()
                        val huboBomba = grupo.any { it.bombaActiva }
                        PuntoGrafico(hora, promPct, huboBomba)
                    }
            }
            "DIA" -> {
                cronologicos.groupBy { extraerClaveDia(it.fechaHora) }
                    .toList()
                    .takeLast(10)
                    .map { (dia, grupo) ->
                        val promPct = grupo.map { calcularPorcentajeEstanque(it.distancia) }.average().toFloat()
                        val huboBomba = grupo.any { it.bombaActiva }
                        PuntoGrafico(dia, promPct, huboBomba)
                    }
            }
            else -> {
                cronologicos.takeLast(14).map { reg ->
                    PuntoGrafico(
                        etiquetaEje = extraerHoraMinuto(reg.fechaHora),
                        nivelPct = calcularPorcentajeEstanque(reg.distancia),
                        bombaActiva = reg.bombaActiva
                    )
                }
            }
        }
    }

    private fun extraerHoraMinuto(fechaHora: String): String {
        val partes = fechaHora.trim().split(" ")
        return if (partes.size >= 2 && partes[1].length >= 5) partes[1].substring(0, 5) else ""
    }

    private fun extraerClaveHora(fechaHora: String): String {
        val partes = fechaHora.trim().split(" ")
        return if (partes.size >= 2 && partes[1].length >= 2) "${partes[1].substring(0, 2)}:00" else "00:00"
    }

    private fun extraerClaveDia(fechaHora: String): String {
        val fecha = fechaHora.trim().split(" ").firstOrNull().orEmpty()
        val segmentos = fecha.split("-")
        return if (segmentos.size == 3) "${segmentos[2]}/${segmentos[1]}" else fecha
    }

    private fun aplicarFiltroYMostrarLista() {
        binding.contenedorHistorial.removeAllViews()
        val filtrados = when (filtroCategoria) {
            "ALERTAS" -> todosLosRegistros.filter { esEventoAlerta(it.evento) }
            "BOMBA" -> todosLosRegistros.filter { it.evento.contains("BOMBA", ignoreCase = true) }
            "SESIONES" -> todosLosRegistros.filter { it.evento.contains("SESION", ignoreCase = true) }
            else -> todosLosRegistros
        }
        registrosFiltradosActuales = filtrados

        if (filtrados.isEmpty()) {
            val vacio = TextView(this).apply {
                text = getString(R.string.historial_vacio)
                textSize = 14f
                setTextColor("#64748B".toColorInt())
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(24), dp(16), dp(24))
            }
            binding.contenedorHistorial.addView(vacio)
            return
        }

        val sdfDia = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val cal = Calendar.getInstance()
        val hoyStr = sdfDia.format(cal.time)
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val ayerStr = sdfDia.format(cal.time)

        var ultimoGrupoDia = ""

        filtrados.forEach { registro ->
            val diaRegistro = registro.fechaHora.trim().split(" ").firstOrNull().orEmpty()
            if (diaRegistro.isNotEmpty() && diaRegistro != ultimoGrupoDia) {
                ultimoGrupoDia = diaRegistro
                val etiquetaGrupo = when (diaRegistro) {
                    hoyStr -> getString(R.string.historial_hoy)
                    ayerStr -> getString(R.string.historial_ayer)
                    else -> extraerClaveDia(registro.fechaHora)
                }
                agregarSeparadorFecha(etiquetaGrupo)
            }
            agregarRegistro(registro)
        }
    }

    private fun agregarSeparadorFecha(etiqueta: String) {
        val tvGrupo = TextView(this).apply {
            text = etiqueta
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor("#64748B".toColorInt())
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        binding.contenedorHistorial.addView(tvGrupo)
    }

    private fun agregarRegistro(registro: RegistroHistorial) {
        val estilo = obtenerEstiloEvento(registro.evento)
        val nivelPct = calcularPorcentajeEstanque(registro.distancia)

        val tarjeta = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
            radius = dp(12).toFloat()
            cardElevation = dp(1).toFloat()
            strokeWidth = dp(1)
            strokeColor = "#E2E8F0".toColorInt()
            setCardBackgroundColor(Color.WHITE)
        }

        val filaRaiz = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val barraLateral = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                dp(5),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(estilo.colorTexto)
        }

        val cuerpo = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(14), dp(10))
        }

        val filaSuperior = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titulo = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = formatearTituloEvento(registro.evento)
            textSize = 14f
            setTextColor("#0F172A".toColorInt())
            setTypeface(null, Typeface.BOLD)
        }

        val badge = TextView(this).apply {
            text = estilo.etiqueta
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(estilo.colorTexto)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(estilo.colorFondo)
            }
        }

        filaSuperior.addView(titulo)
        filaSuperior.addView(badge)

        val tvDatos = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(5)
                bottomMargin = dp(5)
            }
            val bombaTxt = if (registro.bombaActiva) "Bomba ON" else "Bomba OFF"
            val nubeTxt = if (registro.sincronizado) "Nube ✓" else "Local"
            text = String.format(
                Locale.getDefault(),
                "Estanque: %.0f%% (%.1f cm)  •  %s  •  %s",
                nivelPct,
                registro.distancia,
                bombaTxt,
                nubeTxt
            )
            textSize = 12f
            setTextColor("#334155".toColorInt())
        }

        val filaInferior = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val horaSolo = registro.fechaHora.trim().split(" ").getOrNull(1) ?: registro.fechaHora
        val tvFecha = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = horaSolo
            textSize = 11f
            setTextColor("#64748B".toColorInt())
        }

        val nombreUsuario = registro.usuario.ifBlank {
            getString(R.string.historial_sin_usuario)
        }

        val tvOperador = TextView(this).apply {
            text = if (registro.sesionId != null) {
                getString(R.string.historial_operador_sesion, nombreUsuario, registro.sesionId)
            } else {
                nombreUsuario
            }
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor("#475569".toColorInt())
        }

        // Detalle técnico expandible al tocar la tarjeta
        val estadoNube = if (registro.sincronizado) "Firebase OK" else "Pendiente SQLite"
        val tvDetalleExpandible = TextView(this).apply {
            visibility = View.GONE
            text = getString(R.string.historial_detalle_tecnico, registro.id, estadoNube)
            textSize = 11f
            setTextColor("#0369A1".toColorInt())
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor("#F0F9FF".toColorInt())
            }
        }

        tarjeta.setOnClickListener {
            tvDetalleExpandible.isGone = !tvDetalleExpandible.isGone
        }

        filaInferior.addView(tvFecha)
        filaInferior.addView(tvOperador)

        cuerpo.addView(filaSuperior)
        cuerpo.addView(tvDatos)
        cuerpo.addView(filaInferior)
        cuerpo.addView(tvDetalleExpandible)

        filaRaiz.addView(barraLateral)
        filaRaiz.addView(cuerpo)
        tarjeta.addView(filaRaiz)
        binding.contenedorHistorial.addView(tarjeta)
    }

    private fun crearCeldaMetrica(etiqueta: String, valor: String, colorValor: Int): LinearLayout {
        return LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            orientation = LinearLayout.VERTICAL

            val tvLabel = TextView(context).apply {
                text = etiqueta.uppercase(Locale.getDefault())
                textSize = 10f
                setTextColor("#94A3B8".toColorInt())
                setTypeface(null, Typeface.BOLD)
            }

            val tvVal = TextView(context).apply {
                text = valor
                textSize = 15f
                setTextColor(colorValor)
                setTypeface(null, Typeface.BOLD)
            }

            addView(tvLabel)
            addView(tvVal)
        }
    }

    private fun calcularPorcentajeEstanque(distanciaCm: Float): Float {
        return ((60f - distanciaCm) / 60f * 100f).coerceIn(0f, 100f)
    }

    private fun esEventoAlerta(evento: String): Boolean {
        val ev = evento.uppercase(Locale.ROOT)
        return ev.contains("ESTOP") || ev.contains("EMERGENCIA") || ev.contains("ALERTA")
    }

    private data class EstiloEvento(val etiqueta: String, val colorTexto: Int, val colorFondo: Int)

    private fun obtenerEstiloEvento(eventoRaw: String): EstiloEvento {
        val ev = eventoRaw.uppercase(Locale.ROOT)
        return when {
            ev.contains("ESTOP") || ev.contains("EMERGENCIA") || ev.contains("ALERTA") ->
                EstiloEvento("ALERTA", "#B91C1C".toColorInt(), "#FEE2E2".toColorInt())
            ev.contains("BOMBA") ->
                EstiloEvento("ACTUADOR", "#0369A1".toColorInt(), "#E0F2FE".toColorInt())
            ev.contains("MODO") || ev.contains("ENERGIA") || ev.contains("ESTABILIZADO") ->
                EstiloEvento("CONTROL", "#6D28D9".toColorInt(), "#EDE9FE".toColorInt())
            ev.contains("SESION") ->
                EstiloEvento("SESIÓN", "#047857".toColorInt(), "#D1FAE5".toColorInt())
            else ->
                EstiloEvento("SISTEMA", "#334155".toColorInt(), "#F1F5F9".toColorInt())
        }
    }

    private fun formatearTituloEvento(eventoRaw: String): String {
        return when (eventoRaw.uppercase(Locale.ROOT)) {
            "INICIO_SESION_EXITOSO" -> "Inicio de sesión en consola"
            "FIN_SESION" -> "Cierre de sesión"
            "SELECCION_INTERFAZ_CANCELADA" -> "Selección de módulo cancelada"
            "SOLICITUD_PARO_EMERGENCIA", "PARO_EMERGENCIA_RECIBIDO" -> "Paro de emergencia (E-STOP)"
            "SOLICITUD_ENCENDER_BOMBA", "BOMBA_ACTIVADA_SIMULACION" -> "Activación manual de bomba"
            "SOLICITUD_APAGAR_BOMBA", "BOMBA_DESACTIVADA_SIMULACION" -> "Detención manual de bomba"
            "BOMBA_ENCENDIDA_AUTO" -> "Bomba encendida (Automático)"
            "BOMBA_APAGADA_AUTO" -> "Bomba detenida (Automático)"
            "ALERTA_NIVEL_CRITICO_BAJO" -> "Alerta: Nivel de agua crítico bajo"
            "ALERTA_NIVEL_LLENO_MAXIMO" -> "Aviso: Estanque en capacidad máxima"
            "ALERTA_FALLA_SENSOR_HCSR04" -> "Falla detectada en sensor HC-SR04"
            "NIVEL_AGUA_ESTABILIZADO" -> "Nivel de agua estabilizado"
            "SOLICITUD_CAMBIO_MODO_AUTO" -> "Cambio a modo Automático"
            "SOLICITUD_CAMBIO_MODO_MANUAL" -> "Cambio a modo Manual"
            "MODO_ENERGIA_ECO" -> "Ahorro de energía activado (ECO)"
            "MODO_ENERGIA_NORMAL" -> "Modo de energía Normal"
            else -> eventoRaw.replace("_", " ").lowercase(Locale.getDefault())
                .replaceFirstChar { it.titlecase(Locale.getDefault()) }
        }
    }

    private fun dp(valor: Int): Int {
        return (valor * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        if (::dbHelper.isInitialized) {
            dbHelper.close()
        }
        super.onDestroy()
    }

    private inner class GraficoTelemetriaView(
        context: Context,
        private val puntos: List<PuntoGrafico>
    ) : View(context) {

        private val paintFondo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = "#F8FAFC".toColorInt()
            style = Paint.Style.FILL
        }

        private val paintLinea = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = "#0284C7".toColorInt()
            strokeWidth = dp(2).toFloat()
            style = Paint.Style.STROKE
        }

        private val paintArea = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val paintUmbral = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = "#EF4444".toColorInt()
            strokeWidth = dp(1).toFloat()
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
        }

        private val paintPuntoBombaOn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = "#0284C7".toColorInt()
            style = Paint.Style.FILL
        }

        private val paintPuntoBombaOff = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = "#94A3B8".toColorInt()
            style = Paint.Style.FILL
        }

        private val paintTexto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = "#64748B".toColorInt()
            textSize = dp(9).toFloat()
        }

        private val pathLinea = Path()
        private val pathArea = Path()
        private val coordsX = FloatArray(puntos.size)
        private val coordsY = FloatArray(puntos.size)

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val padArr = dp(10).toFloat()
            val padAba = dp(18).toFloat()
            paintArea.shader = LinearGradient(
                0f, padArr, 0f, h - padAba,
                "#330284C7".toColorInt(),
                "#000284C7".toColorInt(),
                Shader.TileMode.CLAMP
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val padIzq = dp(30).toFloat()
            val padDer = dp(14).toFloat()
            val padArr = dp(10).toFloat()
            val padAba = dp(18).toFloat()

            canvas.drawRoundRect(0f, 0f, w, h, dp(8).toFloat(), dp(8).toFloat(), paintFondo)

            val altoUtil = h - padArr - padAba
            val anchoUtil = w - padIzq - padDer

            canvas.drawText("100%", dp(4).toFloat(), padArr + dp(6), paintTexto)
            canvas.drawText("0%", dp(8).toFloat(), h - padAba, paintTexto)

            val pctUmbral = calcularPorcentajeEstanque(25f)
            val yUmbral = padArr + altoUtil * (1f - pctUmbral / 100f)
            canvas.drawLine(padIzq, yUmbral, w - padDer, yUmbral, paintUmbral)

            if (puntos.isEmpty()) return

            pathLinea.reset()
            pathArea.reset()

            for (idx in puntos.indices) {
                val pct = puntos[idx].nivelPct
                val x = if (puntos.size == 1) {
                    padIzq + anchoUtil / 2f
                } else {
                    padIzq + (anchoUtil * idx / (puntos.size - 1).toFloat())
                }
                val y = padArr + altoUtil * (1f - pct / 100f)
                coordsX[idx] = x
                coordsY[idx] = y

                if (idx == 0) {
                    pathLinea.moveTo(x, y)
                    pathArea.moveTo(x, h - padAba)
                    pathArea.lineTo(x, y)
                } else {
                    pathLinea.lineTo(x, y)
                    pathArea.lineTo(x, y)
                }
            }

            pathArea.lineTo(coordsX[puntos.lastIndex], h - padAba)
            pathArea.close()

            canvas.drawPath(pathArea, paintArea)
            canvas.drawPath(pathLinea, paintLinea)

            for (idx in puntos.indices) {
                if (puntos[idx].bombaActiva) {
                    canvas.drawCircle(coordsX[idx], coordsY[idx], dp(4).toFloat(), paintPuntoBombaOn)
                } else {
                    canvas.drawCircle(coordsX[idx], coordsY[idx], dp(2).toFloat(), paintPuntoBombaOff)
                }
            }

            val yEjeX = h - dp(4).toFloat()
            val etiquetaInicio = puntos.first().etiquetaEje
            if (etiquetaInicio.isNotEmpty()) {
                canvas.drawText(etiquetaInicio, padIzq, yEjeX, paintTexto)
            }
            if (puntos.size >= 3) {
                val mid = puntos.size / 2
                val etiquetaMid = puntos[mid].etiquetaEje
                if (etiquetaMid.isNotEmpty()) {
                    canvas.drawText(etiquetaMid, coordsX[mid] - dp(12), yEjeX, paintTexto)
                }
            }
            if (puntos.size >= 2) {
                val etiquetaFin = puntos.last().etiquetaEje
                if (etiquetaFin.isNotEmpty()) {
                    canvas.drawText(etiquetaFin, coordsX[puntos.lastIndex] - dp(24), yEjeX, paintTexto)
                }
            }
        }
    }
}