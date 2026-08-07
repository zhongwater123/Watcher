

# Watcher

Watcher es una aplicación para Android enfoc diseñada para la observación de vídeo asistida por IA y de ejecución prolongada.

Combina la monitorización en tiempo real, el análisis de vídeo segmentado, los comentarios en vivo, la interacción con la audiencia, la revisión por múltiples expertos, los experimentos con modelos locales y el modelado de comportamiento a largo plazo en torno a un mismo flujo de vídeo.

## Estado del proyecto

Watcher es un prototipo de producto en evolución activa, no un SDK finalizado ni una versión pulida para el usuario final.

Lo que existe actualmente:

- Una aplicación Android de móduloun único módulo construida con Kotlin y Jetpack Compose
- Flujos de monitorización en tiempo real sobre flujos MJPEG como `ESP32-CAM`
- Análisis de vídeo planificado por IA con grabación segmentada y resumen de resúmenes
- Modos de transmisión en apaisado para comentarios, interacción activada por voz y análisis experto estilo "consejo"
- Persistencia local para historial, plantillas, proveedores, estado de la audiencia, conocimiento y modelado de comportamiento
- Una API de puerta de enlace LAN integrada para automatización local y control externo
- Un marco de agentes integrado y un punto de entrada separado para modelos locales LiteRT

Lo que se puede esperar:

- Iteraciones rápidas
- Puliado incompleto de la interfaz de usuario
- Superficies de configuración que aún están en evolución
- Funcionalidades internasde investigación interna que son utilizables pero aún no son estables

## Qué hace Watcher

Watcher trata un flujo de vídeo como un contexto de trabajo de larga duración en lugar de una entrada de inferencia única.

Flujos de trabajo principales:

- Monitorización en tiempo real
  Convierte una solicitud de monitorización en lenguaje natural en una tarea estructurada, sondea el flujo a intervalos, detecta cambios o la presencia de objetivos, y persiste alertas, capturas de pantalla y medios de la sesión.
- Análisis de vídeo
  Convierte una solicitud más extensa en un plan, graba múltiples segmentos, analiza cada uno y genera un resumen final con eventos de línea de tiempo.
- Interacción en vivo
  En modo apaisado, ejecuta comentarios, interacción activada por voz y comportamiento de la audiencia impulsado por IA sobre el mismo flujo.
- Modo Consejo
  Ejecuta múltiples roles de expertos sobre el mismo contexto compartido y recopila un resultado sintetizado.
- Tarjeta de vida digital
  Acumula observaciones en registros estilo pizarra compartida, perfiles de escena, afirmaciones de comportamiento, registros de razonamiento y dimensiones del perfil.
- Experimentos con modelos locales
  Gestiona activos de modelos en dispositivo respaldados por LiteRT e inicializa entornos de inferencia locales de forma separ a los proveedores remotos.

## Resumen de la arquitectura

La aplicación es un único módulo de Gradle, pero el código fuentefuente está organizado como un sistema de múltiples bancos de trabajo.

Capas principales en tiempo de ejecución:

- `ui/screens` y `ui/viewmodel`
  Bancos de trabajo con Compose, modos inmersivos y estado de orquestación
- `data/repository`
  Flujos de monitorización, procesamiento de vídeo, comentarios, audiencia, consejo, historial, plantillas y comportamiento
- `data/local`
  Base de datos Room, almacenes persistentes, migraciones y configuración de LiteRT
- `data/remote`
  Servicios Retrofit, clientes de streaming, adaptadores de proveedores y APIs HTTP orientadas al dispositivo
- `data/gateway`
  API LAN integrada basada en NanoHTTPD
- `data/agent` y `agentframework`
  Abstracciones de agentes, herramientas, entornos de ejecución, persistencia y servicios de ejecución autónoma

Para un recorrido a nivel de código, consulta [docs/architecture-overview.md](docs/architecture-overview.md) y [docs/agent-framework.md](docs/agent-framework.md).

## Principales áreas de la aplicación

La superficie principal de la aplicación es el banco de trabajo `MainScreen`, que actualmente incluye:

- `Monitor`
- `Hub`
- `Analysis`
- `History`
- `Templates`

Puntos de entrada adicionales:

- `API Wallet`
- `Agent Config`
- `Digital Life Card`
- `LiteRt`

Modos inmersivos específicos para apaisado:

- `Live`
- `Council`

## Requisitos

- Android Studio con las herramientas actuales del SDK de Android
- JDK 11
- Android SDK 35
- Dispositivo o emulador de Android en Android 10+ (`minSdk = 29`)

Objetivos de compilación desde el código:

- `compileSdk = 35`
- `targetSdk = 35`
- `minSdk = 29`

## Inicio rápido

### 1. Clonar y abrir

Abre el repositorio en Android Studio y deja que Gradle sincronice el proyecto.

### 2. Preparar `local.properties`

`local.properties` se utiliza para la configuración durante el desarrollo. Como mínimo, Android Studio generalmente gestionará `sdk.dir` por ti. Las credenciales opcionales de la aplicación también pueden residir aquí.

Ejemplo:

```properties
API_KEY=your_remote_model_api_key
VOLCENGINE_ASR_APP_KEY=your_volcengine_asr_app_key
VOLCENGINE_ASR_ACCESS_KEY=your_volcengine_asr_access_key
VOLCENGINE_ASR_RESOURCE_ID=volc.seedasr.sauc.duration
```

Valores opcionales de firma para lanzamiento:

```properties
RELEASE_STORE_FILE=keystore.jks
RELEASE_STORE_PASSWORD=your_store_password
RELEASE_KEY_ALIAS=your_alias
RELEASE_KEY_PASSWORD=your_key_password
```

Notas:

- `API_KEY` solo se inyecta en la compilación `debug` a través de `BuildConfig`.
- Los proveedores de modelos en tiempo de ejecución también se pueden configurar dentro de la aplicación a través de `API Wallet`.
- No comprometas (`commit`) `local.properties`.

### 3. Compilar e instalar

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
```

Comandos de verificación útiles:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat connectedDebugAndroidTest
.\gradlew.bat lintDebug
```

### 4. Flujo de primera ejecución

Flujo sugerido para la primera ejecución:

1. Abre la aplicación y configura una URL de flujo en el cuadro de ajustes de la cámara.
2. Confirma que los cuadrosframes en vivo son visibles.
3. Prueba primero `Monitor` para entender el bucle de tarea a alerta.
4. Prueba a continuación `Analysis` para ver la grabación segmentada y la generación de resúmenes.
5. Gira a modo apaisado y entra en `Live` o `Council`.
6. Inspecciona `History` para verificar que los medios, eventos y resúmenes se persisten correctamente.
7. Explora `API Wallet`, `Agent Config`, `Digital Life Card` y `LiteRt` una vez que el flujo de vídeo principal funcione.

## Modelo de configuración

### Flujo de vídeo y dispositivos

Watcher está diseñado principalmente en torno a flujos de dispositivo estilo MJPEG y actualmente incluye:

- Configuración de URL de flujo
- Escaneo de dispositivos LAN
- Actualización de información del dispositivo
- Ayudas de aprovisionamiento para dispositivos compatibles
- Controles relacionados con LED para cámaras compatibles

### Proveedores de modelos

Watcher ahora utiliza una billetera de proveedores en tiempo de ejecución en lugar de asumir un único backend codificado fijo.

Comportamiento de los proveedores:

- Los proveedores se almacenan en Room con los secretos trasladmovidos a un almacenamiento local cifrado
- Se puede seleccionar un proveedor predeterminado en la aplicación
- Algunas funcionalidades pueden recurrir a la ruta `API_KEY` compatible con Ark cuando no hay ningún proveedor seleccionado
- El estado de conectividad de los proveedores se almacena en caché localmente para proporcionar información de estado a la interfaz

### Soporte para modelos locales

El soporte de LiteRT se integra como un punto de entrada separado:

- instalaciónación de activos empaquetados
- Persistencia de configuración
- Resolución de rutas de modelos
- Auto-inicialización opcional al arraninicio
- Descargas de modelos locales y flujos de recarga del motor

## Estructura del proyecto

```text
app/
  src/main/java/com/example/watcher/
    agentframework/         Entorno de ejecución de agentes independienteomo y servicios de persistencia
    data/agent/             Abstracciones y auxiliares de agentes orientados a la aplicación
    data/gateway/           API de puerta de enlace integrada
    data/local/             Room, almacenamiento de la aplicación, almacenes locales de LiteRT
    data/model/             Entidades y modelos de dominio
    data/remote/            Servicios Retrofit y adaptadores de proveedores
    data/repository/        Flujos de trabajo principales y lógica de orquestación
    ui/components/          Bloques de construcción reutilizables con Compose
    ui/screens/             Páginas de bancosbancos de trabajo y superficies inmersivas
    ui/theme/               Tema de Compose
    ui/viewmodel/           Modelos de vista y delegados de flujos de trabajo
  src/main/res/             Recursos de Android
  src/test/                 Pruebas unitarias JVM
  src/androidTest/          Pruebas de instrumentación y de interfaz de usuario con Compose
docs/                       Notas de arquitectura, iteraciones y referencias técnicas
mcp/                        Servidor MCP Watcher sin construccióncompilación para herramientas de puerta de enlace genéricas
tools/                      Scripts de ayuda
```

## Puntos de entrada principales

Orden de lectura recomendado:

1. [app/src/main/java/com/example/watcher/ui/screens/MainScreen.kt](app/src/main/java/com/example/watcher/ui/screens/MainScreen.kt)
2. [app/src/main/java/com/example/watcher/ui/viewmodel/IntentViewModel.kt](app/src/main/java/com/example/watcher/ui/viewmodel/IntentViewModel.kt)
3. [app/src/main/java/com/example/watcher/data/repository/MonitorManager.kt](app/src/main/java/com/example/watcher/data/repository/MonitorManager.kt)
4. [app/src/main/java/com/example/watcher/data/repository/VideoProcessRepository.kt](app/src/main/java/com/example/watcher/data/repository/VideoProcessRepository.kt)
5. [app/src/main/java/com/example/watcher/ui/viewmodel/LiveInteractionController.kt](app/src/main/java/com/example/watcher/ui/viewmodel/LiveInteractionController.kt)
6. [app/src/main/java/com/example/watcher/WatcherApplication.kt](app/src/main/java/com/example/watcher/WatcherApplication.kt)
7. [app/src/main/java/com/example/watcher/data/gateway/GatewayServer.kt](app/src/main/java/com/example/watcher/data/gateway/GatewayServer.kt)
8. [docs/architecture-overview.md](docs/architecture-overview.md)
9. [docs/agent-framework.md](docs/agent-framework.md)

## Mapa de documentación

- [CONTRIBUTING.md](CONTRIBUTING.md)
- [AGENTS.md](AGENTS.md)
- [CLAUDE.md](CLAUDE.md)
- [docs/architecture-overview.md](docs/architecture-overview.md)
- [docs/agent-framework.md](docs/agent-framework.md)
- [docs/2026-04-07-database-field-summary.md](docs/2026-04-07-database-field-summary.md)
- [docs/2026-03-26-product-iteration.md](docs/2026-03-26-product-iteration.md)
- [docs/2026-03-27-product-iteration.md](docs/2026-03-27-product-iteration.md)
- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)

La documentación fechada bajo `docs/` son registros históricos de diseño e iteración, no la fuente de verdad canónica para el comportamiento actual.

## Contribuir

Consulta [CONTRIBUTING.md](CONTRIBUTING.md) para obtener información sobre la configuración, expectativas de pruebaspruebas, convenciones de código y pautas para solicitudes de `pull`.

## Notas de seguridad

- `local.properties` puede contener secretos de desarrollo y debe mantener excluido de los compromisos.
- `network_security_config.xml` permite tráfico de texto claro para escenarios de dispositivo local; revisa cualquier nueva excepción con cuidado.
- La puerta de enlace integrada está diseñada para un uso en LAN de confianza, no para exposición en internet público.
- Los medios históricos, capturas de pantalla y registros pueden contener datos sensibles de la escena local. Maneja los artefactos exportados en consecuencia.

## Avisos de terceros

Los componentes de terceros y los avisos de licencia se registran en [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

La integración de LiteRT se basa en Google AI Edge LiteRT-LM:

- Repositorio upstream: <https://github.com/google-ai-edge/LiteRT-LM>
- Licencia: Apache License 2.0

## Estado de la licencia del repositorio

Este repositorio incluye avisos de terceros, pero actualmente no contiene un archivo de licencia de proyecto de nivel superior. Añade una licencia de proyecto explícita antes de tratar el repositorio como una distribución de código abierto publicada.
