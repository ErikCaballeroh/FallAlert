# FallAlert (Android)

Aplicacion Android para recibir una alarma de caida enviada por un detector basado en Arduino por Bluetooth, y luego disparar una alerta remota consumiendo una API para envio de SMS.

## Estado del proyecto

En desarrollo.

El objetivo actual es validar de punta a punta este flujo:

1. El detector de caidas en Arduino identifica un evento de riesgo.
2. Arduino envia un mensaje de alarma por Bluetooth a la app Android.
3. La app interpreta el mensaje y activa una alerta.
4. La app llama una API externa para enviar SMS a contactos de emergencia.

## Caracteristicas

- Recepcion de mensajes desde un dispositivo Bluetooth.
- Parseo de eventos de alarma enviados por el detector de caidas.
- Integracion con API HTTP para envio de SMS.
- Base de codigo pensada para iterar rapido durante pruebas.

## Arquitectura (resumen)

- Origen de evento: detector de caidas (Arduino).
- Transporte: Bluetooth.
- Cliente movil: app Android (este repositorio).
- Notificacion externa: API de mensajeria SMS.

## Requisitos

- Android Studio.
- SDK de Android compatible con este proyecto.
- Dispositivo Android con Bluetooth habilitado.
- Detector de caidas Arduino configurado para enviar mensajes.
- Endpoint/API de SMS disponible para pruebas.

## Ejecucion local

1. Clona este repositorio.
2. Abre el proyecto en Android Studio.
3. Sincroniza Gradle.
4. Ejecuta la app en un dispositivo fisico Android (recomendado para Bluetooth).
5. Empareja o conecta con el modulo Bluetooth del detector.
6. Simula una caida y verifica que se reciba la alarma y se dispare la llamada a la API de SMS.

## Roadmap corto

- Mejorar validacion de mensajes recibidos desde Arduino.
- Robustecer reintentos y manejo de errores al llamar la API SMS.
- Agregar logs y trazabilidad de eventos de alarma.
- Definir pruebas de integracion para flujo Bluetooth + API.

## Creditos

Este proyecto toma como base el repositorio:

- [kai-morich/SimpleBluetoothLeTerminal](https://github.com/kai-morich/SimpleBluetoothLeTerminal)

Gracias a [Kai Morich](https://github.com/kai-morich) por el trabajo original y por publicar una base clara para comunicacion Bluetooth en Android.

## Proyecto derivado y atribucion

Este proyecto es un trabajo derivado de SimpleBluetoothLeTerminal.

- Codigo base original: [kai-morich/SimpleBluetoothLeTerminal](https://github.com/kai-morich/SimpleBluetoothLeTerminal)
- Autor original del codigo base: Kai Morich
- Licencia del codigo base: MIT

De acuerdo con la licencia MIT, se conserva el aviso de copyright y el texto de licencia en este repositorio.

## Licencia

Este repositorio mantiene la licencia MIT.

Consulta el texto completo en [LICENSE.txt](LICENSE.txt).
