# MiVisor Play

Un solo reproductor para **emisiones de Ace Stream** y para **torrents y magnet**, en tele (con mando), tablet y móvil.
Se instala junto a MiVisor TV sin reemplazarla (paquete `com.mivisor.play`).

## Qué abre

| Qué | Cómo | Motor |
|---|---|---|
| Ace Stream | ID de 40 caracteres, `acestream://…` o un texto con el ID dentro | **Outpace** (el de MiVisor TV, con sus parches) → HLS → Media3 |
| Magnet | `magnet:?xt=…` | **libtorrent** → servidor local → Media3 |
| Archivo .torrent | Botón «Archivo .torrent» o «Abrir con… MiVisor Play» desde otra app | **libtorrent** → servidor local → Media3 |

También aparece en «Abrir con» y en «Compartir» de otras apps (navegador, Telegram, gestor de archivos).

## Torrents: se ven mientras se descargan, y lo visto se borra

- Se descarga en orden, empezando por lo que vas a ver. Si saltas a otro punto, se prioriza ese punto.
- **Borrar lo ya visto** (activado por defecto): solo se guarda una caché alrededor de lo que estás viendo, 500 MB por
  defecto (75 % por delante y 25 % por detrás). Lo que queda fuera se libera del disco al momento: un vídeo de 7 GB no
  necesita 7 GB libres.
- Si vuelves muy atrás, a una parte ya borrada, se descarga otra vez solo ese trozo.
- Al salir del reproductor se borra todo lo descargado.
- Ajustes: tamaño de la caché (300 MB – 2 GB), espera antes de empezar (5 – 40 MB) y límite de subida.

**Audio AC3/DTS:** Media3 usa los decodificadores del aparato. Casi todas las teles Android los traen, pero muchos
móviles no. Si el audio de un vídeo no se puede reproducir, la app lo avisa y ofrece **«Otro reproductor»** (VLC, MX
Player…): el vídeo se sigue descargando mientras lo ves en esa app.

## Reproductor

- Mando: OK o abajo muestran los controles; izquierda/derecha avanzan o retroceden 10 s (en torrents);
  play/pausa del mando; atrás cierra paneles y sale.
- Táctil: un toque muestra los controles; doble toque a un lado, ±10 s (en torrents).
- Audio, subtítulos, formato de imagen, información y, en Ace Stream, «Ir al directo».
- Pantalla de carga con los pasos (buscar la emisión, encontrar personas, preparar imagen) y el motivo si falla.

## Registro de errores

Ajustes › **Ver el registro** muestra lo que ha pasado en la app, en Outpace y en el motor de torrents.
**Copiar todo** lo deja en el portapapeles, listo para pegarlo y averiguar un fallo.
Si la app se cerró sola, el motivo aparece al principio del registro la siguiente vez.

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
| `engine:torrent` | **Nuevo:** libtorrent, servidor HTTP local con Range y caché que libera lo ya visto (`fallocate` PUNCH_HOLE, en C) |
| `ui:design` | Base de foco para mando y táctil (`TvClickable`) y tipografía Inter |
| `app` | **Nueva:** Inicio, reproductores, Ajustes, Registro y Licencias, con su propio diseño |

Quitado respecto a MiVisor TV: canales, agenda, proveedores, MiVisor Gold, WARP, móvil remoto y copias cifradas.

## Licencias

Outpace es AGPL-3.0-or-later: su licencia, su aviso y la lista de cambios van dentro de la APK (Ajustes › Licencias) y
los parches están en `tools/outpace-patches`. libtorrent4j es MIT; Inter, OFL.
