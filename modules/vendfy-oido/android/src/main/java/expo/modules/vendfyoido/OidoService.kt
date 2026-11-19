package expo.modules.vendfyoido

import ai.onnxruntime.OrtEnvironment
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Escucha «Oye Vendfy» con la app en segundo plano o la pantalla apagada
 * (servicio en primer plano de tipo micrófono, con su notificación fija).
 *
 * Nada sale del teléfono hasta oír la palabra. Entonces enciende la
 * pantalla, trae Vendfy al frente y le pasa al panel lo que se dijo (con la
 * palabra adelante: el servidor la verifica igual que en la web).
 */
class OidoService : Service() {
  companion object {
    const val EXTRA_BASE = "base"
    const val EXTRA_UMBRAL = "umbral"
    const val EXTRA_ARCHIVO = "archivo"
    const val ACCION_APAGAR = "expo.modules.vendfyoido.APAGAR"
    /** El «Apagar» de la notificación (no el panel): el panel apaga también la preferencia. */
    const val EXTRA_DESDE_NOTIFICACION = "desdeNotificacion"
    private const val CANAL = "vendfy_oido"
    private const val NOTIF = 4242

    @Volatile var escuchando = false

    /** Mientras habla Vendfy o se cobra: oye, pero no toma «Oye Vendfy» como pedido nuevo. */
    @Volatile var pausado = false

    /** Lo pone el módulo: cada evento va al JS de la app (y de ahí al panel). */
    @Volatile var oyente: ((Map<String, Any?>) -> Unit)? = null

    fun emitir(evento: Map<String, Any?>) {
      oyente?.invoke(evento)
    }
  }

  private var hilo: Thread? = null
  /** Cada escucha tiene su turno: una vieja que todavía cargaba no sigue ni avisa. */
  @Volatile private var turno = 0
  private var config: Triple<String, Float, String?>? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACCION_APAGAR) {
      detener()
      config = null
      val desdeNotificacion = intent.getBooleanExtra(EXTRA_DESDE_NOTIFICACION, false)
      Log.i("VendfyOido", "apagar (desde la notificación: $desdeNotificacion, panel conectado: ${oyente != null})")
      if (desdeNotificacion) {
        emitir(mapOf("tipo" to "estado", "estado" to "apagado", "afuera" to true))
      }
      stopForeground(STOP_FOREGROUND_REMOVE)
      // Solo si no llegó otro «encender» después (prender y apagar seguido).
      stopSelf(startId)
      return START_NOT_STICKY
    }
    val nueva = Triple(
      intent?.getStringExtra(EXTRA_BASE) ?: "https://vendfy-panel.vercel.app/oido/",
      intent?.getFloatExtra(EXTRA_UMBRAL, 0.5f) ?: 0.5f,
      intent?.getStringExtra(EXTRA_ARCHIVO),
    )
    crearCanal()
    val notif = notificacion()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIF, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    } else {
      startForeground(NOTIF, notif)
    }
    // Otra configuración (otro umbral, el audio de prueba): se reinicia la escucha.
    if (hilo == null || nueva != config) {
      detener()
      config = nueva
      val mio = ++turno
      hilo = Thread({ bucle(mio, nueva.first, nueva.second, nueva.third) }, "vendfy-oido").also { it.start() }
    }
    // Si Android lo mata no se reabre solo (el micrófono no se puede prender desde
    // segundo plano): el panel lo vuelve a prender al abrir la app.
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    detener()
    super.onDestroy()
  }

  private fun detener() {
    turno++
    hilo?.join(1500)
    hilo = null
    escuchando = false
  }

  private fun bucle(mio: Int, base: String, umbral: Float, archivo: String?) {
    var fuente: Fuente? = null
    try {
      val env = OrtEnvironment.getEnvironment()
      Detector(env, modelo(base, "melspectrogram.onnx"), modelo(base, "embedding_model.onnx"), modelo(base, "oye_vendfy.onnx")).use { det ->
        val disparador = Disparador(umbral)
        val piso = PisoDeRuido()
        val previo = ArrayDeque<ShortArray>()
        var antes: List<ShortArray> = emptyList()
        var comando: Comando? = null
        if (turno != mio) return
        fuente = if (archivo != null) FuenteArchivo(archivo) else FuenteMicrofono()
        escuchando = true
        emitir(mapOf("tipo" to "estado", "estado" to "escuchando"))
        while (turno == mio) {
          val paso = fuente!!.leer()
          if (paso == null) {
            emitir(mapOf("tipo" to "estado", "estado" to "error", "detalle" to "Se cortó el micrófono"))
            break
          }
          val c = comando
          if (c != null) {
            if (c.empujar(paso)) {
              val wav = aWav(antes + c.audio())
              emitir(mapOf("tipo" to "comando", "wav" to Base64.encodeToString(wav, Base64.NO_WRAP), "hablo" to c.hablo))
              comando = null
              antes = emptyList()
              previo.clear()
              det.reiniciar()
              disparador.reiniciar()
            }
            continue
          }
          piso.empujar(rms(paso))
          previo.addLast(paso)
          if (previo.size > 25) previo.removeFirst()
          val p = det.procesar(FloatArray(paso.size) { paso[it].toFloat() })
          if (!pausado && disparador.empujar(p)) {
            antes = previo.toList()
            comando = Comando(piso.valor())
            despertar()
            alFrente()
            emitir(mapOf("tipo" to "palabra", "p" to (p ?: 0f).toDouble()))
          }
        }
      }
    } catch (e: Exception) {
      if (turno == mio) emitir(mapOf("tipo" to "estado", "estado" to "error", "detalle" to (e.message ?: e.toString())))
    } finally {
      fuente?.cerrar()
      if (turno == mio) escuchando = false
    }
  }

  /** El modelo de la web (el mismo que usa el panel); si no hay conexión, el último que se bajó. */
  private fun modelo(base: String, nombre: String): ByteArray {
    val cache = File(filesDir, "oido/$nombre").apply { parentFile?.mkdirs() }
    try {
      val c = URL(base + nombre).openConnection() as HttpURLConnection
      c.connectTimeout = 10_000
      c.readTimeout = 20_000
      if (c.responseCode == 200) {
        val bytes = c.inputStream.use { it.readBytes() }
        cache.writeBytes(bytes)
        return bytes
      }
    } catch (_: Exception) {
      // sin conexión: el de la última vez
    }
    if (cache.exists()) return cache.readBytes()
    throw IllegalStateException("No se pudo bajar $nombre")
  }

  /** Enciende la pantalla unos segundos (si estaba apagada). */
  @Suppress("DEPRECATION")
  private fun despertar() {
    val pm = getSystemService(POWER_SERVICE) as PowerManager
    if (pm.isInteractive) return
    pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "vendfy:oido").acquire(10_000)
  }

  /** Vendfy al frente. Desde segundo plano Android lo permite con «Mostrar sobre otras apps». */
  private fun alFrente() {
    val abrir = packageManager.getLaunchIntentForPackage(packageName)?.apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    } ?: return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(this)) {
      try {
        startActivity(abrir)
        return
      } catch (_: Exception) {
      }
    }
    // Sin ese permiso: un aviso que se toca para abrir.
    val pi = PendingIntent.getActivity(this, 1, abrir, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val n = NotificationCompat.Builder(this, CANAL)
      .setSmallIcon(applicationInfo.icon)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setContentTitle("Te escucho")
      .setContentText("Toca para ver a Vendfy")
      .setContentIntent(pi)
      .setAutoCancel(true)
      .build()
    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF + 1, n)
  }

  private fun crearCanal() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    if (nm.getNotificationChannel(CANAL) == null) {
      nm.createNotificationChannel(NotificationChannel(CANAL, "Manos libres", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Vendfy escucha «Oye Vendfy»"
      })
    }
  }

  private fun notificacion(): Notification {
    val abrir = packageManager.getLaunchIntentForPackage(packageName)
    val piAbrir = PendingIntent.getActivity(this, 0, abrir, PendingIntent.FLAG_IMMUTABLE)
    val apagar = Intent(this, OidoService::class.java).setAction(ACCION_APAGAR).putExtra(EXTRA_DESDE_NOTIFICACION, true)
    // UPDATE_CURRENT: que no quede el atajo de una instalación anterior sin la marca.
    val piApagar = PendingIntent.getService(this, 3, apagar, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    return NotificationCompat.Builder(this, CANAL)
      .setSmallIcon(applicationInfo.icon)
      .setContentTitle("Vendfy te escucha")
      .setContentText("Di «Oye Vendfy» y lo que necesites")
      .setContentIntent(piAbrir)
      .setOngoing(true)
      .addAction(0, "Apagar", piApagar)
      .build()
  }
}

/** De dónde sale el audio: el micrófono o (en pruebas) un WAV a velocidad real. */
interface Fuente {
  /** 1280 muestras (80 ms), o null si terminó. */
  fun leer(): ShortArray?
  fun cerrar()
}

class FuenteMicrofono : Fuente {
  @SuppressLint("MissingPermission")
  private val rec = AudioRecord(
    MediaRecorder.AudioSource.VOICE_RECOGNITION,
    16000,
    AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT,
    maxOf(AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), Detector.PASO * 4),
  ).also { it.startRecording() }

  override fun leer(): ShortArray? {
    val b = ShortArray(Detector.PASO)
    var n = 0
    while (n < b.size) {
      val r = rec.read(b, n, b.size - n)
      if (r < 0) return null
      n += r
    }
    return b
  }

  override fun cerrar() {
    try {
      rec.stop()
    } catch (_: Exception) {
    }
    rec.release()
  }
}

/** Para las pruebas: un WAV (ruta o URL) como si fuera el micrófono; al terminar, silencio. */
class FuenteArchivo(origen: String) : Fuente {
  private val muestras: ShortArray
  private var i = 0
  private var siguiente = System.nanoTime()

  init {
    val bytes = if (origen.startsWith("http")) URL(origen).openStream().use { it.readBytes() } else File(origen).readBytes()
    var datos = 12
    while (datos + 8 <= bytes.size && String(bytes, datos, 4) != "data") {
      datos += 8 + ByteBuffer.wrap(bytes, datos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }
    val desde = datos + 8
    val sb = ByteBuffer.wrap(bytes, desde, bytes.size - desde).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    muestras = ShortArray(sb.remaining()).also { sb.get(it) }
  }

  override fun leer(): ShortArray {
    // A velocidad real: un paso cada 80 ms.
    siguiente += 80_000_000L
    val espera = (siguiente - System.nanoTime()) / 1_000_000L
    if (espera > 0) Thread.sleep(espera)
    val b = ShortArray(Detector.PASO)
    val n = minOf(b.size, maxOf(0, muestras.size - i))
    if (n > 0) System.arraycopy(muestras, i, b, 0, n)
    i += b.size
    return b
  }

  override fun cerrar() {}
}
