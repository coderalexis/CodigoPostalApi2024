# Actualización del catálogo

Requiere Python 3.10+ y un archivo de texto SEPOMEX con su línea informativa,
encabezado oficial y 15 columnas separadas por `|`. Obtén el archivo desde
[Correos de México](https://www.correosdemexico.gob.mx/SSLServicios/ConsultaCP/CodigoPostal_Exportar.aspx).
Los comandos se ejecutan desde la raíz del repositorio. El proceso no descarga ni
reemplaza automáticamente el catálogo incluido en el código fuente.

## Revisar antes de activar

```bash
python scripts/catalog.py validate nuevo.txt
python scripts/catalog.py compare src/main/resources/CPdescarga.txt nuevo.txt --report target/catalog-diff.json
```

Se comprueban tamaño máximo de 100 MiB, encabezado, columnas, campos obligatorios,
formato de CP y claves oficiales, rango de estado, zona, asentamientos duplicados
y ubicaciones contradictorias para el mismo CP. Un error rechaza el archivo completo.
La detección de UTF-8/ISO-8859-1 examina todo el archivo; puede fijarse con
`--encoding utf-8` o `--encoding iso-8859-1` en `validate` e `import`.

El reporte conserva las altas y bajas de CP y las claves de asentamientos agregados,
eliminados o modificados. La identidad del asentamiento es
`CP:estado:municipio:id_asenta_cpcons`; un cambio en cualquier columna cuenta como
modificación. También incluye totales, codificación y SHA-256 de ambos archivos.
Estos controles comprueban consistencia, no certifican la exactitud geográfica.

## Importar y activar

Inicializa el almacén con el catálogo actual para que exista un punto de retorno:

```bash
python scripts/catalog.py import src/main/resources/CPdescarga.txt --store data/catalog
python scripts/catalog.py import nuevo.txt --store data/catalog
```

Una actualización con más del 5% de altas, bajas y modificaciones de asentamientos
respecto al total anterior se rechaza. Revisa el reporte de `compare`; si el cambio
es intencional, repite con `--allow-large-change`. El umbral se puede ajustar con
`--max-change-ratio 0.02`. La primera importación no tiene una referencia para este control.

El almacén queda organizado así:

```text
data/catalog/
  active.json                 # checksum current, previous y fecha de activación
  versions/<sha256>.txt        # archivos inmutables, preservados para rollback
  reports/<sha256>.json        # diferencias al importar esa versión
```

Los archivos se escriben mediante reemplazo atómico. `active.json` se actualiza al
final; un fallo anterior no cambia la versión activa. Reimportar la misma versión
es idempotente y conserva el punto de retorno. `.import.lock` impide dos escritores
simultáneos. Si un proceso termina abruptamente y deja el bloqueo, comprueba que
no haya una importación en ejecución antes de retirar ese archivo.

Configura la aplicación y **reiníciala** para cargar el catálogo seleccionado:

```bash
java -jar target/CodigoPostalApi-3.0.0-SNAPSHOT.jar --zipcode.catalog.directory=data/catalog
```

También acepta `ZIPCODE_CATALOG_DIRECTORY` (ruta absoluta recomendada en despliegues).
Esta opción tiene prioridad sobre `ZIPCODE_FILE_PATH`. En Docker monta el almacén
como volumen de solo lectura y usa su ruta dentro del contenedor. Conserva el
almacén en almacenamiento persistente y respáldalo fuera de la instancia.

El servidor verifica el checksum y aplica las mismas validaciones estructurales.
No inicia con un catálogo parcial, corrupto o con una referencia de versión inválida.
Comprueba `/actuator/health/readiness` y que `catalogChecksum` en `/zip-codes/stats`
coincida con `current` en `active.json` antes de dirigir tráfico a la nueva instancia.
No hay recarga en caliente: una instancia ya iniciada conserva sus datos hasta reiniciarse.

## Volver al catálogo anterior

```bash
python scripts/catalog.py rollback --store data/catalog
```

El rollback verifica ambos archivos e intercambia `current` y `previous` de forma
atómica. Reinicia las instancias y verifica nuevamente readiness y checksum.
Las versiones históricas permanecen disponibles; la herramienta no las elimina.

## Pruebas

```bash
python -m unittest discover -s scripts/tests -v
```

Cubren codificaciones, duplicados, datos contradictorios, importación idempotente,
diff, rollback, bloqueo de escrituras y detección de archivos modificados.
