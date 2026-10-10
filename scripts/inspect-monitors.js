const apiKey = 'a65a6c84cbe468582ca3cc16c8b8cf81';
const appKey = 'ddapp_ebWVMLRQjDpJM0lsv8ehu6R78qv63V8Xuu';

async function listMonitors() {
  const res = await fetch('https://api.datadoghq.com/api/v1/monitor', {
    headers: {
      'DD-API-KEY': apiKey,
      'DD-APPLICATION-KEY': appKey
    }
  });
  const monitors = await res.json();
  console.log('Total monitors:', monitors.length);
  for (const m of monitors) {
    if (m.name.includes('{{') || m.name.toLowerCase().includes('location') || m.name.includes('service')) {
      console.log(`[ID: ${m.id}] "${m.name}"`);
      console.log(`   Type: ${m.type} | Query: ${m.query}`);
    }
  }
}

listMonitors().catch(console.error);
