# FallAlert (Android)

Aplicacion Android para recibir mensajes de alerta desde un detector basado en Arduino por Bluetooth, y disparar notificaciones remotas por Telegram.

## Estado actual

Flujo actual implementado:

1. El detector de caidas (Arduino) envia una alerta por Bluetooth.
2. La app recibe y procesa el mensaje en segundo plano.
3. La app envia un mensaje formateado a Telegram usando el Bot API.
4. La app registra en logs el resultado HTTP del envio.

## Funcionalidades implementadas

- Escucha Bluetooth con servicio en segundo plano (Foreground Service).
- Conexion manual a dispositivo guardado desde pantalla de Configuracion.
- Envio de alertas a Telegram (`sendMessage`) con formato enriquecido (`parse_mode=HTML`).
- Configuracion de Chat ID desde la app (almacenado en SharedPreferences).
- Visualizacion de logs de comunicacion serial y logs HTTP.
- UI principal adaptada a FallAlert (Home + Configuracion + Logs).

## Arquitectura resumida

- Origen de evento: detector de caidas (Arduino).
- Transporte: Bluetooth (app Android).
- Servicio: SerialService (recepcion serial + envio HTTP + notificacion en background).
- Destino remoto: Telegram Bot API.

## Requisitos

- Android Studio (version reciente).
- JDK 21 (o version compatible con tu Android Studio).
- Gradle Wrapper 8.9 (incluido en el repositorio).
- Android Gradle Plugin 8.5.2 (configurado en el proyecto).
- Dispositivo Android fisico para pruebas Bluetooth.
- Bot de Telegram y Chat ID de grupo/canal para recibir alertas.

## Configuracion de Telegram

1. Crea un bot con @BotFather (si aun no lo tienes).
2. Agrega el bot al grupo/canal destino (por ejemplo: `@arduino_detector_caidas_bot`).
3. Obtiene el Chat ID con @Getmyid_Work_Bot:
	- enviar `/start`
	- reenviar un mensaje del grupo/canal al bot
	- copiar el Chat ID devuelto
4. Dentro de la app, abre Configuracion y guarda ese Chat ID.

## Seguridad de credenciales

Este proyecto lee el token desde `local.properties` mediante `BuildConfig`:

```properties
telegram.bot.token=TU_TOKEN_AQUI
```

`local.properties` ya esta ignorado por Git.

## Ejecucion local

1. Clona este repositorio.
2. Abre el proyecto en Android Studio.
3. Agrega `telegram.bot.token` en `local.properties`.
4. Sincroniza Gradle.
5. Ejecuta la app en un dispositivo fisico Android.
6. Configura Chat ID en la pantalla de Configuracion.
7. Selecciona y conecta el dispositivo Bluetooth guardado.
8. Inicia la escucha en segundo plano y valida que la alerta llegue a Telegram.

## Notas de uso

- La app no fuerza conexion al abrirse; la conexion se inicia desde Configuracion.
- Si no hay Chat ID guardado, se usa un Chat ID por defecto definido en codigo.
- Los resultados de envio HTTP aparecen en la vista de logs.

## Roadmap corto

- Mejorar validacion y normalizacion de mensajes entrantes desde Arduino.
- Agregar politicas de reintento/backoff para errores de red.
- Separar configuraciones por entorno (dev/test/prod).
- Agregar pruebas instrumentadas para flujo Bluetooth + Telegram.

## Creditos

Este proyecto toma como base:

- [kai-morich/SimpleBluetoothLeTerminal](https://github.com/kai-morich/SimpleBluetoothLeTerminal)

Gracias a [Kai Morich](https://github.com/kai-morich) por el trabajo original.

## Proyecto derivado y atribucion

Este proyecto es un trabajo derivado de SimpleBluetoothLeTerminal.

- Codigo base original: [kai-morich/SimpleBluetoothLeTerminal](https://github.com/kai-morich/SimpleBluetoothLeTerminal)
- Autor original del codigo base: Kai Morich
- Licencia del codigo base: MIT

Se conserva el aviso de copyright y la licencia MIT en este repositorio.

## Licencia

Este repositorio mantiene la licencia MIT.

Consulta el texto completo en [LICENSE.txt](LICENSE.txt).
