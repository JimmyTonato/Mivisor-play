# MiVisor Play

Un solo reproductor para **emisiones de Ace Stream** y para **torrents y magnet**, en tele (con mando), tablet y móvil.
Se instala junto a MiVisor TV sin reemplazarla (paquete `com.mivisor.play`).

## Qué abre

| Qué | Cómo | Motor |
|---|---|---|
| Ace Stream | ID de 40 caracteres, `acestream://…` o un texto con el ID dentro | **Outpace** (el de MiVisor TV, con sus parches) → HLS → Media3 |
| Magnet | `magnet:?xt=…` | **TorrServer** (proceso aparte) → **VLC** dentro de la app |
| Archivo .torrent | Botón «Archivo .torrent» o «Abrir con… MiVisor Play» desde otra app | **TorrServer** (proceso aparte) → **VLC** dentro de la app |

También aparece en «Abrir con» y en «Compartir» de otras apps (navegador, Telegram, gestor de archivos).

## Torrents y magnet: montado como Ace Stream

Ace Stream (3.2) lleva TorrServer para los torrents, en su propio proceso, y VLC para reproducir. MiVisor Play hace lo
mismo:

- **TorrServer** (GPL-3.0, github.com/YouROK/TorrServer) es un ejecutable aparte, igual que Outpace: si se cae, la app
  sigue abierta, lo explica («El motor de torrents se cerró») y lo vuelve a arrancar con Reintentar.
- Su caché solo guarda la ventana por delante de lo que se ve y lo último visto; el resto lo suelta. Por defecto 128 MB
  **en memoria**: no ocupa disco. En Ajustes se puede subir hasta 2 GB y guardarla en el disco de la app. Se borra sola.
- **VLC** reproduce dentro de la app: AC3, DTS, MKV, AVI… Si con la aceleración por hardware no sale la imagen, se
  reintenta solo sin ella en el mismo punto.
- Ajustes: tamaño de la caché, memoria o disco, precarga, límite de subida y aceleración por hardware.

**Compilación de TorrServer:** la hace Gradle solo (tarea `buildTorrServer`) en GitHub Actions cuando falta el binario:
`tools/build-torrserver.sh` descarga el Go que pide TorrServer, lo compila con el NDK ya instalado (el de Outpace) y lo
deja en `jniLibs/<abi>/libtorrserver.so`. No hay que tocar el workflow.

## Reproductor

- Mando: OK o abajo muestran los controles; izquierda/derecha avanzan o retroceden 10 s (en torrents);
  play/pausa del mando; atrás cierra paneles y sale.
- Táctil: un toque muestra los controles; doble toque a un lado, ±10 s (en torrents).
- Audio, subtítulos, formato de imagen, información y, en Ace Stream, «Ir al directo».
- Pantalla de carga con los pasos (buscar la emisión, encontrar personas, preparar imagen) y el motivo si falla.

## Registro de errores

Ajustes › **Ver el registro** muestra lo que ha pasado en la app, en Outpace y en el motor de torrents.
**Copiar todo** lo deja en el portapapeles, listo para pegarlo y averiguar un fallo.
El registro se guarda en un archivo al momento: si la app se cerró sola, al abrirla otra vez aparece lo que pasó justo antes y lo que dijo Android («Cierre anterior»). Lo que cuenta TorrServer sale con la etiqueta [TorrServer].

## Cómo se genera la APK

Igual que MiVisor TV, con GitHub Actions:

1. Sube el contenido del ZIP a la rama `main` de un repositorio (mejor privado).
2. El workflow **MiVisor Play (APK)** arranca solo. También se puede lanzar en *Actions › Run workflow*.
3. Pasos: compila Outpace desde el commit fijado y le aplica los parches de `tools/outpace-patches`, pasa las pruebas
   y compila la release con R8.
4. Si sale bien, se publica una **pre-release** con `MiVisor-Play-1.0.<n>.apk` adjunta. Las de otras ramas quedan
   como *artifacts*.

Firma: con los secretos `SIGNING_*` si están; si no, con `app/debug.keystore`, como MiVisor TV.

## Estructura

| Módulo | Qué hace |
|---|---|
| `core:*` | Módulos de MiVisor TV sin cambios: motor (supervisor de Outpace, retraso con el directo), catálogo (`DirectStream`), fiabilidad, diagnóstico |
| `engine:outpace` | Outpace en Android (sin cambios) |
| `feature:playback` | Reproducción de Ace Stream con Media3 (sin cambios) |
| `engine:torrserver` | **Nuevo:** arranca y vigila TorrServer como proceso aparte (como Outpace) y habla con su API |
| `ui:design` | Base de foco para mando y táctil (`TvClickable`) y tipografía Inter |
| `app` | **Nueva:** Inicio, reproductores, Ajustes, Registro y Licencias, con su propio diseño |

Quitado respecto a MiVisor TV: canales, agenda, proveedores, MiVisor Gold, WARP, móvil remoto y copias cifradas.

## Licencias

Outpace es AGPL-3.0-or-later: su licencia, su aviso y la lista de cambios van dentro de la APK (Ajustes › Licencias) y
los parches están en `tools/outpace-patches`. TorrServer es GPL-3.0 (se compila sin cambios y su licencia va dentro de la APK); VLC, LGPL-2.1; Inter, OFL.
