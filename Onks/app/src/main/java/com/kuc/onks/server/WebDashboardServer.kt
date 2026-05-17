package com.kuc.onks.server

import android.util.Log
import com.google.gson.Gson
import com.kuc.onks.room.RoomManager
import fi.iki.elonen.NanoHTTPD

/**
 * Embedded HTTP server on port 8080.
 * Serves a responsive HTML dashboard — controls only, no audio streaming.
 * Open http://[host-ip]:8080 on any laptop browser on the same WiFi.
 */
class WebDashboardServer(
    private val roomManager: RoomManager,
    port: Int = 8080
) : NanoHTTPD(port) {

    companion object { private const val TAG = "WebDashboard" }

    private val gson = Gson()
    var lastCue: String = ""

    var onCueReceived: ((String) -> Unit)? = null
    var onMuteToggle: ((String, Boolean) -> Unit)? = null
    var onVolumeChange: ((String, Float) -> Unit)? = null

    override fun serve(session: IHTTPSession): Response {
        return when {
            session.uri == "/" || session.uri == "/dashboard" -> htmlResponse(buildHtml())
            session.uri == "/api/performers" && session.method == Method.GET -> performersJson()
            session.uri == "/api/status" -> statusJson()
            session.uri == "/api/mute" && session.method == Method.POST -> handleMute(session)
            session.uri == "/api/volume" && session.method == Method.POST -> handleVolume(session)
            session.uri == "/api/cue" && session.method == Method.POST -> handleCue(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun performersJson(): Response {
        val data = roomManager.performers.value.map { p ->
            mapOf("id" to p.id, "name" to p.name, "isMuted" to p.isMuted,
                "volume" to p.volume, "isTransmitting" to p.isTransmitting,
                "isConnected" to p.isConnected)
        }
        return jsonResponse(data)
    }

    private fun statusJson(): Response = jsonResponse(mapOf(
        "room" to RoomManager.ROOM_NAME,
        "pin" to roomManager.pin,
        "connectedCount" to roomManager.performers.value.size,
        "transmitting" to (roomManager.transmittingPerformer.value ?: ""),
        "lastCue" to lastCue,
        "ip" to roomManager.getLocalIpAddress()
    ))

    private fun handleMute(session: IHTTPSession): Response {
        val p = parseBody(session)
        val id = p["id"] ?: return bad("Missing id")
        val muted = p["muted"]?.toBoolean() ?: return bad("Missing muted")
        roomManager.mutePerformer(id, muted)
        onMuteToggle?.invoke(id, muted)
        return jsonResponse(mapOf("ok" to true))
    }

    private fun handleVolume(session: IHTTPSession): Response {
        val p = parseBody(session)
        val id = p["id"] ?: return bad("Missing id")
        val vol = p["volume"]?.toFloatOrNull() ?: return bad("Invalid volume")
        roomManager.setPerformerVolume(id, vol)
        onVolumeChange?.invoke(id, vol)
        return jsonResponse(mapOf("ok" to true))
    }

    private fun handleCue(session: IHTTPSession): Response {
        val p = parseBody(session)
        val cue = p["cue"] ?: return bad("Missing cue")
        lastCue = cue
        onCueReceived?.invoke(cue)
        Log.i(TAG, "Cue: $cue")
        return jsonResponse(mapOf("ok" to true, "cue" to cue))
    }

    private fun jsonResponse(obj: Any): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(obj))
            .also { it.addHeader("Access-Control-Allow-Origin", "*") }

    private fun htmlResponse(html: String) =
        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)

    private fun bad(msg: String) =
        newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, msg)

    @Suppress("UNCHECKED_CAST")
    private fun parseBody(session: IHTTPSession): Map<String, String> {
        val files = mutableMapOf<String, String>()
        return try {
            session.parseBody(files)
            gson.fromJson(files["postData"] ?: "{}", Map::class.java) as Map<String, String>
        } catch (e: Exception) { emptyMap() }
    }

    private fun buildHtml() = """<!DOCTYPE html>
<html lang="en"><head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Onks – Stage Dashboard</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{background:#121212;color:#fff;font-family:system-ui,sans-serif;padding:20px;max-width:900px;margin:0 auto}
h1{color:#9C4DCC;font-size:1.8rem;margin-bottom:2px}
.sub{color:#555;font-size:.85rem;margin-bottom:18px}
.bar{background:#1e1e1e;border-radius:12px;padding:14px 18px;margin-bottom:14px;display:flex;gap:16px;align-items:center;flex-wrap:wrap;border:1px solid #2a2a2a}
.pill{padding:5px 14px;border-radius:999px;font-size:.8rem;font-weight:700}
.green{background:#1a3a1a;color:#4CAF50}.orange{background:#3a2500;color:#FF9800}
.red{background:#3a0000;color:#F44336;animation:pulse 1s infinite}
@keyframes pulse{0%,100%{opacity:1}50%{opacity:.55}}
.card{background:#1e1e1e;border-radius:12px;padding:12px 16px;margin-bottom:8px;display:flex;align-items:center;gap:12px;flex-wrap:wrap;border:1px solid #2a2a2a}
.name{font-weight:700;flex:1;min-width:80px}.dot{width:11px;height:11px;border-radius:50%;flex-shrink:0}
button{border:none;cursor:pointer;border-radius:8px;padding:7px 16px;font-weight:700;font-size:.85rem;transition:.15s}
.mbtn{background:#2a2a2a;color:#aaa}.mbtn.on{background:#3a0000;color:#F44336}
input[type=range]{accent-color:#9C4DCC;width:110px;cursor:pointer}
.cue{background:#1e1e1e;border-radius:12px;padding:16px 18px;margin-top:6px;border:1px solid #2a2a2a}
.cue h2{font-size:1rem;color:#9C4DCC;margin-bottom:10px}
.crow{display:flex;gap:8px}
.crow input{flex:1;background:#2a2a2a;border:1px solid #444;color:#fff;border-radius:8px;padding:8px 12px}
.crow button{background:#6A1B9A;color:#fff}
.presets{display:flex;flex-wrap:wrap;gap:6px;margin-top:10px}
.presets button{background:#2a2a2a;color:#BB86FC;font-size:.78rem;padding:5px 11px}
#lcue{margin-top:10px;color:#FF9800;font-size:.88rem;min-height:18px}
footer{margin-top:22px;color:#333;font-size:.72rem;text-align:center}
</style></head><body>
<h1>Onks</h1><p class="sub">Kiriwina United Church D&amp;D – Stage Dashboard</p>
<div class="bar">
  <span>Room: <b id="rn">—</b></span>
  <span>PIN: <b id="pd" style="color:#FFEB3B;letter-spacing:3px">——</b></span>
  <span id="txs" class="pill green">● Standby</span>
  <span id="cnt" style="color:#666">0 performers</span>
</div>
<div id="plist"></div>
<div class="cue">
  <h2>Text Cue to Performers</h2>
  <div class="crow">
    <input id="ci" type="text" placeholder="Type a cue..." maxlength="120">
    <button onclick="sendCue()">Send Cue</button>
  </div>
  <div class="presets">
    <button onclick="sc('STANDBY')">STANDBY</button>
    <button onclick="sc('PLACES PLEASE')">PLACES PLEASE</button>
    <button onclick="sc('QUIET ON STAGE')">QUIET ON STAGE</button>
    <button onclick="sc('5 MINUTES')">5 MINUTES</button>
    <button onclick="sc('GO GO GO')">GO GO GO</button>
    <button onclick="sc('CURTAIN UP')">CURTAIN UP</button>
    <button onclick="sc('CURTAIN DOWN')">CURTAIN DOWN</button>
  </div>
  <div id="lcue"></div>
</div>
<footer>Onks v1.0 · Local network only · No internet required</footer>
<script>
async function refresh(){
  try{
    const[s,p]=await Promise.all([
      fetch('/api/status').then(r=>r.json()),
      fetch('/api/performers').then(r=>r.json())
    ]);
    document.getElementById('rn').textContent=s.room||'—';
    document.getElementById('pd').textContent=s.pin||'——';
    document.getElementById('cnt').textContent=(s.connectedCount||0)+' performer(s)';
    const tx=document.getElementById('txs');
    if(s.transmitting){tx.className='pill red';tx.textContent='● LIVE – '+s.transmitting.split(':').pop();}
    else{tx.className='pill green';tx.textContent='● Standby';}
    render(p);
  }catch(e){console.warn(e)}
}
function render(list){
  const el=document.getElementById('plist');
  if(!list.length){el.innerHTML='<p style="color:#444;margin:10px 0">No performers connected yet</p>';return;}
  el.innerHTML=list.map(p=>`
    <div class="card">
      <div class="dot" style="background:${p.isTransmitting?'#F44336':p.isConnected?'#4CAF50':'#444'}"></div>
      <span class="name">${p.name}</span>
      <span style="font-size:.8rem;color:#555">${p.isTransmitting?'🎙 LIVE':p.isMuted?'🔇 Muted':'Ready'}</span>
      <button class="mbtn ${p.isMuted?'on':''}" onclick="toggleMute('${p.id}',${!p.isMuted})">${p.isMuted?'Unmute':'Mute'}</button>
      <input type="range" min="0" max="1" step="0.05" value="${p.volume}" onchange="setVol('${p.id}',this.value)" title="Volume for ${p.name}">
    </div>`).join('');
}
async function toggleMute(id,muted){
  await fetch('/api/mute',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({id,muted})});
  refresh();
}
async function setVol(id,volume){
  await fetch('/api/volume',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({id,volume:parseFloat(volume)})});
}
async function sendCue(){
  const cue=document.getElementById('ci').value.trim();
  if(!cue)return;
  await fetch('/api/cue',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({cue})});
  document.getElementById('lcue').textContent='Sent: '+cue;
  document.getElementById('ci').value='';
}
function sc(t){document.getElementById('ci').value=t;}
setInterval(refresh,1500);refresh();
</script></body></html>"""
}
