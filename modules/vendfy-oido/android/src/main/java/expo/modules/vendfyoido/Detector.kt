package expo.modules.vendfyoido

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.sqrt

/**
 * El detector de «Oye Vendfy», la misma cuenta que el panel
 * (src/features/piloto/oido/detector.ts) y que openWakeWord en Python:
 * cada 80 ms, espectrograma mel → embedding de los últimos 76 cuadros →
 * clasificador sobre los últimos 16 embeddings.
 */
class Detector(private val env: OrtEnvironment, mel: ByteArray, embedding: ByteArray, clasificador: ByteArray) : AutoCloseable {
  private val sMel: OrtSession = env.createSession(mel, OrtSession.SessionOptions())
  private val sEmb: OrtSession = env.createSession(embedding, OrtSession.SessionOptions())
  private val sCls: OrtSession = env.createSession(clasificador, OrtSession.SessionOptions())

  private var crudo = FloatArray(0)
  private val melFilas = ArrayList<FloatArray>()
  private val embeddings = ArrayList<FloatArray>()

  init {
    reiniciar()
  }

  fun reiniciar() {
    crudo = FloatArray(0)
    melFilas.clear()
    repeat(CUADROS) { melFilas.add(FloatArray(32) { 1f }) }
    embeddings.clear()
  }

  /** Un paso de 1280 muestras en escala int16. Devuelve la probabilidad o null sin historia suficiente. */
  fun procesar(paso: FloatArray): Float? {
    val unido = crudo + paso
    val entrada = unido.copyOfRange(max(0, unido.size - (PASO + CONTEXTO)), unido.size)
    crudo = entrada

    correr(sMel, entrada, longArrayOf(1, entrada.size.toLong())).let { espectro ->
      var i = 0
      while (i + 32 <= espectro.size) {
        melFilas.add(FloatArray(32) { j -> espectro[i + j] / 10f + 2f })
        i += 32
      }
    }
    while (melFilas.size > MAX_CUADROS) melFilas.removeAt(0)

    val ventana = FloatArray(CUADROS * 32)
    val desde = melFilas.size - CUADROS
    for (k in 0 until CUADROS) System.arraycopy(melFilas[desde + k], 0, ventana, k * 32, 32)
    embeddings.add(correr(sEmb, ventana, longArrayOf(1, CUADROS.toLong(), 32, 1)).copyOf(96))
    while (embeddings.size > 120) embeddings.removeAt(0)
    if (embeddings.size < 16) return null

    val x = FloatArray(16 * 96)
    val inicio = embeddings.size - 16
    for (k in 0 until 16) System.arraycopy(embeddings[inicio + k], 0, x, k * 96, 96)
    return correr(sCls, x, longArrayOf(1, 16, 96))[0]
  }

  private fun correr(s: OrtSession, datos: FloatArray, forma: LongArray): FloatArray {
    OnnxTensor.createTensor(env, FloatBuffer.wrap(datos), forma).use { t ->
      s.run(mapOf(s.inputNames.first() to t)).use { r ->
        val b = (r[0] as OnnxTensor).floatBuffer
        return FloatArray(b.remaining()).also { b.get(it) }
      }
    }
  }

  override fun close() {
    sMel.close()
    sEmb.close()
    sCls.close()
  }

  companion object {
    const val PASO = 1280
    private const val CONTEXTO = 480
    private const val CUADROS = 76
    private const val MAX_CUADROS = 970
  }
}

/** Dos pasos seguidos sobre el umbral; después 2 s de descanso (no dispara dos veces por la misma palabra). */
class Disparador(var umbral: Float, private val paciencia: Int = 2, private val descansoPasos: Int = 25) {
  private var seguidos = 0
  private var descanso = 0

  fun empujar(p: Float?): Boolean {
    if (descanso > 0) {
      descanso--
      seguidos = 0
      return false
    }
    if (p == null || p < umbral) {
      seguidos = 0
      return false
    }
    if (++seguidos >= paciencia) {
      seguidos = 0
      descanso = descansoPasos
      return true
    }
    return false
  }

  fun reiniciar() {
    seguidos = 0
    descanso = 0
  }
}

fun rms(x: ShortArray): Float {
  var s = 0.0
  for (v in x) s += (v / 32768.0) * (v / 32768.0)
  return sqrt(s / max(1, x.size)).toFloat()
}

/** El piso de ruido del lugar: un percentil bajo de la energía de los últimos 4 s. */
class PisoDeRuido(private val largo: Int = 50) {
  private val valores = ArrayDeque<Float>()
  fun empujar(e: Float) {
    valores.addLast(e)
    if (valores.size > largo) valores.removeFirst()
  }
  fun valor(): Float = if (valores.isEmpty()) 0.002f else valores.sorted()[(valores.size * 0.2).toInt()]
}

/** Lo que se dice después de la palabra, hasta que la persona se calla (igual que comando.ts). */
class Comando(piso: Float) {
  private val pasos = ArrayList<ShortArray>()
  private val umbral = max(piso * 3, 0.004f)
  private var conVoz = 0
  private var silencio = 0

  /** true cuando terminó. */
  fun empujar(paso: ShortArray): Boolean {
    pasos.add(paso)
    if (rms(paso) >= umbral) {
      conVoz++
      silencio = 0
    } else {
      silencio++
    }
    if (pasos.size >= 125) return true // 10 s
    if (conVoz < 4) return pasos.size >= 75 // 6 s sin hablar
    return silencio >= 13 // 1 s de silencio
  }

  val hablo get() = conVoz >= 4

  fun audio(): List<ShortArray> = if (hablo) pasos.subList(0, pasos.size - max(0, silencio - 4)) else emptyList()
}

/** WAV de 16 bits a 16 kHz. */
fun aWav(trozos: List<ShortArray>): ByteArray {
  val n = trozos.sumOf { it.size }
  val cab = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
  cab.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray())
  cab.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
  cab.put("data".toByteArray()).putInt(n * 2)
  val salida = ByteArrayOutputStream(44 + n * 2)
  salida.write(cab.array())
  val datos = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
  for (t in trozos) for (v in t) datos.putShort(v)
  salida.write(datos.array())
  return salida.toByteArray()
}
