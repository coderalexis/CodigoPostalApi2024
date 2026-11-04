import assert from 'node:assert/strict';
import { createPostalClient, PostalApiError } from '../dist/index.js';

const api = createPostalClient({ baseUrl: process.env.BASE_URL ?? 'http://localhost:8080' });
const { data: options } = await api.GET('/zip-codes/{zipcode}/address', {
  params: { path: { zipcode: '01000' } }, signal: AbortSignal.timeout(5000),
});
assert.equal(options.zip_code, '01000');
console.log('Opciones:', options);

const { data: validation } = await api.POST('/zip-codes/validate-address', {
  body: { zip_code: '01000', federal_entity: options.federal_entity, municipality: options.municipality },
  signal: AbortSignal.timeout(5000),
});
assert.equal(validation.valid, true);
console.log('Validación:', validation);

try {
  await api.GET('/zip-codes/{zipcode}', { params: { path: { zipcode: '99999' } }, signal: AbortSignal.timeout(5000) });
  throw new Error('Se esperaba 404');
} catch (error) {
  if (!(error instanceof PostalApiError) || error.status !== 404) throw error;
  console.log('Error manejado:', error.status, error.message);
}
