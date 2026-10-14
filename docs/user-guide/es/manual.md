# Manual de usuario de Anti-Vocale

Anti-Vocale transcribe mensajes de voz en tu dispositivo Android, completamente sin conexión. El audio nunca sale del teléfono: la transcripción se ejecuta localmente con modelos de IA abiertos, sin cuenta, sin servicios en la nube, sin telemetría.

Manual actualizado para la versión 1.15.

## Contenidos

1. [Primeros pasos](#primeros-pasos)
2. [Elegir un modelo](#elegir-un-modelo)
3. [Transcribir: el flujo diario](#transcribir-el-flujo-diario)
4. [Notas de voz de WhatsApp, automáticamente](#notas-de-voz-de-whatsapp-automáticamente)
5. [Resultados: etiquetas, formatos y la ventana flotante](#resultados-etiquetas-formatos-y-la-ventana-flotante)
6. [Audio largo, cola y reintentos](#audio-largo-cola-y-reintentos)
7. [Modelos de la comunidad e importaciones](#modelos-de-la-comunidad-e-importaciones)
8. [Tasker y automatización](#tasker-y-automatización)
9. [Ajustes por aplicación](#ajustes-por-aplicación)
10. [Privacidad](#privacidad)
11. [Solución de problemas](#solución-de-problemas)
12. [Preguntas frecuentes](#preguntas-frecuentes)

Las secciones 8a, 8b y 8c tratan las funciones de automatización: la vigilancia programada de carpetas, la delegación remota y el proveedor de Open Transcribe.

## Primeros pasos

1. Instala Anti-Vocale desde tu tienda (Google Play o F-Droid) o desde un APK publicado en GitHub.
2. Abre la aplicación una vez. En la pestaña **Modelos** verás los modelos integrados disponibles para descargar.
3. Descarga un modelo. Para la mayoría de las personas, la primera opción recomendada es **Parakeet TDT (stock int8, 640 MB)**: rápido, ligero y cubre 25 lenguas europeas.
4. Para transcribir, comparte un mensaje de voz desde cualquier aplicación de mensajería (WhatsApp, Telegram, Signal y otras) hacia Anti-Vocale. Aparece una notificación mientras procesa y después una segunda notificación con el texto.
5. Toca la notificación del resultado para copiar, compartir o devolver el texto al chat de donde provenía.

No hace falta ninguna configuración adicional. Todo lo que sigue es opcional.

## Elegir un modelo

Los modelos difieren en tamaño, velocidad, cobertura de idiomas y precisión. La pestaña Modelos muestra los datos esenciales en cada tarjeta antes de descargar. Una orientación rápida:

| Modelo | Tamaño | Idiomas | Notas |
|---|---|---|---|
| Parakeet TDT stock int8 | 640 MB | 25 europeas | Rápido y ligero; la recomendación por defecto |
| Parakeet TDT SmoothQuant | 862 MB | 25 europeas | Más preciso, más pesado; necesita más RAM |
| Whisper Turbo | 988 MB | 101 | El mejor equilibrio de la familia Whisper |
| Whisper Medium | 903 MB | 101 | Más lento que Turbo, no es mejor para la mayoría del audio |
| Whisper Small | 358 MB | 101 | El Whisper más ligero; calidad decente |
| Whisper Distil Italian | 938 MB | Solo italiano | La mejor precisión en italiano del conjunto integrado |
| Qwen3-ASR | 938 MB | Multilingüe | Arquitectura alternativa |
| Nemotron streaming | 640 MB | Multilingüe | Muestra el texto mientras hablas (en streaming) |
| GigaAM v3 | 326 MB | Ruso | Especialista en ruso |

Reglas prácticas:
- Si transcribes sobre todo un solo idioma, un modelo especializado (Distil Italian, GigaAM) supera a un generalista del mismo tamaño.
- Si tu teléfono tiene 4 GB de RAM o menos, prefiere modelos de menos de 500 MB.
- Los modelos Gemma (listados aparte en la pestaña Modelos) son modelos de lenguaje más grandes que también pueden transcribir. Son interesantes para experimentar, pero más pesados y lentos que los modelos ASR dedicados.

## Transcribir: el flujo diario

- Comparte un mensaje de voz hacia Anti-Vocale. El procesamiento empieza de inmediato, incluso con la pantalla apagada.
- La notificación del resultado ofrece: **Copiar**, **Compartir** y, cuando la aplicación de origen es compatible, **Enviar a [App]**, que pega el texto directamente en el chat de donde venía el mensaje de voz.
- Con la copia automática activada (Ajustes), el texto ya está en el portapapeles cuando llega la notificación; la notificación lo indica.
- Cada transcripción se guarda en la pestaña **Logs** con el modelo usado, la duración y el tiempo de procesamiento. Mantén pulsada una entrada para reintentar, copiar, borrar o informar de un mal resultado por correo electrónico; **Volver a transcribir** procesa el mismo audio con otro modelo de tu elección.
- Con un modelo **Whisper o SenseVoice**, cada transcripción expandida lleva una etiqueta de idioma: la bandera del idioma que el modelo detectó, con un toque para comprobarla o fijarla cuando la suposición es errónea (los modelos pequeños a veces se equivocan). Otras familias de modelos no informan del idioma detectado, así que la etiqueta no aparece para ellas; el interruptor en Ajustes lo indica y permanece desactivado.
- Con el guardado automático (Ajustes), cada transcripción también se escribe como archivo .txt en una carpeta que elijas.

## Notas de voz de WhatsApp, automáticamente

Anti-Vocale puede transcribir las notas de voz nuevas de WhatsApp en el momento en que llegan, sin tocar nada. El texto aparece en la pestaña Historial y en una notificación de resultado; si Android bloquea un inicio en segundo plano (por ejemplo con la pantalla apagada), una notificación de un toque inicia la transcripción en su lugar.

La configuración toma un minuto, en **Ajustes > Notas de voz**:

1. Activa **Transcripción de notas de voz** y concede el acceso al audio cuando Android lo pida. Si lo rechazaste dos veces, Android ya no muestra el diálogo; la pista roja que aparece en su lugar abre la página del sistema donde todavía se puede conceder el permiso.
2. Elige una vez la carpeta de notas de voz de WhatsApp mediante el selector guiado. Una carpeta equivocada se rechaza en el acto; puedes cambiarla o quitarla cuando quieras desde la misma tarjeta.
3. Opcional: activa también el **reconocimiento del remitente** y concede el acceso a las notificaciones. Así la fila de Logs lleva el nombre del remitente.

Dos reglas honestas rigen las etiquetas. Un nombre aparece solo cuando la propia notificación de la nota lo confirma, es decir, la notificación llegó mientras la función estaba activa y su duración declarada coincide con el audio: los nombres nunca se adivinan. Y las notas que descargas manualmente (con la descarga automática desactivada) quedan sin etiqueta, porque su notificación es demasiado antigua para ser fiable. La lista de espera de notificaciones recientes vive solo en memoria y desaparece cuando la función está desactivada.

La privacidad es el mismo contrato de siempre: con la detección activada, se lee únicamente el listado de la carpeta de notas de voz de WhatsApp; el audio de una nota se lee solo para transcribirlo, completamente en este dispositivo; con la función desactivada no se lee nada.

Una advertencia, dicha con claridad: en teléfonos con gestores de batería agresivos, las notas que llegan mientras el sistema ha suspendido la aplicación pueden perderse hasta que esta despierte. Si dependes del modo automático, excluir Anti-Vocale de la optimización de batería lo hace fiable. Solo WhatsApp por ahora; Telegram está en la hoja de ruta.

## Resultados: etiquetas, formatos y la ventana flotante

- **Etiquetas de hablante**: actívalas desde Ajustes y las transcripciones de conversaciones reciben atribución de hablante por frase, mostrada como turnos de SPEAKER en el resultado y en todas las exportaciones.
- **Compartir como más que texto**: desde una fila de resultado, comparte como texto plano, texto con marcas de tiempo, SRT o VTT. Los formatos con marcas de tiempo necesitan una transcripción que lleve señales. "Compartir como…" se activa desde Ajustes.
- **Picture-in-Picture**: mientras se ejecuta una transcripción, una ventana flotante puede mostrar el texto apareciendo en vivo, de modo que puedes seguir usando otras aplicaciones.
- **Volver a transcribir**: reintenta cualquier entrada con otro modelo directamente desde su fila y compara.

## Audio largo, cola y reintentos

- Cualquier duración de audio funciona con cualquier modelo: las grabaciones largas se dividen y se vuelven a unir automáticamente. (Las versiones antiguas tenían un límite de 6:40 con Parakeet; ya no existe.)
- Comparte varios mensajes seguidos: se ponen en cola. Cada elemento en cola se puede cancelar individualmente desde su notificación mientras otra transcripción se ejecuta.
- Una transcripción fallida se puede reintentar con un toque desde la pestaña Historial.

## Modelos de la comunidad e importaciones

El catálogo integrado no cubre todos los idiomas. Anti-Vocale incluye un catálogo comunitario de modelos adicionales que importas con dos toques: pestaña Modelos, Avanzado, ONNX Sherpa, Importar del catálogo, filtra por tu idioma, toca el modelo, confirma. El catálogo crece sin parar y hoy contiene más de 30 entradas validadas: árabe (dialectal, completo y ligero), alemán (en streaming y un ajuste fino de Whisper), español (en streaming y un Moonshine ligero), suizo alemán, hebreo, persa, ruso, vietnamita, coreano, ucraniano, seis lenguas índicas (bengalí, gujarati, hindi, marathi, tamil, télugu), Canary Flash para inglés y francés, SenseVoice, Dolphin, Orukeet (un ajuste fino de Parakeet que cubre 24 idiomas, incluido el italiano) y un Whisper Tiny multilingüe de poca RAM. Si falta tu idioma, abre un issue: así se añaden las entradas.

Los usuarios avanzados también pueden:
- importar un modelo desde la URL de un repositorio de Hugging Face o desde un enlace de entrada del catálogo (la rama avanzada del mismo diálogo);
- importar un conjunto de archivos de modelo desde una carpeta del teléfono;
- exportar un modelo descargado como **paquete sin conexión** e importarlo en otro dispositivo, de modo que un modelo descargado una vez se traslade sin volver a descargarlo;
- apuntar la aplicación a otro índice de catálogo (la acción "cambiar" junto a la fuente del catálogo) mantenido por cualquiera, por ejemplo tu comunidad.

El formato de importación y los requisitos de los archivos están documentados en [modelos externos](../../external-models.md).

## Tasker y automatización

Anti-Vocale acepta un broadcast que Tasker (o cualquier aplicación de automatización) puede enviar para transcribir un archivo sin tocar la interfaz:

```
Action: com.antivocale.app.PROCESS_REQUEST
Extras: request_type=audio, file_path=/path/to/audio, task_id=your-id
Optional: backend_id=<model id> to pick the model for that request
```

El resultado vuelve como broadcast de respuesta. La guía completa con ejemplos está en la [guía de Tasker](../../TASKER_GUIDE.md).

## Vigilancia programada de carpetas

Anti-Vocale puede vigilar las carpetas que elijas y transcribir automáticamente los archivos de audio nuevos: Ajustes, Vigilancia programada de carpetas, Vigilar una carpeta. Los análisis se ejecutan en una programación que el sistema permite; los ahorradores de batería agresivos pueden retrasarlos, y el botón Escanear ahora siempre funciona. Cada archivo de audio nuevo se transcribe con el modelo seleccionado en ese momento y aparece en la pestaña Historial como cualquier otra ejecución. En Android 11 o posterior, el selector de carpetas puede abrir ubicaciones del almacenamiento compartido, como la carpeta de medios de una aplicación de mensajería, lo que hace posible la transcripción automática de las notas de voz de un chat sin compartirlas una por una.

## Delegación remota (experimental)

Si gestionas tu propio servidor de transcripción, Anti-Vocale puede delegarle las transcripciones largas o pesadas: Ajustes, Delegación remota (experimental). Cualquier servidor compatible con OpenAI que hable la API /v1/audio/transcriptions sirve (OmniVoice, el servidor de whisper.cpp y otros). El aviso dentro de la aplicación lo dice con claridad: cuando la función está activada, el audio de las transcripciones que delegas se envía solo a la dirección del servidor que introduzcas, esté ese servidor donde esté (tu red doméstica o cualquier punto de internet); todo lo demás se transcribe en el dispositivo, y la función viene desactivada por defecto.

## Open Transcribe (experimental)

Las aplicaciones que hablan el contrato abierto Open Transcribe, como Forkgram (un cliente de Telegram), pueden usar Anti-Vocale como su transcriptor de mensajes de voz integrado: activa **Ajustes > Automatización > Open Transcribe API** (desactivada por defecto) y luego elige Anti-Vocale en los ajustes de transcripción sin conexión del cliente. Los mensajes de voz se transcriben con los modelos propios de la aplicación y el audio nunca sale del dispositivo. Al desactivar el interruptor, la aplicación desaparece de nuevo de los selectores de los clientes.

## Ajustes por aplicación

Para cada aplicación desde la que compartas (WhatsApp, Telegram, ...) puedes configurar por separado: si se muestra la acción de devolución, si se copia automáticamente y el sonido de notificación. Pestaña Ajustes, Ajustes por aplicación.

## Privacidad

- La transcripción es 100% en el dispositivo. Ningún audio, ningún texto, ningún metadato sale jamás de tu teléfono.
- La aplicación no tiene permiso de internet para transcribir; la red se usa solo cuando descargas un modelo de forma explícita.
- Los registros se quedan en tu dispositivo y son tuyos: bórralos cuando quieras desde la pestaña Historial.
- La compilación de Play incluye el informe de fallos de Crashlytics (puedes verlo y desactivarlo en los ajustes de Android); la compilación de F-Droid no lo tiene.

## Solución de problemas

**La transcripción nunca termina / la notificación desaparece.**
Algunas marcas de teléfonos (Vivo, OPPO, algunos Xiaomi y Samsung) suspenden de forma agresiva las aplicaciones en segundo plano. Abre Anti-Vocale una vez y, si lo ofrece, concede la exención de batería; o busca la aplicación en los ajustes de batería y ponla en Sin restricciones. La aplicación detecta esta situación y lo explica en una notificación cuando ocurre.

**"Memoria insuficiente" o fallos con modelos grandes.**
Los modelos indican su tamaño en la tarjeta. En teléfonos con 4 GB de RAM o menos, usa modelos de menos de 500 MB. Si una transcripción falla con un mensaje de memoria agotada, prueba con un archivo más corto, un modelo más pequeño o cierra otras aplicaciones.

**La calidad de la transcripción es pobre.**
Prueba un modelo especializado para tu idioma (ver la tabla de arriba). Mantén pulsada la entrada defectuosa en Logs y usa Informar para enviarnos los detalles (modelo, duración, tiempos; el extracto de la transcripción solo si eliges incluirlo).

**NNAPI hace que falle.**
Si activaste el proveedor NNAPI en Ajustes y ahora la aplicación falla, vuelve a CPU automáticamente en el siguiente arranque. NNAPI depende en gran medida del chipset del teléfono; si falla repetidamente, déjalo en CPU.

**Las notas de voz automáticas dejaron de llegar.**
Dos causas habituales. Si la carpeta de WhatsApp se borró y se volvió a crear (una reinstalación, una herramienta de limpieza), el permiso de carpeta guardado puede morir: abre Ajustes > Notas de voz y vuelve a elegir la carpeta desde la tarjeta. Si tu teléfono suspende de forma agresiva las aplicaciones en segundo plano, las notas que llegan mientras la aplicación está suspendida se pierden hasta que despierte: la exención de batería de arriba es la solución, y importa más para esta función que para el uso manual.

## Preguntas frecuentes

**¿Funciona sin internet?**
Sí. Después de descargar un modelo, la transcripción funciona completamente sin conexión.

**¿Qué aplicaciones de mensajería son compatibles?**
Cualquier aplicación que pueda compartir un archivo de audio. La acción de devolución apunta actualmente a un subconjunto de aplicaciones (WhatsApp, Telegram y otras detectadas automáticamente).

**¿Dónde están mis transcripciones?**
En la pestaña Historial y, opcionalmente, como archivos .txt en una carpeta que elijas. No se guarda nada en ningún otro sitio.

**¿Puede transcribir notas de voz automáticamente al llegar?**
Sí, para WhatsApp: activa Ajustes > Notas de voz, concede el acceso al audio y elige una vez la carpeta de notas de voz de WhatsApp. Las notas nuevas se transcriben al llegar y la fila puede llevar el nombre del remitente. La vigilancia programada de carpetas sigue siendo la vía genérica para la carpeta de medios de cualquier aplicación (Android 11 o posterior), y Telegram está en la hoja de ruta. En teléfonos con gestores de batería agresivos, las notas que llegan con la aplicación suspendida pueden perderse: la exención de batería hace fiable el modo automático.

**¿Por qué una nota de voz no tiene nombre de remitente?**
El nombre aparece solo cuando la notificación de la nota llegó mientras el reconocimiento del remitente estaba activo y su duración declarada coincide con el audio. Las notas descargadas manualmente (con la descarga automática desactivada) y las notas cuya notificación nunca llegó no reciben nombre, a propósito: un nombre equivocado es peor que ningún nombre.

**¿Por qué hay dos tiendas (Play y F-Droid)?**
La misma aplicación, las mismas funciones. F-Droid la compila desde el código fuente sin componentes propietarios; Play añade el informe de fallos automático.

**¿De verdad es privada?**
Sí. El código fuente está abierto; puedes verificar que ningún dato sale del dispositivo. Consulta la política de privacidad del repositorio.

---

¿Encontraste un error o falta algo? Abre un issue en [GitHub](https://github.com/RisorseArtificiali/anti-vocale/issues).
