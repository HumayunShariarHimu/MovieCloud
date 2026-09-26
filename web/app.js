
const ROOT="https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/builds/";
const INDEX=ROOT+"artifact-index.json";
const PLUGINS=ROOT+"plugins.json";
const GH="https://github.com/MyselfHumayunShariarHimu/MovieCloud/tree/main/";

const ICONS={
BasPlayFTP:"https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/BasPlayFTP/icon.svg",
CinePlexFTP:"https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/CinePlexFTP/icon.svg",
CTGFTP:"https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/CTGFTP/icon.svg",
DhakaFTP:"https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/DhakaFTP/icon.svg",
DiscoveryFTP:"https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/DiscoveryFTP/icon.svg",
HiAnime:"https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/HiAnime/icon.svg",
KhulnaPlex:"https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/KhulnaPlex/icon.svg",
MojaLoss:"https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/MojaLoss/icon.svg",
MovieHaat:"https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/MovieHaat/icon.svg",
MovieLinkBD:"https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/MovieLinkBD/icon.svg",
OnlineMovies:"https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/OnlineMovies/icon.svg",
YouTube:"https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/YouTube/icon.svg",
YouTubeKids:"https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/YouTubeKids/icon.svg",
Zoryva:"https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/Zoryva/icon.svg",
MovieBox:"https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/MovieBox/icon.svg"
};

const STREAMING={
  BasPlayFTP:{url:null,label:"BasPlayFTP",mode:"direct"},CinePlexFTP:{url:"http://cineplexbd.net",label:"CinePlexBD",mode:"direct"},CTGFTP:{url:"https://ctgmovies.com",label:"CTG Movies",mode:"direct"},DhakaFTP:{url:null,label:"DhakaFTP",mode:"direct"},DiscoveryFTP:{url:"https://movies.discoveryftp.net",label:"Discovery Movies",mode:"direct"},HiAnime:{url:"https://hianime.at",label:"HiAnime",mode:"direct"},KhulnaPlex:{url:"http://khulnaplex.com",label:"KhulnaPlex",mode:"direct"},MojaLoss:{url:"https://www.mojaloss.stream",label:"MojaLoss",mode:"direct"},MovieHaat:{url:"https://moviehaat.net",label:"MovieHaat",mode:"direct"},MovieLinkBD:{url:"https://movielinkbd.tv",label:"MovieLinkBD",mode:"direct"},OnlineMovies:{url:"https://111.90.159.132",label:"OnlineMovies",mode:"direct"},YouTube:{url:"https://www.youtube.com",label:"YouTube",mode:"direct"},YouTubeKids:{url:"https://www.youtube.com/kids",label:"YouTube Kids",mode:"direct"},Zoryva:{url:"https://zoryva.me",label:"Zoryva",mode:"direct"},MovieBox:{url:"https://movieboxonline.net",label:"MovieBox",mode:"direct"}
};
const PROVIDER_DETAILS=window.MOVIECLOUD_PROVIDER_DETAILS||{};

const providers=[
{name:"BasPlayFTP",desc:"Bangla movie and series provider."},
{name:"CinePlexFTP",desc:"Cine Plex streaming provider."},
{name:"CTGFTP",desc:"Chattogram-focused movie provider."},
{name:"DhakaFTP",desc:"Dhaka FTP movie provider."},
{name:"DiscoveryFTP",desc:"Discovery FTP content provider."},
{name:"HiAnime",desc:"Anime-focused CloudStream provider."},
{name:"KhulnaPlex",desc:"Khulna Plex streaming provider."},
{name:"MojaLoss",desc:"Movie and entertainment provider."},
{name:"MovieHaat",desc:"Movie Haat streaming provider."},
{name:"MovieLinkBD",desc:"MovieLinkBD provider for Bangla content."},
{name:"OnlineMovies",desc:"Online movie streaming provider."},
{name:"YouTube",desc:"YouTube content provider."},
{name:"YouTubeKids",desc:"Bengali India YouTube Kids provider."},
{name:"Zoryva",desc:"Zoryva CloudStream provider."},
{name:"MovieBox",desc:"MovieBox provider packaged for MovieCloud."}
];

const $=s=>document.querySelector(s);
let artifacts={providers:{}};
let mediaCatalog={providers:{}};
let artifactState="loading";

function esc(s){return String(s).replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]));}
function fileFor(name,ext){const f=artifacts.providers?.[name]?.[ext];return f?ROOT+f:null;}
function sourceFor(name){return GH+name;}
function streamFor(name){return STREAMING[name]||null;}
function iconFor(name){const u=ICONS[name];return u?'<img class="icon icon-svg" src="'+u+'" alt="'+esc(name)+' icon" loading="lazy">':'<span class="icon fallback" aria-hidden="true">MC</span>';}

function card(p){
 const cs3=fileFor(p.name,"cs3"),jar=fileFor(p.name,"jar"),stream=streamFor(p.name),detail=PROVIDER_DETAILS[p.name]||{};
 const status=artifactState==="loading"?'<span class="availability">Checking…</span>':artifactState==="unavailable"?'<span class="availability fail">Index unavailable</span>':cs3?'<span class="availability ok">CS3 ready</span>':'<span class="availability fail">CS3 missing</span>';
 const jarAction=jar?'<a class="jar-action" href="'+jar+'" target="_blank" rel="noopener noreferrer">JAR ↗</a>':'<button class="disabled jar-action jar-help" type="button" data-jar-help="'+esc(p.name)+'" title="No separate JAR artifact is published">JAR</button>';
 const site=detail.site?'<a class="site-link" href="'+esc(detail.site)+'" target="_blank" rel="noopener noreferrer">Provider ↗</a>':'<span class="site-link disabled">Network source</span>';
 return '<article class="card" data-name="'+p.name.toLowerCase()+'" data-jar="'+Boolean(jar)+'" data-streaming="'+Boolean(stream)+'">'+status+
 '<div class="card-head">'+iconFor(p.name)+'<div><h3>'+esc(p.name)+'</h3><span class="tag">'+esc(detail.media||"CloudStream provider")+'</span></div></div>'+
 '<p class="card-desc">'+esc(p.desc)+'</p><div class="actions">'+
 (cs3?'<a class="cs3" href="'+cs3+'" target="_blank" rel="noopener noreferrer">CS3 ↗</a>':'<span class="disabled">CS3 missing</span>')+
 jarAction+'<button class="details-action" type="button" data-provider-detail="'+esc(p.name)+'">Details</button></div>'+
 '<div class="stream-row"><div class="source-line">'+site+'<span class="source-type">'+esc(detail.engine||"CloudStream")+'</span></div><button class="stream-action" type="button" data-stream="'+esc(p.name)+'">Media Player ▶</button></div></article>';
}

function render(){
 const q=$("#search").value.trim().toLowerCase(),f=$("#filter").value;
 const list=providers.filter(p=>{const matches=!q||p.name.toLowerCase().includes(q)||p.desc.toLowerCase().includes(q);const mf=f==="all"||(f==="jar"&&fileFor(p.name,"jar"))||(f==="stream"&&streamFor(p.name));return matches&&mf;});
 $("#providerGrid").innerHTML=list.map(card).join("");
 $("#empty").hidden=list.length>0;
 bindCardActions();
}

function bindCardActions(){
 document.querySelectorAll("[data-stream]").forEach(btn=>btn.addEventListener("click",()=>openStreaming(btn.dataset.stream)));
 document.querySelectorAll("[data-jar-help]").forEach(btn=>btn.addEventListener("click",()=>showJarHelp(btn.dataset.jarHelp)));
 document.querySelectorAll("[data-provider-detail]").forEach(btn=>btn.addEventListener("click",()=>showProviderDetails(btn.dataset.providerDetail)));
}

function ensureModal(){
 if($("#streamModal"))return;
 document.body.insertAdjacentHTML("beforeend",
 '<div class="modal" id="streamModal" hidden><div class="modal-backdrop" data-close-stream></div>'+
 '<section class="stream-modal" role="dialog" aria-modal="true" aria-labelledby="streamTitle">'+
 '<header class="stream-modal-head"><div><div class="section-kicker">STREAMING WORKSPACE</div><h2 id="streamTitle">Provider</h2><p id="streamSubtitle">Open the provider website or watch compatible content here.</p></div>'+
 '<button class="modal-close" type="button" data-close-stream aria-label="Close">×</button></header><div id="streamBody"></div></section></div>');
 document.querySelectorAll("[data-close-stream]").forEach(el=>el.addEventListener("click",closeStreaming));
}

function showJarHelp(name){
 ensureModal();
 $("#streamTitle").textContent=name+" · JAR";
 $("#streamSubtitle").textContent="Artifact status";
 $("#streamBody").innerHTML='<div class="info-panel"><div class="info-icon">JAR</div><h3>No separate JAR published</h3>'+
 '<p>MovieCloud’s current verified build publishes the CloudStream <b>.cs3</b> package. There is no real '+esc(name)+'.jar file in the builds branch, so a fake JAR link is not exposed.</p>'+
 '<div class="info-actions"><button class="btn primary" type="button" data-close-stream>Close</button>'+
 '<a class="btn ghost" href="'+esc(fileFor(name,"cs3")||"#")+'" target="_blank" rel="noopener noreferrer">Open CS3 ↗</a></div></div>';
 $("#streamBody").querySelector("[data-close-stream]").addEventListener("click",closeStreaming);
 $("#streamModal").hidden=false;
 document.body.classList.add("modal-open");
}

function directMediaUrl(value){
  const v=String(value||"").trim();
  if(!v)return null;
  try{
    const u=new URL(v);
    if(!["http:","https:"].includes(u.protocol))return null;
    return u.href;
  }catch{return null;}
}

function providerCatalog(name){return mediaCatalog.providers?.[name]||{};}
function parseM3U(text){
 const lines=String(text||"").replace(/^\\uFEFF/,"").split(/\\r?\\n/),items=[];let meta={};
 for(const raw of lines){const line=raw.trim();if(!line)continue;
  if(line.startsWith("#EXTINF:")){const comma=line.indexOf(",");const attrs=comma>=0?line.slice(8,comma):line.slice(8);const title=comma>=0?line.slice(comma+1).trim():"Untitled";const m=attrs.match(/group-title="([^"]*)"/i);meta={title:title||"Untitled",group:m?m[1]:""};}
  else if(!line.startsWith("#")){const url=directMediaUrl(line);if(url)items.push({...meta,url});meta={};}
 }return items;
}
function showPlaylistItems(items){
 const box=$("#playlistItems");if(!box)return;
 box.innerHTML=items.length?items.map((x,i)=>'<button class="playlist-item" type="button" data-playlist-index="'+i+'"><span class="playlist-thumb">▶</span><span class="playlist-copy"><b>'+esc(x.title||"Untitled")+'</b><small>'+esc(x.group||"Media source")+'</small></span><span class="playlist-play">Play</span></button>').join(""):'<div class="playlist-empty">No playable entries found.</div>';
 box.querySelectorAll("[data-playlist-index]").forEach(b=>b.addEventListener("click",()=>playMedia(items[Number(b.dataset.playlistIndex)].url,items[Number(b.dataset.playlistIndex)].title)));
}
async function loadM3U(){
 const url=directMediaUrl($("#m3uInput").value),status=$("#playerStatus");
 if(!url){status.textContent="Enter a valid M3U/M3U8 playlist URL.";return;}
 status.textContent="Loading playlist…";
 try{const r=await fetch(url,{cache:"no-store"});if(!r.ok)throw new Error("HTTP "+r.status);const items=parseM3U(await r.text());showPlaylistItems(items);status.textContent=items.length+" playable playlist source(s) found.";}
 catch(error){status.textContent="Playlist could not be read in the browser. If the playlist server blocks CORS, paste the M3U text or use a direct media URL instead.";}
}
function openStreaming(name){
 ensureModal();
 const detail=PROVIDER_DETAILS[name]||{};
 const catalog=mediaCatalog.providers?.[name]||{};
 const sources=Array.isArray(catalog.sources)?catalog.sources.filter(x=>directMediaUrl(x?.url)):[];
 $("#streamTitle").textContent=name+" · Media Player";
 $("#streamSubtitle").textContent="Repository-aware native media workspace — no provider webpage iframe.";
 const site=detail.site?'<a class="btn ghost" href="'+esc(detail.site)+'" target="_blank" rel="noopener noreferrer">Provider ↗</a>':"";
 const caps=(detail.capabilities||[]).map(x=>'<li>'+esc(x)+'</li>').join("");
 const auto=sources.length?'<div class="source-list"><div class="source-list-head"><b>Automatic repository sources</b><span>'+sources.length+' ready</span></div>'+sources.map((x,i)=>'<button class="playlist-item" type="button" data-auto-source="'+i+'"><span class="playlist-thumb">▶</span><span class="playlist-copy"><b>'+esc(x.label||("Repository source "+(i+1)))+'</b><small>'+esc(x.type||"Direct media")+'</small></span><span class="playlist-play">Play</span></button>').join("")+'</div>':'<div class="auto-empty"><b>Automatic source slot is ready.</b><p>No direct media URL is declared in the web-safe repository catalog for this provider. The Kotlin/CS3 provider remains available to CloudStream, but the browser cannot execute that Android plugin.</p></div>';
 $("#streamBody").innerHTML='<div class="stream-layout"><aside class="source-panel"><div class="section-kicker">REPOSITORY SOURCE MAP</div><h3>'+esc(detail.label||name)+'</h3><p class="source-engine">'+esc(detail.engine||"CloudStream provider")+'</p><div class="source-meta"><span>Media</span><b>'+esc(detail.media||"Direct media")+'</b></div><ul class="capability-list">'+caps+'</ul><div class="source-path"><span>Repository source</span><code>'+esc(detail.source||"")+'</code></div><p class="source-note">'+esc(detail.note||"Use an authorized direct media URL for browser playback.")+'</p><div class="info-actions">'+site+'<button class="btn primary" type="button" data-close-stream>Close</button></div></aside><section class="player-panel">'+auto+'<div class="tool-card"><label for="m3uInput">M3U / M3U8 playlist URL <span>(optional)</span></label><div class="inline-form"><input id="m3uInput" type="url" inputmode="url" autocomplete="off" placeholder="https://your-domain.example/playlist.m3u"><button id="loadM3U" class="btn ghost" type="button">Load</button></div><div id="playlistItems" class="playlist-list"></div><label for="mediaInput" class="secondary-label">Or authorized direct media URL</label><div class="inline-form"><input id="mediaInput" type="url" inputmode="url" autocomplete="off" placeholder="https://your-domain.example/video.m3u8 or .mp4"><button id="loadMedia" class="btn primary" type="button">Play</button></div><div class="quick-links"><span>MP4/HLS sources work when they are authorized and their hosts are allowlisted.</span></div></div><div class="player-shell" id="playerShell"><div class="player-empty"><span>▶</span><p>Choose an automatic repository source or enter a direct media URL.</p></div></div><div class="player-status" id="playerStatus" role="status">Ready.</div></section></div><div class="browser-note"><b>Important:</b> CloudStream <code>.cs3</code> files are Android/CloudStream plugins, not browser JavaScript. The web app can automatically play only direct media URLs explicitly declared in the repository's web-safe catalog; it does not scrape provider pages or bypass playback controls.</div>';
 document.querySelectorAll("[data-close-stream]").forEach(el=>el.addEventListener("click",closeStreaming));
 document.querySelectorAll("[data-auto-source]").forEach(el=>el.addEventListener("click",()=>playMedia(sources[Number(el.dataset.autoSource)].url,sources[Number(el.dataset.autoSource)].label)));
 $("#loadM3U").addEventListener("click",loadM3U);$("#loadMedia").addEventListener("click",()=>playMedia($("#mediaInput").value));
 $("#streamModal").hidden=false;document.body.classList.add("modal-open");
 if(sources.length===1)setTimeout(()=>playMedia(sources[0].url,sources[0].label),60);
}
function playMedia(value){
 const media=directMediaUrl(value),input=$("#mediaInput"),shell=$("#playerShell"),status=$("#playerStatus");
 if(!media){input.classList.add("input-error");status.textContent="Enter a valid http(s) MP4 or HLS URL.";input.focus();return;}
 input.classList.remove("input-error");
 const gateway="/api/stream?url="+encodeURIComponent(media),isHls=/\.m3u8(?:$|[?#])/i.test(media);
 shell.innerHTML='<video class="player-frame native-player" id="nativePlayer" controls playsinline preload="metadata" crossorigin="anonymous"></video><div class="player-error" hidden></div>';
 const video=$("#nativePlayer"),err=msg=>{const e=shell.querySelector(".player-error");e.hidden=false;e.textContent=msg;status.textContent=msg;};
 status.textContent=isHls?"Loading HLS through the authorized gateway…":"Loading MP4 through the authorized gateway…";
 if(isHls&&window.Hls&&Hls.isSupported()){const hls=new Hls({enableWorker:true});video.__hls=hls;hls.loadSource(gateway);hls.attachMedia(video);hls.on(Hls.Events.MANIFEST_PARSED,()=>{status.textContent="HLS ready — press Play.";video.play().catch(()=>{});});hls.on(Hls.Events.ERROR,(_,data)=>{if(data?.fatal){try{hls.destroy()}catch{};err("HLS playback failed. Verify the allowlisted host and media manifest.");}});}
 else if(isHls&&video.canPlayType("application/vnd.apple.mpegurl")){video.src=gateway;video.addEventListener("loadedmetadata",()=>{status.textContent="HLS ready — press Play.";video.play().catch(()=>{});});}
 else{video.src=gateway;video.addEventListener("loadedmetadata",()=>{status.textContent="MP4 ready — press Play.";});}
 video.addEventListener("error",()=>err("Playback failed. Verify that the URL is a direct MP4/HLS source and its host is configured in ALLOWED_STREAM_HOSTS."));
}
function showProviderDetails(name){
 ensureModal();const d=PROVIDER_DETAILS[name]||{};
 $("#streamTitle").textContent=name+" · Source Details";$("#streamSubtitle").textContent="Repository-derived provider/source information.";
 const site=d.site?'<a class="btn ghost" href="'+esc(d.site)+'" target="_blank" rel="noopener noreferrer">Provider ↗</a>':"";
 $("#streamBody").innerHTML='<div class="info-panel source-detail-panel"><div class="info-icon">MC</div><h3>'+esc(d.label||name)+'</h3><p class="detail-lead">'+esc(d.note||"Provider metadata is defined in the MovieCloud repository.")+'</p><div class="detail-grid"><div><span>Engine</span><b>'+esc(d.engine||"CloudStream")+'</b></div><div><span>Media</span><b>'+esc(d.media||"Direct media")+'</b></div><div><span>Source file</span><code>'+esc(d.source||"")+'</code></div></div><ul class="capability-list centered">'+(d.capabilities||[]).map(x=>'<li>'+esc(x)+'</li>').join("")+'</ul><div class="info-actions">'+site+'<button class="btn primary" type="button" data-open-media>Open Media Player</button><button class="btn ghost" type="button" data-close-stream>Close</button></div></div>';
 $("#streamBody").querySelector("[data-open-media]").addEventListener("click",()=>openStreaming(name));$("#streamBody").querySelector("[data-close-stream]").addEventListener("click",closeStreaming);$("#streamModal").hidden=false;document.body.classList.add("modal-open");
}
function closeStreaming(){
 const modal=$("#streamModal");if(!modal)return;const video=$("#nativePlayer");if(video?.__hls){try{video.__hls.destroy()}catch{}}if(video){try{video.pause();video.removeAttribute("src");video.load()}catch{}}modal.hidden=true;document.body.classList.remove("modal-open");
}

async function loadArtifacts(){
 artifactState="loading";render();
 try{const mr=await fetch("/web/media-catalog.json",{cache:"no-store"});if(mr.ok){const md=await mr.json();if(md&&typeof md.providers==="object")mediaCatalog=md;}}catch{mediaCatalog={providers:{}}}
 try{
   const r=await fetch(INDEX,{cache:"no-store"});if(!r.ok)throw new Error();
   const data=await r.json();if(!data||typeof data.providers!=="object")throw new Error();
   artifacts=data;artifactState="ready";
 }catch{
   try{
     const r=await fetch(PLUGINS,{cache:"no-store"});if(!r.ok)throw new Error();
     const list=await r.json();if(!Array.isArray(list))throw new Error();
     artifacts={providers:{}};
     for(const item of list){if(!item?.internalName)continue;artifacts.providers[item.internalName]={};if(item.url)artifacts.providers[item.internalName].cs3=item.url.split("/").pop();if(item.jarUrl)artifacts.providers[item.internalName].jar=item.jarUrl.split("/").pop();}
     artifactState="fallback";
   }catch{artifacts={providers:{}};artifactState="unavailable";}
 }
 const vals=Object.values(artifacts.providers||{});
 $("#providerCount").textContent=providers.length;
 $("#cs3Count").textContent=vals.filter(x=>x.cs3).length;
 $("#jarCount").textContent=vals.filter(x=>x.jar).length;
 const sc=$("#streamCount");if(sc)sc.textContent=providers.filter(p=>streamFor(p.name)).length;
 render();
}

$("#search").addEventListener("input",render);
$("#filter").addEventListener("change",render);
$("#copyRoot").addEventListener("click",async()=>{
 try{await navigator.clipboard.writeText(ROOT);const b=$("#copyRoot");b.textContent="Copied ✓";setTimeout(()=>b.textContent="Copy",1000);}
 catch{const b=$("#copyRoot");b.textContent="Copy failed";setTimeout(()=>b.textContent="Copy",1200);}
});
document.addEventListener("keydown",e=>{if(e.key==="Escape")closeStreaming();});
ensureModal();
loadArtifacts();
