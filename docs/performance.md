# Medición reproducible

El benchmark inicia una JVM propia en un puerto local libre, espera readiness,
calienta cada escenario y termina únicamente el proceso que inició. Requiere
Python 3.10+, Java 27 y el JAR compilado. No necesita un servicio desplegado.

```bash
mvn verify
python scripts/benchmark.py --jar target/CodigoPostalApi-3.0.0-SNAPSHOT.jar --requests 1000 --concurrency 8 --warmup 300 --cold-requests 50 --output target/benchmark.json
```

Si Java no está en PATH, añade `--java /ruta/al/jdk/bin/java` (en Windows,
`--java C:\ruta\al\jdk\bin\java.exe`). El informe JSON y el log de la JVM quedan
en la ruta indicada. Se fija el catálogo incluido en el JAR y las opciones locales
por línea de comandos para evitar heredar una configuración de despliegue.
No se vacían las cachés de una instancia existente.

## Escenarios y métricas

| Escenario | Consulta | Caché |
|---|---|---|
| `direct_warm` | CP `01000` | Mapa directo; no tiene caché de resultados |
| `municipality_warm` | Guadalajara, 20 resultados | Caliente |
| `municipality_cold_cache` | La misma consulta | Todas las cachés vaciadas antes de cada petición |
| `advanced_warm` | Asentamiento `san`, zona Urbana, 20 resultados | Caliente |
| `advanced_cold_cache` | La misma búsqueda avanzada | Todas las cachés vaciadas antes de cada petición |

Cada escenario registra p50, **p95**, p99, media, peticiones por segundo, duración,
concurrencia, heap usado/comprometido al terminar y máximo RSS muestreado cada 100 ms.
RSS se obtiene en Windows y Linux; otros sistemas devuelven `null`. Las mediciones
de heap no fuerzan una recolección: no representan exclusivamente memoria viva.

La latencia incluye el viaje HTTP local, lectura del cuerpo y trabajo del servidor.
Los clientes usan HTTP/1.1 persistente, sin TLS, proxy ni compresión solicitada.
El percentil usa el rango superior más cercano. Cualquier respuesta distinta de
200 aborta la ejecución para evitar resultados que oculten errores.

En los escenarios fríos, la concurrencia es 1 para evitar que las peticiones se
calienten entre sí. El vaciado no entra en la latencia, pero **sí entra en el tiempo
total usado para calcular RPS** (`cache_reset_control_in_throughput=true`). Por ello,
compara su p95 entre versiones y no interpretes esos RPS como capacidad máxima ni
los compares directamente con los RPS de concurrencia 8. “Frío” se refiere a las
cachés de consultas: la JVM, los índices y el código ya están inicializados.

El informe incluye comando/flags de JVM, versión Java, plataforma, CPU lógicas,
checksum del JAR, estadísticas y checksum del catálogo, tiempo hasta readiness
y número de peticiones de calentamiento. Los flags fijan heap de 128–384 MiB y ZGC.

## Comparar cambios

1. Usa la misma máquina, Java, catálogo, flags, carga y concurrencia para ambas versiones.
2. Ejecuta al menos tres repeticiones por versión, sin builds u otras cargas en paralelo.
3. Conserva todos los JSON; compara la mediana del p95 y RPS de cada escenario, y RSS.
4. Incrementa duración y calentamiento si los resultados varían mucho. Repite en el
   entorno de despliegue para evaluar red, límites de CPU y memoria reales.

El generador Python comparte máquina con la JVM y puede limitar la carga. Estos
resultados sirven para comparar cambios locales; no son una prueba de saturación
ni un compromiso de latencia en producción. No hay una medición previa equivalente
a estas mejoras, así que una primera ejecución establece una referencia.

## Perfil aislado

`application-benchmark.yml` desactiva rate limiting y calentamiento automático,
escucha en `127.0.0.1` y habilita `/internal/benchmark/reset` y `/memory` solo con
el perfil `benchmark`. Ambos controles rechazan direcciones remotas. No combines
este perfil con perfiles de despliegue ni lo publiques detrás de un proxy.
Las pruebas verifican que esos controles no existan en el perfil normal.

## Referencia local del 3 de noviembre de 2026

Tres ejecuciones, Windows 11, 12 CPU lógicas, Temurin 27+35, 1.000 consultas
por escenario caliente, 300 de calentamiento y 50 con caché vacía. Catálogo:
31.918 CP y 157.424 asentamientos. Mediana entre ejecuciones:

| Escenario | p95 (ms) | RPS | RSS máximo (MiB) |
|---|---:|---:|---:|
| `direct_warm` | 3.17 | 3496.1 | 585.3 |
| `municipality_cold_cache` | 2.32 | 329.0 | 597.9 |
| `municipality_warm` | 3.14 | 4102.3 | 599.2 |
| `advanced_cold_cache` | 14.11 | 77.3 | 602.2 |
| `advanced_warm` | 3.31 | 3978.6 | 597.9 |

[Resultados completos y configuración](benchmarks/2026-11-03-local.json).
Son ejecuciones cortas para establecer una referencia inicial; los RPS fríos
incluyen el control de vaciado y usan concurrencia 1. Para decisiones de capacidad
aumenta la duración y usa un generador de carga en otra máquina.
