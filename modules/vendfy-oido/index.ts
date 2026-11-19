import { requireOptionalNativeModule } from "expo-modules-core";

/** Lo que manda el oído nativo (servicio de micrófono de Android). */
export type EventoOido =
  | { tipo: "palabra"; p: number }
  /** Lo dicho desde 2 s antes de la palabra hasta que se calló: WAV 16 kHz en base64. */
  | { tipo: "comando"; wav: string; hablo: boolean }
  /** `afuera`: lo apagaron con el «Apagar» de la notificación. */
  | { tipo: "estado"; estado: "escuchando" | "apagado" | "error"; detalle?: string; afuera?: boolean };

interface ModuloOido {
  encender(base: string, umbral: number, archivo: string | null): void;
  apagar(): void;
  escuchando(): boolean;
  pausar(valor: boolean): void;
  puedeMostrarEncima(): boolean;
  pedirMostrarEncima(): void;
  addListener(evento: "evento", cb: (e: EventoOido) => void): { remove(): void };
}

/** null fuera de Android (o en Expo Go): la app sigue con el micrófono del WebView. */
export const OidoNativo = requireOptionalNativeModule<ModuloOido>("VendfyOido");
