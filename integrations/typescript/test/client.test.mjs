import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createPostalClient, PostalApiError, retryAfterMs } from '../dist/index.js';
const json = (body, status = 200, headers = {}) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });
test('conserva CP y cuerpo JSON', async () => {
  const client = createPostalClient({ baseUrl: 'http://localhost', fetch: async request => {
    assert.equal(new URL(request.url).pathname, '/zip-codes/validate-address');
    assert.equal((await request.json()).zip_code, '01000'); return json({ valid: true });
  }});
  const { data } = await client.POST('/zip-codes/validate-address', { body: { zip_code: '01000', federal_entity: 'Ciudad de México', municipality: 'Álvaro Obregón' } });
  assert.equal(data.valid, true);
});
test('reintenta GET 429 respetando Retry-After', async () => {
  let calls = 0;
  const client = createPostalClient({ baseUrl: 'http://localhost', fetch: async () => ++calls === 1 ? json({}, 429, { 'Retry-After': '0' }) : json({ zip_code: '01000' }) });
  const { data } = await client.GET('/zip-codes/{zipcode}/address', { params: { path: { zipcode: '01000' } } });
  assert.equal(calls, 2); assert.equal(data.zip_code, '01000');
});
test('expone errores y no reintenta POST ni esperas largas', async () => {
  let calls = 0;
  const client = createPostalClient({ baseUrl: 'http://localhost', fetch: async () => { calls++; return json({ message: 'Límite' }, 429, { 'Retry-After': '60' }); } });
  await assert.rejects(client.POST('/zip-codes/validate-address', { body: { zip_code: '01000', federal_entity: 'x', municipality: 'y' } }), e => e instanceof PostalApiError && e.retryAfterMs === 60000);
  await assert.rejects(client.GET('/zip-codes/{zipcode}', { params: { path: { zipcode: '01000' } } }), e => e.status === 429);
  assert.equal(calls, 2);
});
test('404 conserva cuerpo y mensaje', async () => {
  const client = createPostalClient({ baseUrl: 'http://localhost', fetch: async () => json({ message: 'No existe' }, 404) });
  await assert.rejects(client.GET('/zip-codes/{zipcode}', { params: { path: { zipcode: '99999' } } }), e => e.message === 'No existe' && e.status === 404);
});
test('Retry-After acepta fecha HTTP y fallback', () => {
  assert.equal(retryAfterMs(new Headers({ 'Retry-After': 'Tue, 03 Nov 2026 12:00:00 GMT' }), Date.parse('2026-11-03T11:59:58Z')), 2000);
  assert.equal(retryAfterMs(new Headers({ 'X-RateLimit-Retry-After-Seconds': '3' })), 3000);
});
test('cancelar interrumpe la espera de reintento', async () => {
  const abort = new AbortController(); let calls = 0;
  const client = createPostalClient({ baseUrl: 'http://localhost', fetch: async () => { calls++; return json({}, 429, { 'Retry-After': '10' }); } });
  const pending = client.GET('/zip-codes/{zipcode}', { params: { path: { zipcode: '01000' } }, signal: abort.signal });
  setTimeout(() => abort.abort(), 10);
  await assert.rejects(pending, e => e.name === 'AbortError'); assert.equal(calls, 1);
});
