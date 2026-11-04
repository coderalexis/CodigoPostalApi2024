# Integrar domicilios

Ejecuta la API y abre [el formulario de ejemplo](http://localhost:8080/examples/address.html).
El ejemplo no necesita compilación: sus archivos están en
`src/main/resources/static/examples/`. Usa el mismo origen que la API, conserva
ceros iniciales, espera 250 ms entre consultas y cancela solicitudes obsoletas.
Puedes escribir `01000`, editar estado/municipio y comprobar las alternativas.

## Obtener opciones por CP

```bash
curl -i http://localhost:8080/zip-codes/01000/address
```

```json
{
  "zip_code": "01000",
  "federal_entity": "Ciudad de México",
  "federal_entity_code": "09",
  "municipality": "Álvaro Obregón",
  "municipality_code": "010",
  "settlements": ["San Ángel"]
}
```

Las colonias se devuelven sin duplicados y ordenadas. El CP y las claves oficiales
son cadenas. Para identificar un municipio usa **estado + clave de municipio**:
los nombres y las claves municipales aisladas pueden repetirse en otros estados.

## Validar la relación CP/estado/municipio

```bash
curl -X POST http://localhost:8080/zip-codes/validate-address -H "Content-Type: application/json" -d '{"zip_code":"01000","federal_entity":"Jalisco","municipality":"Guadalajara"}'
```

En PowerShell usa `Invoke-RestMethod` para evitar diferencias de escape del shell:

```powershell
$body = @{ zip_code='01000'; federal_entity='Jalisco'; municipality='Guadalajara' } | ConvertTo-Json
Invoke-RestMethod http://localhost:8080/zip-codes/validate-address -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
```

Una inconsistencia devuelve HTTP 200 con `valid: false`. `issues` contiene
`zip_code_not_found`, `federal_entity_mismatch` y/o `municipality_mismatch`.
`canonical`, cuando existe el CP, describe su ubicación oficial. `suggestions`
incluye hasta cinco alternativas: primero el CP proporcionado, después CP de la
coincidencia exacta estado/municipio solicitada, ordenados por código.
`total_suggestions` y `suggestions_truncated` permiten reconocer una lista recortada.
Sin coincidencias, las sugerencias están vacías; no se inventan ubicaciones.

La comparación ignora acentos, mayúsculas y espacios exteriores. No interpreta
abreviaturas como “CDMX”, no hace coincidencia aproximada y no comprueba calles,
números exteriores, entregabilidad ni existencia física de una dirección.

| Resultado | Tratamiento |
|---|---|
| 200 y `valid=true` | Coinciden CP, estado y municipio |
| 200 y `valid=false` | Muestra las discrepancias y permite elegir una alternativa |
| 400 | Corrige JSON, campos requeridos o formato de cinco dígitos |
| 404 en consultas GET | CP o búsqueda sin resultados |
| 429 | Espera el tiempo indicado por `Retry-After` |
| 5xx o fallo de red | Informa el fallo; evita reintentos sin límite |

La validación POST usa `Cache-Control: no-store`. Los GET exitosos llevan ETag
específico de URL, parámetros, catálogo, build e instancia. Si envías `If-None-Match`,
guarda también el cuerpo anterior para poder resolver un 304. Los errores 400/404
se evalúan antes de las condiciones de caché y nunca se convierten en 304.

## TypeScript y Postman

- [Cliente TypeScript](../integrations/typescript/README.md): tipos generados desde
  OpenAPI, errores tipados, cancelación y reintentos acotados para GET/HEAD con 429.
- [Colección Postman](../integrations/postman/CodigoPostalApi.postman_collection.json):
  consultas, validación, errores y revalidación con ETag. Importa el archivo y cambia
  `baseUrl`; el valor inicial es `http://localhost:8080`. Usa Collection Runner en el
  orden incluido para la comprobación ETag.
- [Contrato OpenAPI](../integrations/openapi.json): exportado por las pruebas desde
  `/v3/api-docs`; Swagger sigue disponible en `/swagger-ui.html` con el perfil dev.
- [Ejemplo ejecutable de cliente](../integrations/typescript/examples/address.mjs):
  consulta y valida contra una API local en ejecución.

Para un frontend en otro dominio, configura un proxy de tu aplicación hacia esta
API o una política CORS explícita en tu despliegue. El proyecto no habilita CORS
globalmente; el formulario incluido usa el mismo origen.

## Mantener sincronizado el contrato

```bash
mvn verify
python scripts/sync_openapi.py
cd integrations/typescript
npm ci --ignore-scripts --no-audit --no-fund
npm run generate
npm test
```

Incluye `integrations/openapi.json`, `src/schema.d.ts` y `package-lock.json` en el
control de versiones. CI vuelve a exportar y generar, falla si hay diferencias y
produce un artefacto descargable con contrato, colección y cliente compilado.
El paquete queda preparado para distribución; no se publica automáticamente en npm.
