# Parquex

Mapa 3D nativo para Android de los parques de Grupo Xcaret (Xcaret, Xel-Há, Xplor, Xenses y Xoximilco),
con estilo low-poly y pensado para ir rápido en teléfonos modestos.

> Proyecto personal sin relación con Grupo Xcaret. Los nombres y marcas de los parques pertenecen a sus dueños.

## Qué hace

- Dibuja cada parque en 3D con [Filament](https://github.com/google/filament): modelos, árboles, agua y suelo
  ilustrado. Alrededor del parque se ve el mapa de OpenStreetMap.
- Muestra actividades numeradas, rutas oficiales y andadores, con búsqueda y filtros por ruta o categoría.
- Navegación a pie dentro del parque, con GPS o simulada.
- Modo noche con faroles, piezas animadas y parques vecinos.
- Interfaz en español e inglés.

## Compilar

Requiere Android Studio (Kotlin 2.4, AGP 9) y un dispositivo o emulador con Android 7.0 (API 24) o superior.

```
./gradlew assembleDebug
./gradlew installDebug
```

Si Gradle no encuentra el JDK que pide, usa el que trae Android Studio:

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug -Porg.gradle.java.installations.paths="$JAVA_HOME"
```

## Modelos y datos

`app/src/main/assets/parks/<parque>/` trae lo que la app carga: `park-models.glb`, `park.json` y archivos auxiliares.
Se generan aparte, en el proyecto web del mapa (three.js), con `npm run export:mobile`. Aquí no se editan a mano.
`CLAUDE.md` explica los formatos y la arquitectura de la app.

## Mapa base

Las teselas salen de `tile.openstreetmap.org`, que solo sirve para desarrollo y tráfico moderado. Para publicar la
app hay que cambiar `GroundTiles.URL_TEMPLATE` a otro proveedor o a un servidor propio.

## Licencia

El código está bajo la licencia [MIT](LICENSE).

- Los andadores y el mapa base salen de OpenStreetMap: © colaboradores de OpenStreetMap,
  [ODbL](https://www.openstreetmap.org/copyright).
- Filament se distribuye bajo la licencia Apache 2.0.
