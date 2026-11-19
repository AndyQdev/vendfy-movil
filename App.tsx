import { useCallback, useEffect, useRef, useState } from "react";
import { Alert, AppState, BackHandler, PermissionsAndroid, Platform, Pressable, StyleSheet, Text, View } from "react-native";
import { StatusBar } from "expo-status-bar";
import Constants from "expo-constants";
import { activateKeepAwakeAsync, deactivateKeepAwake } from "expo-keep-awake";
import { SafeAreaProvider, SafeAreaView } from "react-native-safe-area-context";
import { WebView, type WebViewMessageEvent } from "react-native-webview";

import { OidoNativo, type EventoOido } from "./modules/vendfy-oido";

/**
 * Vendfy para Android: el mismo panel de app.vendfy.shop (todo se programa una
 * sola vez, en la web) más lo que el navegador del teléfono no da:
 *
 * - micrófono y cámara con el permiso del sistema (manos libres y escáner);
 * - manos libres con el oído del teléfono (modules/vendfy-oido): un servicio
 *   que escucha «Oye Vendfy» con la app en segundo plano o la pantalla apagada,
 *   la enciende, trae Vendfy al frente y le pasa al panel lo que se dijo;
 * - el botón "atrás" vuelve dentro del panel;
 * - sin conexión, una pantalla para reintentar en vez de un error en blanco.
 *
 * El panel se entera de que está en la app por `window.vendfyNativo`.
 */
const URL: string = Constants.expoConfig?.extra?.url ?? "https://vendfy-panel.vercel.app";
const DEPURAR = __DEV__ || URL.startsWith("http://");
/** Solo en los armados de prueba: un WAV que hace de micrófono. */
const extraPrueba: unknown = Constants.expoConfig?.extra?.oidoPrueba;
const OIDO_PRUEBA: string | null = typeof extraPrueba === "string" && extraPrueba.startsWith("http") ? extraPrueba : null;

const PUENTE = `
  (function () {
    function mandar(m) { window.ReactNativeWebView.postMessage(JSON.stringify(m)); }
    window.vendfyNativo = {
      plataforma: "android",
      traerAlFrente: function () {},
      manosLibres: function (activo) { mandar({ tipo: "manosLibres", activo: !!activo }); },
      ${
        OidoNativo
          ? `oido: {
        encender: function (base, umbral) { mandar({ tipo: "oido", accion: "encender", base: base, umbral: umbral }); },
        apagar: function () { mandar({ tipo: "oido", accion: "apagar" }); },
        pausar: function (valor) { mandar({ tipo: "oido", accion: "pausar", valor: !!valor }); },
      },`
          : ""
      }
    };
  })();
  true;
`;

type MensajeDelPanel =
  | { tipo: "manosLibres"; activo: boolean }
  | { tipo: "oido"; accion: "encender"; base: string; umbral: number }
  | { tipo: "oido"; accion: "apagar" }
  | { tipo: "oido"; accion: "pausar"; valor: boolean };

async function pedirPermisos() {
  if (Platform.OS !== "android") return;
  const permisos = [PermissionsAndroid.PERMISSIONS.RECORD_AUDIO, PermissionsAndroid.PERMISSIONS.CAMERA];
  // Android 13+: la notificación fija de manos libres.
  if (Number(Platform.Version) >= 33) permisos.push(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS);
  await PermissionsAndroid.requestMultiple(permisos).catch(() => undefined);
}

/** Sin «Mostrar sobre otras apps», Android no deja traer Vendfy al frente desde segundo plano. */
function ofrecerMostrarEncima() {
  if (!OidoNativo || OidoNativo.puedeMostrarEncima()) return;
  Alert.alert(
    "Que Vendfy aparezca cuando lo llames",
    "Para que Vendfy se abra solo al oír «Oye Vendfy» (aunque estés en otra app o con la pantalla apagada), activa «Mostrar sobre otras apps». Sin eso te aparece un aviso para tocar.",
    [
      { text: "Ahora no", style: "cancel" },
      { text: "Activar", onPress: () => OidoNativo?.pedirMostrarEncima() },
    ],
  );
}

export default function App() {
  const web = useRef<WebView>(null);
  const [puedeVolver, setPuedeVolver] = useState(false);
  const [sinConexion, setSinConexion] = useState(false);
  const pendientes = useRef<EventoOido[]>([]);
  const ofrecido = useRef(false);

  useEffect(() => {
    void pedirPermisos();
  }, []);

  // Lo que oye el teléfono, al panel. Si la app todavía no está al frente
  // (pantalla bloqueada, sin permiso para aparecer sola), espera a que se abra.
  useEffect(() => {
    if (!OidoNativo) return;
    const entregar = (e: EventoOido) => {
      const js = `window.dispatchEvent(new CustomEvent("vendfy-oido", { detail: ${JSON.stringify(e)} })); true;`;
      web.current?.injectJavaScript(js);
    };
    const sub = OidoNativo.addListener("evento", (e) => {
      if (AppState.currentState === "active") entregar(e);
      else pendientes.current.push(e);
    });
    const estado = AppState.addEventListener("change", (s) => {
      if (s !== "active") return;
      const lista = pendientes.current;
      pendientes.current = [];
      lista.forEach(entregar);
    });
    return () => {
      sub.remove();
      estado.remove();
    };
  }, []);

  // "Atrás" del teléfono: primero dentro del panel.
  useEffect(() => {
    const sub = BackHandler.addEventListener("hardwareBackPress", () => {
      if (puedeVolver) {
        web.current?.goBack();
        return true;
      }
      return false;
    });
    return () => sub.remove();
  }, [puedeVolver]);

  const alMensaje = useCallback((e: WebViewMessageEvent) => {
    let m: MensajeDelPanel;
    try {
      m = JSON.parse(e.nativeEvent.data) as MensajeDelPanel;
    } catch {
      return; // un mensaje que no es nuestro
    }
    if (m.tipo === "manosLibres") {
      // Sin el oído del teléfono (Expo Go), escucha el navegador: que la pantalla no se apague.
      if (OidoNativo) return;
      if (m.activo) void activateKeepAwakeAsync("manos-libres");
      else deactivateKeepAwake("manos-libres");
      return;
    }
    if (m.tipo !== "oido" || !OidoNativo) return;
    if (m.accion === "encender") {
      OidoNativo.encender(m.base, m.umbral, OIDO_PRUEBA);
      if (!ofrecido.current && !OIDO_PRUEBA) {
        ofrecido.current = true;
        ofrecerMostrarEncima();
      }
    } else if (m.accion === "apagar") OidoNativo.apagar();
    else OidoNativo.pausar(m.valor);
  }, []);

  return (
    <SafeAreaProvider>
      <SafeAreaView style={estilos.raiz} edges={["top", "bottom"]}>
        <StatusBar style="dark" />
        {sinConexion ? (
          <View style={estilos.sinConexion} testID="sin-conexion">
            <Text style={estilos.titulo}>Sin conexión</Text>
            <Text style={estilos.texto}>Revisa el internet del teléfono y vuelve a intentar.</Text>
            <Pressable
              style={estilos.boton}
              onPress={() => {
                setSinConexion(false);
                web.current?.reload();
              }}
            >
              <Text style={estilos.botonTexto}>Reintentar</Text>
            </Pressable>
          </View>
        ) : null}
        <WebView
          ref={web}
          source={{ uri: URL }}
          style={sinConexion ? estilos.oculto : estilos.web}
          injectedJavaScriptBeforeContentLoaded={PUENTE}
          onMessage={alMensaje}
          onNavigationStateChange={(n) => setPuedeVolver(n.canGoBack)}
          onError={() => setSinConexion(true)}
          javaScriptEnabled
          domStorageEnabled
          // El micrófono y la cámara de la página, con el permiso que ya dio la app.
          mediaCapturePermissionGrantType="grant"
          // La voz de Vendfy suena sin que haya que tocar antes.
          mediaPlaybackRequiresUserAction={false}
          allowsInlineMediaPlayback
          setSupportMultipleWindows={false}
          webviewDebuggingEnabled={DEPURAR}
          pullToRefreshEnabled={false}
        />
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

const estilos = StyleSheet.create({
  raiz: { flex: 1, backgroundColor: "#ffffff" },
  web: { flex: 1 },
  oculto: { height: 0, opacity: 0 },
  sinConexion: { flex: 1, alignItems: "center", justifyContent: "center", padding: 24, gap: 12 },
  titulo: { fontSize: 22, fontWeight: "700", color: "#0f172a" },
  texto: { fontSize: 15, color: "#475569", textAlign: "center" },
  boton: { marginTop: 8, backgroundColor: "#0E7490", paddingHorizontal: 24, paddingVertical: 12, borderRadius: 10 },
  botonTexto: { color: "#ffffff", fontWeight: "700", fontSize: 15 },
});
