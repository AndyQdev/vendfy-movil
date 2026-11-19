/**
 * Vendfy para Android: el panel de app.vendfy.shop dentro de la app, con el
 * micrófono y la cámara del teléfono (manos libres «Oye Vendfy» y el escáner).
 *
 * EXPO_PUBLIC_VENDFY_URL cambia la dirección (para probar contra el panel
 * local desde el emulador: http://10.0.2.2:5173). Con http se habilita el
 * tráfico sin cifrar SOLO en ese armado de prueba.
 */
// El panel publicado en la cuenta de Vercel que funciona (app.vendfy.shop sigue
// en la cuenta pausada hasta que se verifique el dominio; sirven lo mismo).
const url = process.env.EXPO_PUBLIC_VENDFY_URL || "https://vendfy-panel.vercel.app";
const prueba = url.startsWith("http://");
/** Solo para las pruebas de punta a punta: un WAV que hace de micrófono del oído nativo. */
const oidoPrueba = process.env.EXPO_PUBLIC_OIDO_ARCHIVO || null;

module.exports = {
  expo: {
    name: "Vendfy",
    slug: "vendfy",
    version: "1.1.0",
    orientation: "default",
    icon: "./assets/icon.png",
    userInterfaceStyle: "light",
    splash: { image: "./assets/splash-icon.png", resizeMode: "contain", backgroundColor: "#ffffff" },
    android: {
      package: "shop.vendfy.app",
      versionCode: 2,
      adaptiveIcon: {
        backgroundColor: "#ECFDF5",
        foregroundImage: "./assets/android-icon-foreground.png",
        backgroundImage: "./assets/android-icon-background.png",
        monochromeImage: "./assets/android-icon-monochrome.png",
      },
      permissions: ["android.permission.RECORD_AUDIO", "android.permission.CAMERA", "android.permission.MODIFY_AUDIO_SETTINGS", "android.permission.WAKE_LOCK"],
      predictiveBackGestureEnabled: false,
    },
    plugins: [
      ["expo-build-properties", { android: { usesCleartextTraffic: prueba } }],
      ["./plugins/arquitecturas", { lista: prueba ? ["arm64-v8a", "x86_64"] : ["armeabi-v7a", "arm64-v8a"] }],
    ],
    // oidoPrueba solo si hay audio de prueba (un null llega a la app como {}).
    extra: { url, ...(oidoPrueba ? { oidoPrueba } : {}) },
  },
};
