const form = document.querySelector('#address-form');
const zip = document.querySelector('#zip');
const state = document.querySelector('#state');
const municipality = document.querySelector('#municipality');
const settlement = document.querySelector('#settlement');
const status = document.querySelector('#status');
const suggestions = document.querySelector('#zip-suggestions');
const alternatives = document.querySelector('#alternatives');
let timer, controller, revision = 0;
function message(text, error = false) { status.textContent = text; status.dataset.error = String(error); }
function clearOptions() {
  state.value = ''; municipality.value = ''; settlement.replaceChildren(new Option('Selecciona un código postal', ''));
  settlement.disabled = true; alternatives.replaceChildren(); suggestions.replaceChildren();
}
async function request(path, options = {}) {
  const response = await fetch(path, options);
  if (!response.ok) {
    if (response.status === 429) throw new Error(`Demasiadas consultas. Intenta nuevamente en ${response.headers.get('Retry-After') || 60} segundos.`);
    if (response.status === 404) throw new Error('No encontramos ese código postal. Revisa los cinco dígitos.');
    throw new Error('No fue posible consultar el catálogo. Intenta de nuevo.');
  }
  return response.json();
}
zip.addEventListener('input', () => {
  clearTimeout(timer); controller?.abort(); controller = new AbortController();
  const signal = controller.signal, current = ++revision, value = zip.value.trim();
  clearOptions();
  if (!/^[0-9]{1,5}$/.test(value)) { message('Escribe de uno a cinco dígitos.'); return; }
  message('Buscando…');
  timer = setTimeout(async () => {
    try {
      if (value.length < 5) {
        const items = await request(`/zip-codes/search?code=${encodeURIComponent(value)}&limit=8&simplified=true`, { signal });
        if (current !== revision) return;
        suggestions.replaceChildren(...items.map(item => new Option(`${item.municipality}, ${item.federal_entity}`, item.zip_code)));
        message('Selecciona una sugerencia o completa los cinco dígitos.');
      } else {
        const data = await request(`/zip-codes/${value}/address`, { signal });
        if (current !== revision) return;
        state.value = data.federal_entity; municipality.value = data.municipality;
        settlement.replaceChildren(...data.settlements.map(name => new Option(name, name)));
        settlement.disabled = false;
        message(`${data.settlements.length} colonia(s) disponibles. Puedes comprobar el estado y municipio.`);
      }
    } catch (error) { if (error.name !== 'AbortError' && current === revision) message(error.message, true); }
  }, 250);
});
form.addEventListener('submit', async event => {
  event.preventDefault(); const current = ++revision;
  clearTimeout(timer); controller?.abort(); controller = new AbortController();
  alternatives.replaceChildren(); message('Comprobando…');
  try {
    const data = await request('/zip-codes/validate-address', { method: 'POST', signal: controller.signal,
      headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ zip_code: zip.value.trim(), federal_entity: state.value, municipality: municipality.value }) });
    if (current !== revision) return;
    message(data.valid ? 'El código postal, estado y municipio coinciden.' : 'Los datos no coinciden. Revisa estas opciones del catálogo:', !data.valid);
    alternatives.replaceChildren(...data.suggestions.map(item => { const li = document.createElement('li'); li.textContent = `${item.zip_code} · ${item.municipality}, ${item.federal_entity}`; return li; }));
  } catch (error) { if (error.name !== 'AbortError' && current === revision) message(error.message, true); }
});
