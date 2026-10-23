# Parquex — mapa 3D nativo de los parques de Grupo Xcaret

App Android nativa (Kotlin + Jetpack Compose + Material 3) que muestra en 3D los parques de Grupo Xcaret
(Xcaret `xc`, Xel-Há `xh`, Xplor `xp`, Xenses `xs`, Xoximilco `xo`) con el mismo estilo low-poly "caricatura" del mapa web hecho con three.js
(proyecto web `xh`, repo aparte: ver su `README.md` y `docs/arquitectura.md`). El objetivo es que sea
**muy rápida en teléfonos modestos**: el trabajo pesado se hace offline en el proyecto web y la app solo
carga un `.glb` (`park-models.glb`) y lo dibuja con **Google Filament**.

**Suelo como en la web**: dentro del área diseñada del parque (`park.json → clear`) va el suelo ilustrado del GLB
(terreno con sombras horneadas, agua y empedrado) con los modelos 3D encima; fuera, el **mapa real de
OpenStreetMap** (teselas raster en y = 0), mezclado sobre el parque en una franja de −45 m … +5 m del contorno
(`ParkData.DesignArea.fade`, igual que `shared/design-area.js`). Si el GLB no trae `terrain:*`, el mapa OSM se ve en
todas partes (modo solo-modelos).

- Paquete: `com.faustinoloeza.parquex` · minSdk 24 · target/compileSdk 37
- AGP 9.3.3 con Kotlin integrado; **Kotlin 2.4.21** (Filament 1.77 trae kotlin-stdlib 2.4; con KGP 2.2 no compila).
  La versión de KGP se sube declarando `org.jetbrains.kotlin.android` con `apply false` en el `build.gradle.kts` raíz.
- Dependencias en `gradle/libs.versions.toml`. Filament **1.77.3**: `filament-android` + `gltfio-android`.
  `filament-utils-android` **no** se usa (su `Manipulator` en modo MAP es solo cenital, sin giro ni inclinación;
  la cámara es propia). Agrégalo solo si se necesitan sus cargadores KTX/HDR.

## Compilar y probar

```
./gradlew assembleDebug
./gradlew installDebug
```

El daemon de Gradle pide JDK 25 (`gradle/gradle-daemon-jvm.properties`). Android Studio lo resuelve solo; desde
la terminal, si falla la descarga del toolchain, usa el JBR de Android Studio:

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug -Porg.gradle.java.installations.paths="$JAVA_HOME"
```

Emulador: `~/Library/Android/sdk/emulator/emulator -avd Pixel_10 -no-window -no-audio` y captura con
`adb exec-out screencap -p > captura.png`. Apaga el emulador al terminar (`adb emu kill`).

## Cómo se producen los assets

Los modelos **no se editan aquí**. Se generan en el proyecto web:

```
cd xh                   # el proyecto web (repo aparte)
npm run export:mobile   # = export:glb --mobile --android; copia park-models.glb + park.json a este proyecto
```

`--android` copia el resultado a `app/src/main/assets/parks/<key>/` (xc, xh, xp, xs, xo). Orden y nombres del selector:
`PARK_KEYS` y `parkName()` en `ParkMapScreen.kt` (+ `park_<key>` en strings). Xplor, Xenses y Xoximilco no tienen mapa
oficial (suelo de satélite + OSM) ni rutas: sus puntos se agrupan por categoría, como Xel-Há. `--mobile` exporta modelos + suelo
ilustrado + agua + empedrado, sin rutas ni capa `shadows`. (`--models-only` produce `park-models-only.glb`: sin
terreno ni agua y con capa `shadows`, pensado para OSM en todas partes; no se copia a la app.) El script avisa si alguna
textura sale de 1×1 (pasó una vez: el render de sombras liberaba las imágenes). Si quedan `park.glb` o
`park-merged.glb` en `assets/parks/*/`, **bórralos**: solo hacen el APK más pesado. En assets solo deben quedar:

| Archivo           | Contenido                                                                                                  |
| ----------------- | ---------------------------------------------------------------------------------------------------------- |
| `park-models.glb` | glTF 2.0 binario, y arriba, metros. `terrain:*` (4 teselas JPEG de 1024² con sombras horneadas, cortado en el contorno + 5 m con borde dentado), `water` (BLEND), edificios/palapas fusionados por material, `paving` (empedrado sobre el relieve, heightAt + 0.05 m, UV en m/4 con REPEAT) y un prototipo `proto:<tipo>` por modelo repetido (árboles, palmeras, autobús), en el origen. Comprimido con `EXT_meshopt_compression` + `KHR_mesh_quantization` (requeridas; gltfio 1.77.3 las decodifica, verificado oct 2026), salvo los `proto:*` y `anim:*`: float32, índices uint16, en bufferViews normales del buffer 0, porque `InstancedModels` y `AnimatedModels` los leen del binario (`PlainMeshes`). La cuantización pone escala/traslación en los nodos de malla que no son `proto:*` ni `anim:*`: no leer sus posiciones crudas sin aplicar la transformación del nodo. PNG/JPEG embebidos (xc ≈ 3.0 MB / 59 draw calls, xh ≈ 2.0 MB / 23). Modelos a la altura del terreno (normalmente < 1–2 m). Sin compresión: `npm run export:glb -- --mobile --no-meshopt --android` |
| `instances.bin`   | copias de los prototipos ("PQI1", little endian): `u32 tipos`; por tipo `u16 largo + nombre UTF-8`, `u32 n`, n × 28 bytes = `f32 x, y, z, rotY, escalaXZ, escalaY` + `u8 r, g, b` (tinte sRGB que multiplica a COLOR_0 en lineal) + `u8 0`. Matriz T · R_y · S. xc ≈ 11k copias (308 KB), xh ≈ 15k (402 KB). Lo arma `InstancedModels` |
| `context.glb`, `context-instances.bin`, `context.json` | solo xc, xp, xs: **paquete de contexto** (parques vecinos + "Zona Xcaret" en versión ligera), ya recortado y fundido por el exportador, en el marco del parque enfocado. `ctx-ground:<key>` (BLEND, textura 1024², alfa por vértice) y `ctx-water:<key>`; árboles en `context-instances.bin` con los `proto:*` del parque; `context.json → neighbors[]` da prioridad, `offset` (cambio de marco), `clear`, rangos de árboles por tipo y fracción por calidad. Formato: README de la web y `docs/arquitectura.md` ("Parques vecinos en la app") |
| `animated.json`   | piezas que se mueven (solo xc: cabina y bandera de la Torre Escénica, voladores, bandera monumental, mariposas/aves de los domos y 5 bandadas): por pieza `driver`, posición y `rotY` del landmark, `box` con todo su recorrido, `size` (m de su figura más chica), `params` del `update()` de la web y `parts` = nodos `anim:<pieza>:<i>` de `park-models.glb` (en su propio marco, float32 sin meshopt como los `proto:*`, siempre con COLOR_0). El exportador ya no las congela en `static`. Lo arma `AnimatedModels`. Contrato: `docs/app-movil-noche-navegacion.md` → Animaciones |
| `night.json`, `lamps.bin`, `context-lamps.bin` | modo noche: luces día/noche, fuerza emisiva por material, agua y mapa de noche, estilo de faroles; faroles "PQL1" (x, y, z del pie + tipo suelo/muelle, registros de `recordBytes`). `nightlight.png` no se copia a la app: el mapa de luz ya va dentro del GLB (si aparece en assets, bórralo). Contrato completo: `docs/app-movil-noche-navegacion.md` de la web |
| `park.json`       | datos del parque: centro lat/lng, `bounds`, `extent`, `pois` (actividades), `routes`, `walkways` (andadores de OSM; los cruces comparten coordenadas exactas), `services`, `hours`, `piers`, área diseñada (`clear`), `groundHoles` (opcional: huecos del suelo con puente, `{ rings: [[[x, z], …], …], y }`, anillos par-impar + altura absoluta de la cubierta, como `shared/ground-holes.js`; hoy el foso de los felinos de xc) |

El nombre del modelo es la constante `ParkRenderer.MODEL_FILE`.

**Capas del suelo en `ParkRenderer`** (todas en el canal del mapa, sin proyectar sombra): `terrain:*` escribe
profundidad (los modelos se ordenan contra él); `paving` y `shadows` (si viniera, variante solo-modelos) van sin prueba
ni escritura de profundidad, así no hay z-fighting. `FilamentAsset.getEntitiesByPrefix` devuelve vacío en gltfio
1.77.3: para buscar nodos se recorren `asset.entities` con `getName()`.

**Por qué no `park.glb` (instanciado)**: se comprobó en el emulador (oct 2026, Filament 1.77.3) que gltfio **no
dibuja** los árboles de `EXT_mesh_gpu_instancing`. Con la misma cámara, `park.glb` mostró solo el suelo
(69 renderables) y la variante fusionada (`park-merged.glb`, hoy `park-models.glb`) el bosque completo (88 renderables). gltfio no registra ninguna advertencia en
logcat. El `.so` contiene la cadena `EXT_mesh_gpu_instancing`, pero eso no basta para que se dibuje. Si una versión
futura de Filament lo soporta, volver a probar con las dos capturas antes de cambiar `MODEL_FILE` (el instanciado pesa
la mitad).

**Nodos que la app oculta si vinieran en el GLB** (`ParkRenderer.HIDDEN_NODES`): `route:*` (las rutas las dibuja
`RouteLayer` desde `park.json`), `proto:*` (moldes de `InstancedModels`) y `anim:*` (moldes de `AnimatedModels`; sus
materiales sí entran a `materialsByName`: el vidrio de la cabina brilla de noche).

`app/build.gradle.kts` declara `androidResources.noCompress += glb, filamat, ktx, ktx2`: el `.glb` se **mapea en
memoria** desde el APK (`AssetManager.openFd` + `FileChannel.map`) sin copiarlo al heap de Java. Si falta
`park-models.glb` la app muestra un aviso en lugar de fallar.

## Convenciones

- Coordenadas locales en **metros**: `x` = este, `z` = sur (norte = −z), `y` = altura; origen en el centro del
  mapa del parque (`park.json → center`). El `.glb` y `park.json` comparten este sistema.
- lat/lng ↔ local: proyección equirectangular de `shared/geo.js` (web), replicada en `ParkData.toLocal()` /
  `toLatLng()`. Precisión de centímetros a escala de parque.
- Ángulos en radianes. Cámara: `yaw` 0 = norte arriba; `pitch` = ángulo desde la vertical (0 = cenital).
- Sol: dirección `[-0.55, 1.3, 0.5]` (hacia el sol), igual que `shared/scene.js` de la web — las sombras
  horneadas del suelo dependen de ella. Si cambia en la web, cambiarla en `ParkRenderer`.
- Textos de UI en `res/values/strings.xml` (español) y `res/values-en/strings.xml` (inglés); nada de cadenas fijas en
  Compose. El idioma es **de la app** (Ajustes → Idioma, `AppSettings`, por defecto el del sistema si es es/en): lo
  aplica `ProvideLanguage` con un contexto con ese locale, sin recrear la actividad ni recargar el 3D. Los popups y
  diálogos (`DropdownMenu`, `ModalBottomSheet`) vuelven a poner el contexto de su ventana: envolver su contenido en
  `PopupLanguage`. Los datos de `park.json` se leen con `lang` (respaldo `es`); los grupos y la relación actividad →
  ruta usan los textos en español como clave estable (`routeKey`, `categoryKey`, `nameKey`).
- Tema claro fijo (`ui/theme`), sin colores dinámicos; borde a borde con `WindowInsets.safeDrawing`.
- Sin `Skybox`: el cielo es el color de limpieza del `Renderer` (el skybox se pinta donde no hay profundidad y
  taparía el mapa y las rutas, que no la escriben).
- Todo Filament se usa desde el **hilo principal**. Cada objeto creado debe destruirse en `ParkRenderer.destroy()`.

## Arquitectura

```
MainActivity (edge-to-edge) ─► ParkMapScreen (Compose)
                                ├─ AndroidView(SurfaceView) ◄── MapGestures (OnTouchListener)
                                ├─ LocationLayer (punto azul real o simulado, Canvas)
                                ├─ MarkerLayer (marcadores: solo los grupos activos)
                                ├─ barra: lista de actividades · píldora del parque (menú xc/xh + horario) · ajustes
                                │   (idioma, calidad gráfica, andadores, sombras, animaciones, simular ubicación)
                                ├─ ActivitiesSheet (hoja inferior: búsqueda + lista por número)
                                ├─ controles: zoom ±, brújula, encuadrar parque, mi ubicación
                                └─ abajo: aviso, tarjeta de actividad, chips de rutas/categorías, crédito OSM
ParkRenderer (map/)  Engine, Renderer, Scene, View, Camera, SwapChain (UiHelper), gltfio
                     AssetLoader + UbershaderProvider + 2 ResourceLoader (asíncronos), sol + IBL
  ├─ ParkBundle      un parque enfocado armado fuera de escena: modelo + copias (InstancedModels) + piezas animadas
  │                  (AnimatedModels) + contexto (context.glb + árboles); loadPark() lo carga mientras se ve el anterior
  │                  y swap() cambia en un cuadro
  ├─ GroundTiles     mapa OSM: selección de teselas por distancia, caché LRU en GPU ── TileLoader (red + caché HTTP)
  ├─ WalkwayLayer    andadores de park.json (OSM) punteados, delgados y neutros ── ScreenLines
  └─ RouteLayer      rutas de park.json con ancho constante en pantalla, resaltado de rutas activas ── ScreenLines
MapCamera (map/)     cámara de mapa: objetivo, distancia, yaw, pitch, límites, vuelos, inercia,
                     rayo pantalla→suelo, CameraSnapshot.project() mundo→pantalla, frustum
ParkData (data/)     modelo de park.json (org.json) + proyección lat/lng
ParkContext (data/)  modelo de context.json: vecinos, prioridades, offsets, árboles por calidad
location/            LocationTracker (GPS del sistema, sin Play Services) y SimulatedWalk (demo)
nav/                 NavGraph + Navigation: navegación a pie portada 1:1 de shared/nav-graph.js y navigation.js
                     de la web (pruebas en app/src/test/…/nav con los mismos números que tests/shared de la web)
data/AppSettings     idioma y calidad gráfica persistentes (SharedPreferences)
```

| Archivo                       | Responsabilidad                                                                            |
| ----------------------------- | ------------------------------------------------------------------------------------------ |
| `map/ParkRenderer.kt`         | objetos Filament, carga del GLB, bucle de render a demanda, luces, opciones de la View, canales, destroy |
| `map/GroundTiles.kt`          | mapa base OSM: teselas {z}/{x}/{y} en el suelo, ancestro recortado por UV mientras carga, tope de 48; con área diseñada no dibuja ni descarga dentro del parque y en el borde usa mallas de 24² con alfa por vértice |
| `map/TileLoader.kt`           | descarga/decodifica teselas en hilos (User-Agent propio, `HttpResponseCache` 96 MB, sin red usa caché) |
| `map/ScreenLines.kt`          | polilíneas → cintas de ancho constante en pantalla, recalculadas en CPU al mover la cámara; altura por punto y cinta `flat` (acostada en el plano horizontal, para prueba de profundidad); `PositionRing` (4 búferes de posición) |
| `map/RouteLayer.kt`           | rutas oficiales: contorno + trazo por ruta, las activas más gruesas y encima               |
| `map/WalkwayLayer.kt`         | andadores: contorno #4A3B2C al 50 % + guiones #FFFAF0 (textura 16×1 premultiplicada, periodo en m redondeado a potencia de 2 según el zoom); omite tramos a ≤ 4 m de una ruta; los tramos que cruzan un hueco con puente van sobre la cubierta en el canal 5 |
| `map/Unlit.kt`                | materiales unlit tomados del ubershader de gltfio (no hace falta `matc`) y formato de vértice común |
| `map/InstancedModels.kt`      | prototipos `proto:*` (leídos del binario del GLB) + `instances.bin` → mallas fusionadas por (material, zona de 350 m, canal) con el MaterialInstance de gltfio; tinte horneado en el color de vértice |
| `map/MapCamera.kt`            | estado y matemáticas de la cámara (sin Filament); `snapshot()` inmutable para Compose      |
| `map/MapGestures.kt`          | un dedo = paneo con inercia; dos dedos = zoom + giro + paneo; dos dedos verticales = inclinar; doble toque = acercar |
| `data/ParkData.kt`            | parseo de `park.json`, `toLocal(lat,lng)` / `toLatLng(x,z)`                                 |
| `ui/ParkMapScreen.kt`         | pantalla, controles, selector de parque, estados de carga                                  |
| `ui/MarkerLayer.kt`           | marcadores: se miden una vez y se **recolocan** solo cuando cambia la cámara               |
| `ui/ActivitiesSheet.kt`       | lista de actividades: búsqueda sin acentos por nombre/número/categoría; elegir vuela la cámara y selecciona |
| `ui/Localization.kt`          | `ProvideLanguage` / `PopupLanguage`: idioma propio de la app sobre Compose               |
| `ui/Navigation.kt`            | `NavSession` (destino, salida, ruta, guía, simulación) y vistas: hoja de indicaciones, letrero verde, barra inferior, pin |
| `map/AnimatedModels.kt`       | piezas de `animated.json`: un renderable por pieza (una primitiva por material, copias seguidas: de noche se dibujan solo las despiertas); cada cuadro de animación transforma en CPU los vértices de las piezas a la vista (~3.6 k en xc) y sube posiciones y tangentes con búferes rotativos; cuerdas de los voladores = 2 planos cruzados de ≥ 1.2 px |
| `map/AnimationDrivers.kt`     | port 1:1 (Kotlin puro) de los `update()` de la web: cabina, voladores, tela de bandera, mariposas, bandadas y aleteo de `flap.js`; pruebas en `AnimationDriversTest` con números sacados de three.js |
| `map/PlainMeshes.kt`          | lector de las mallas planas del GLB (`proto:*`, `anim:*`) con el MaterialInstance de gltfio |
| `data/ParkAnimations.kt`      | modelo de `animated.json` (drivers desconocidos se omiten)                                 |
| `map/LampLayer.kt`            | faroles de noche: postes en una malla (ocultos a > 650 m) y cabezas aditivas de cara a la cámara (≥ 3.2 dp) |
| `map/NavLineLayer.kt`         | línea de la ruta a pie encima de todo, tramo caminado en gris (color por vértice)          |
| `data/ParkNight.kt`           | `night.json` y `lamps.bin`; `isNightNow()` = 19:00–06:00 en UTC−5 fijo                     |
| `ui/PoiFilters.kt`            | grupos de puntos: por ruta (`poi.route` = `route.name`), por categoría si no hay rutas (xh), "Otros" |
| `ui/LocationLayer.kt`         | punto azul (+ halo de precisión) leído en la fase de dibujo                                |
| `location/LocationTracker.kt` | `LocationManager` GPS + red, solo en primer plano; permiso al tocar "Mi ubicación"         |
| `location/SimulatedWalk.kt`   | demo: punto que recorre una ruta (xc) o los andadores de OSM (xh) a 6 m/s                  |

**Orden de dibujo (canales de Filament)**: 0 suelo del parque → 1 contexto (suelo de la región y los vecinos, mezclado
y sin profundidad, de la menor a la mayor prioridad: región primero; su agua al final con prueba de profundidad) →
2 árboles (del parque y de contexto) → 3 andadores → 4 rutas (andadores y rutas sin profundidad: encima del suelo y de
los árboles) → 5 edificios, demás modelos, piezas animadas y postes de faroles (tapan andadores y rutas que quedan detrás de ellos, como
la web; aquí también los tramos de andador sobre un puente, translúcidos y con prueba de profundidad) → 6 cabezas de faroles (aditivas) → 7 línea de navegación (encima de todo). Andadores y rutas van en canales distintos porque, dentro de un canal, Filament dibuja
lo opaco antes que lo translúcido sin importar la prioridad (el contorno de los andadores es translúcido). Dentro del canal 0,
por prioridad: color de tierra (0) → teselas OSM opacas (1) → terreno (2, escribe profundidad) → empedrado (3) →
translúcidos: agua (2), `shadows` (4) y teselas OSM del borde (6, encima del terreno). Dentro del canal 2: contornos,
trazos, y las rutas activas encima.

**Alfa premultiplicado** (verificado en el código de Filament 1.77): los BLEND de gltfio usan blending FADE, que mezcla
con ONE / ONE_MINUS_SRC_ALPHA y asume `baseColor` premultiplicado; `base.mat.in` **no** premultiplica el alfa del color de
vértice (solo en TRANSPARENT y antes de `getColor()`). Por eso todo nodo BLEND con alfa por vértice debe traer
**COLOR_0 premultiplicado** (rgb·a, a): `ctx-ground`, `ctx-water` y el `water` del parque (lo hace el exportador). Si no,
se suma en vez de mezclarse y aparece un halo claro en las franjas de fundido. Igual con los unlit de la app:
`UnlitMaterials.staticAttributes` escribe (α, α, α, α) y `blendedColor` premultiplica `baseColorFactor`.

**Modo noche** (Ajustes → Modo: Automático / Día / Noche, persistido; automático = 19:00–06:00 hora de Cancún, UTC−5
fijo, revisado cada minuto): nivel k con transición de 1.5 s (smoothstep). Por k se interpolan luces, cielo y exposición
(`night.json → lighting`), `emissiveStrength` por material (ventanas, mapa de luz del suelo y del agua), el agua
(`baseColorFactor` × nightMultiply), las teselas OSM (× nightTint) y los faroles; la interfaz pasa a azul marino con
k > 0.5 (`LocalMapColors`). **gltfio 1.77.3**: para que se vea el emisivo hay que escribir `emissiveFactor` (deja 0 si
la fuerza del archivo es 0) y `emissiveUvMatrix` = identidad (la deja en ceros sin KHR_texture_transform y la
textura emisiva se leería en (0, 0)); `MaterialInstance.getMaterial()` puede ser null en sus instancias.

**Navegación a pie**: «Cómo llegar» en la tarjeta de la actividad y «Ir» en la lista. Salida: punto elegido en el
mapa > GPS dentro del parque o a ≤ 300 m > entrada simulada (`park.json → entrance`). Vista previa con minutos (a
1.25 m/s), distancia y pasos; Iniciar (GPS real, recalcula a > 25 m cada ≥ 4 s) o Simular 1×/4×/10×. Cámara de
seguimiento 22 m por delante, 170 m, inclinación 0.92 rad, la marcha hacia arriba; un gesto la pausa («Recentrar»).
Cambiar de parque cancela la navegación. Rutas oficiales atenuadas = más delgadas (son opacas; sin opacidad 0.4).

**Parques vecinos (Xcaret, Xplor, Xenses)**: con el foco en uno, los otros dos y la "Zona Xcaret" se ven ligeros
(suelo, agua y una fracción de árboles; sin puntos ni modelos), siempre, también en Baja (menos árboles:
`context.json → trees.quality`). El mapa OSM no se dibuja donde la región lo tapa y en la franja de fundido su alfa se
corrige con `f·(1−r)/max(1−r·f, 1e-4)` (`GroundTiles.setRegion`). Cambiar de parque no recarga la escena: el nuevo se
arma fuera de escena (la píldora muestra "Cargando…") y cambia en un cuadro; si es vecino, la cámara pasa al marco nuevo
con `neighbors[nuevo].offset` del paquete anterior (`MapCamera.reframe`, sin salto) y luego vuela a encuadrarlo.
Xel-Há y Xoximilco no tienen paquete de contexto.

**Andadores**: `park.json → walkways` es una red limpia (líneas centradas en los andadores, unidas solo en sus extremos
con coordenadas idénticas; xh en un solo componente conexo). Se dibujan punteados por defecto; Ajustes → "Andadores"
los oculta. La ubicación simulada de Xel-Há camina por esa misma red.
Andadores, rutas y línea de navegación van en un plano (y ≈ 0, sin el relieve), salvo sobre un hueco del suelo con puente
(`park.json → groundHoles`, `ParkData.surfaceAt`): ahí la línea de navegación y el punto azul suben a la cubierta, y el tramo de
andador que lo cruza (con los extremos en las plataformas) se dibuja en el canal 5 a cubierta + 0.5 m (como la web) con prueba de
profundidad y cinta acostada; en el canal 3 la cubierta lo taparía.

**Menú de puntos**: por defecto no se muestra ningún número. Cada chip (línea de color + `label.es`, o categoría en
Xel-Há) activa los puntos de su grupo, resalta su ruta (más gruesa y encima) y encuadra la cámara. Varios a la vez.

**Ubicación**: "Mi ubicación" pide permiso, vuela al punto si está dentro del parque (+15 %) o avisa a cuántos km
está. Ajustes → "Simular ubicación en el parque" mueve un punto para probar fuera del parque: por la ruta activa o
la más larga (xc) o, si no hay rutas (xh), en un paseo aleatorio con semilla fija por el grafo de `walkways`
(componente conectado más grande). El punto es Compose: moverse no pide cuadros a Filament.

**SurfaceView vs TextureView**: se usa `SurfaceView` (el compositor del sistema muestra su búfer directamente,
sin copia ni cuadro extra de latencia). Compose dibuja los controles y marcadores encima sin problema. Solo
cambiar a `TextureView` (`UiHelper.attachTo(textureView)`) si hiciera falta animar alfa/recortar la vista 3D
como un nodo más de Compose.

**Marcadores y proyección**: `ParkRenderer.cameraSnapshot` (estado de Compose) cambia solo cuando la cámara se
mueve. `CameraSnapshot.project(x, y, z, out)` da píxeles de pantalla sin asignar memoria. `MarkerLayer` lee el
snapshot **en la fase de colocación** del `Layout`: mover la cámara no recompone ni vuelve a medir, solo recoloca.
Oculta lo que queda detrás, fuera de pantalla o encimado (prioridad: seleccionado, luego número).
`ParkRenderer.projectToScreen()` es el atajo para otros overlays.

**Cámara**: vista inicial oblicua (`pitch` 0.62 rad ≈ 54° de elevación, como `polar` en la web) que muestra
**completo** `park.json → bounds` (la caja del área diseñada): `MapCamera.fitDistance` proyecta las 4 esquinas y deja
libres los lados (90 % de media pantalla) y la barra superior y los chips (76 % en vertical). "Ver todo el parque" y
los chips de grupo usan el mismo cálculo. Límites: objetivo dentro de `bounds` + 15 %, distancia 25 m … el mayor de
2.2 × tamaño y 1.25 × la vista inicial (en vertical el ancho manda), pitch ≤ 1.15 rad.
Los gestos anclan el punto del suelo bajo el dedo (rayo contra el plano y = 0), así el mapa se "pega" al dedo
también en vista oblicua.

## Reglas de rendimiento (no romperlas sin medir)

1. **Render a demanda**: `Choreographer` solo programa cuadros mientras algo cambia (gesto, vuelo, inercia,
   texturas cargando). Con el mapa quieto, **cero** cuadros. Cualquier cambio visible debe llamar
   `renderer.requestRender()`. Única excepción: las **piezas animadas** (Ajustes → «Animaciones», persistido, encendido por
   defecto; nunca en Baja): mientras alguna esté en el frustum y su `size` cubra ≥ 2 px, cuadros a 30 fps con
   `postFrameCallbackDelayed` (no despierta en cada vsync; `requestRender()` adelanta el cuadro). No cuentan como
   movimiento: ni resolución dinámica ni calidad automática. Sin piezas a la vista (o en Xel-Há, que no tiene), cero
   cuadros. Animaciones de todo el parque (agua, viento) siguen pendientes: ver roadmap.
2. **Calidad gráfica** (Ajustes, persistida; presets equivalentes a `QUALITY_PRESETS` de la web, en `ParkRenderer.PRESETS`):

   | Nivel | Escala quieta / al mover | AA | fps | Sombras en tiempo real | Detalle OSM |
   | ----- | ------------------------ | -- | --- | ---------------------- | ----------- |
   | Alta  | 1.0 / 0.6–1.0            | MSAA 2× | 60 | disponibles | ×1 |
   | Media | 0.75 / 0.5–0.75          | FXAA | 60 | disponibles | ×1.25 (teselas más gruesas) |
   | Baja  | 0.55 / 0.45–0.55         | FXAA | 30 | no | ×1.6 |

   **Auto** parte de `ParkRenderer.detectQuality` (poca RAM, ≤ 3 GB o ≤ 4 núcleos → Baja; ≤ 6 GB o < 8 núcleos →
   Media; si no, Alta) y baja un nivel si al mover el intervalo promedio de 45 cuadros pasa de 1.5× el objetivo. El
   tope de fps se aplica con salto de cuadros, `Renderer.FrameRateOptions.interval` y `Surface.setFrameRate` (API 30+).
3. **Resolución dinámica** (escala del preset) solo mientras la vista se mueve; 150 ms después de parar se
   dibuja un último cuadro a la escala fija del nivel.
4. **Sin sombras en tiempo real** por defecto: las sombras de árboles y edificios sobre el mapa vienen **horneadas**
   en la textura del terreno (depende del sol, ver Convenciones). "Sombras en tiempo real" (ajustes) activa un
   mapa de 1024² con una cascada (caro; solo para pruebas/equipos altos).
5. View: MSAA 2× solo en Alta (si no, FXAA), sin bloom/SSAO/SSR/niebla, búfer HDR `MEDIUM` (R11G11B10), tonemapping PBR Neutral
   (= `NeutralToneMapping` de three.js), exposición 1.05, blend opaco, frustum culling activado.
6. Luz: un direccional (2.3 lux) + IBL solo con armónicos esféricos de 2 bandas (hemisferio cielo/suelo de la web);
   **sin** cubemaps de reflexión.
7. **Árboles y modelos repetidos**: un prototipo por tipo + `instances.bin`; `InstancedModels` los **fusiona en el
   dispositivo por zona** (350 m) y material: pocos draw calls (xc ≈ 27), culling por zona y el archivo 8–10 MB más
   ligero. Armar ~11–15k copias tarda ~140 ms en el emulador. Nunca miles de nodos sueltos. Instancing de GPU
   descartado (probado en oct 2026): `EXT_mesh_gpu_instancing` no se dibuja con gltfio 1.77.3, e `InstanceBuffer`
   admite como máximo `Engine.getMaxAutomaticInstances()` = 64 copias (más lanza IllegalArgumentException).
   `instances(n)` sin búfer acepta hasta 32 767, pero requeriría un material propio (`matc`) que lea las
   transformaciones con `getInstanceIndex()`.
8. **Presupuesto de draw calls**: objetivo < 150 renderables visibles (meta < 100). Fusionar geometría estática por
   material en el exportador. Revisar con los logs `Parque listo: N renderables` (tag `ParkRenderer`) y
   `Teselas dibujadas: N` (tag `GroundTiles`). Medido (Pixel 10, emulador): Xcaret 84 del modelo + 12 de rutas +
   ~28 teselas (14 de ellas en el borde) en la vista general; Xel-Há 48 + ~24. Las teselas tienen tope de 48 (si se
   pasa, se eligen más gruesas).
9. **Texturas**: resto ≤ 1024²; con mipmaps. Teselas OSM: 256² sRGB con mipmaps (~350 KB), caché LRU de 80 en GPU
   (~28 MB; dentro del parque no se descargan) + texturas del GLB (xc ~20 MB, xh ~17 MB). Presupuesto de GPU
   orientativo: < 64 MB por parque.
13. **Teselas OSM**: `tile.openstreetmap.org` sirve para desarrollo y tráfico moderado; su política exige
    User-Agent propio (lo pone `TileLoader`), caché y el crédito «© colaboradores de OpenStreetMap» visible (abajo a
    la derecha, enlaza a /copyright). En producción cambiar `GroundTiles.URL_TEMPLATE` a un proveedor o servidor
    propio con el mismo esquema {z}/{x}/{y}. Sin red solo se ve lo que haya en caché (el resto, color de tierra); el
    parque en sí no necesita red.
10. Carga: `ResourceLoader.asyncBeginLoad` (texturas decodificadas en hilos de trabajo, se ven según llegan) y
    `releaseSourceData()` al terminar; el `.glb` mapeado se suelta en ese momento.
11. Compresión: **meshopt** + cuantización ya en uso (ver assets). Pendiente: **KTX2/Basis** (`KHR_texture_basisu`)
    para las texturas; gltfio 1.77 la soporta. Cambiarlo es solo del lado del exportador.
12. APK: en release agregar `ndk { abiFilters += listOf("arm64-v8a") }` (o App Bundle) — Filament ocupa ~6 MB por ABI.

## ROADMAP (prioridad de arriba hacia abajo)

1. **Validar con los GLB reales**: medir renderables, triángulos y memoria; ajustar presupuesto del exportador;
   comparar capturas con la web (`?still`).
2. ~~Rutas~~ (hecho: `RouteLayer` + chips). Falta: resaltar la ruta de la actividad seleccionada, flechas de sentido.
3. ~~"Estás aquí" por GPS~~ (hecho: `LocationTracker` + simulación) y ~~navegación a pie~~ (hecho). Falta: cono de rumbo.
4. **Búsqueda y filtros**: por nombre, categoría, incluido/costo extra; lista que vuela la cámara a la actividad
   (`MapCamera.flyTo`). Servicios (`park.json → services`: baños, etc.) como capa aparte.
5. ~~Mapa base OSM~~ (hecho: teselas raster en Filament, `GroundTiles`). Falta: proveedor de producción y un modo sin
   conexión (teselas descargadas por parque).
6. ~~Atracciones y aves de Xcaret~~ (hecho: `AnimatedModels`, CPU, sin `matc`). Falta: **agua animada y vaivén de árboles** con materiales propios de Filament compilados con `matc` (`.filamat` en
   assets, sin comprimir). Requiere render continuo mientras sean visibles: hacerlo opcional y a 30 fps.
7. **LODs**: niveles de detalle para árboles/edificios (por bloque o `KHR_mesh_lod`-like en el exportador) y
   ocultar instancias pequeñas a distancia.
8. **Compresión** KTX2 de texturas (meshopt ya está, ver regla 11) y descarga de parques por demanda.
9. **Caché offline**: parques descargables (no en el APK), versión en `park.json`, almacenamiento en `filesDir`.
10. ~~Idioma inglés~~ (hecho: Ajustes → Idioma). Falta: accesibilidad (TalkBack en marcadores), modo oscuro.
11. Perfilado: Android GPU Inspector / `adb shell dumpsys gfxinfo`; pruebas de UI de gestos y de `MapCamera`.
