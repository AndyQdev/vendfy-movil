/**
 * Qué procesadores trae la APK. Los teléfonos y tablets son ARM; x86/x86_64
 * son de emuladores y casi duplican el peso (el motor ONNX del oído y React
 * Native van una vez por arquitectura). El armado de prueba suma x86_64.
 */
const { withAppBuildGradle, withGradleProperties } = require("expo/config-plugins");

module.exports = function arquitecturas(config, { lista }) {
  config = withGradleProperties(config, (c) => {
    c.modResults = c.modResults.filter((p) => !(p.type === "property" && p.key === "reactNativeArchitectures"));
    c.modResults.push({ type: "property", key: "reactNativeArchitectures", value: lista.join(",") });
    return c;
  });
  return withAppBuildGradle(config, (c) => {
    if (!c.modResults.contents.includes("abiFilters")) {
      const filtros = lista.map((a) => `"${a}"`).join(", ");
      c.modResults.contents = c.modResults.contents.replace(/defaultConfig\s*\{/, (m) => `${m}\n        ndk { abiFilters ${filtros} }`);
    }
    return c;
  });
};
