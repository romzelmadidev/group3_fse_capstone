const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = 8990;
const FILE_PATH = path.resolve(__dirname, '../aurabank_breakscale_simulator.html');

const server = http.createServer((req, res) => {
  if (fs.existsSync(FILE_PATH)) {
    const content = fs.readFileSync(FILE_PATH, 'utf-8');
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(content);
  } else {
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('Simulator HTML file not found.');
  }
});

server.listen(PORT, () => {
  console.log('================================================================');
  console.log('  AuraBank Breakscale Architecture Simulator Server');
  console.log(`  Access the interactive canvas at: http://localhost:${PORT}`);
  console.log('================================================================');
});
