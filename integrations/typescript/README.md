# Cliente TypeScript

Cliente ESM para Node.js 20+ y navegadores con Fetch. `src/schema.d.ts` se genera
desde [OpenAPI](../openapi.json) con `openapi-typescript`; `openapi-fetch` construye
las peticiones tipadas. No edites el archivo de tipos generado.

Desde este directorio:

```bash
npm ci --ignore-scripts --no-audit --no-fund
npm run generate
npm test
npm pack
```

Instala el `.tgz` resultante en tu proyecto con `npm install /ruta/al/archivo.tgz`.
El paquete aún no está publicado en un registro. Para probarlo aquí, inicia la API y
ejecuta `node examples/address.mjs`; puedes cambiar el servidor con la variable `BASE_URL`.

```typescript
import { createPostalClient, PostalApiError } from '@codigopostal/client';

const api = createPostalClient({ baseUrl: 'http://localhost:8080' });
try {
  const { data } = await api.GET('/zip-codes/{zipcode}/address', {
    params: { path: { zipcode: '01000' } },
    signal: AbortSignal.timeout(5000),
  });
  console.log(data?.settlements);

  const result = await api.POST('/zip-codes/validate-address', {
    body: { zip_code: '01000', federal_entity: 'Ciudad de México', municipality: 'Álvaro Obregón' },
    signal: AbortSignal.timeout(5000),
  });
  console.log(result.data?.valid);
} catch (error) {
  if (error instanceof PostalApiError) {
    console.error(error.status, error.message, error.body, error.retryAfterMs);
  } else {
    throw error; // red, timeout o cancelación
  }
}
```

`PostalApiError` conserva el código HTTP, cuerpo y cabeceras. Las inconsistencias
de domicilio son resultados 200 con `valid=false`, no excepciones. Los fallos HTTP
sí lanzan una excepción; no dependas del campo `error` de `openapi-fetch` para ellos.

Ante un 429 se permite por defecto **un reintento de GET/HEAD**. Se respeta
`Retry-After` en segundos o fecha HTTP, con fallback al encabezado anterior
`X-RateLimit-Retry-After-Seconds` y espera de 1 segundo si ambos faltan.
`maxRetries` acepta 0–5; `maxRetryDelayMs` limita la espera (30 segundos por defecto).
Si el servidor pide más tiempo, se lanza el error sin reintentar antes de tiempo.
POST no se reintenta. `AbortSignal` también cancela la espera entre intentos.

El cliente no gestiona almacenamiento ETag: no envíes `If-None-Match` sin implementar
tu propia conservación del cuerpo y tratamiento de 304. Puedes usar Fetch directamente
para ese flujo. Usa CP como cadenas para conservar los ceros iniciales.

En navegadores sirve la API bajo el mismo origen mediante proxy o configura CORS
en tu despliegue; también deberás exponer `Retry-After` si usas orígenes distintos.
El ejemplo incluido en el servidor no necesita CORS.
