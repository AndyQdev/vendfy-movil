package expo.modules.vendfyoido

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

/** Manos libres nativo: prender, apagar y los eventos del oído hacia el JS de la app. */
class VendfyOidoModule : Module() {
  private val contexto get() = appContext.reactContext ?: throw IllegalStateException("Sin contexto")

  override fun definition() = ModuleDefinition {
    Name("VendfyOido")
    Events("evento")

    OnCreate {
      OidoService.oyente = { evento -> sendEvent("evento", evento) }
    }

    OnDestroy {
      OidoService.oyente = null
    }

    /** `archivo`: solo en pruebas, un WAV (ruta o URL) en vez del micrófono. */
    Function("encender") { base: String, umbral: Double, archivo: String? ->
      // Con el teléfono bloqueado se enciende la pantalla, pero Vendfy no se
      // muestra encima del bloqueo: se ve al desbloquear (los datos de la tienda
      // no quedan a la vista de cualquiera).
      val i = Intent(contexto, OidoService::class.java)
        .putExtra(OidoService.EXTRA_BASE, base)
        .putExtra(OidoService.EXTRA_UMBRAL, umbral.toFloat())
      if (archivo != null) i.putExtra(OidoService.EXTRA_ARCHIVO, archivo)
      ContextCompat.startForegroundService(contexto, i)
    }

    Function("apagar") {
      contexto.startService(Intent(contexto, OidoService::class.java).setAction(OidoService.ACCION_APAGAR))
    }

    Function("escuchando") { OidoService.escuchando }

    Function("pausar") { valor: Boolean -> OidoService.pausado = valor }

    /** «Mostrar sobre otras apps»: lo que deja traer Vendfy al frente desde segundo plano. */
    Function("puedeMostrarEncima") { Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(contexto) }

    Function("pedirMostrarEncima") {
      contexto.startActivity(
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${contexto.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
      )
    }
  }
}
