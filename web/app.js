
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
CinePlexFTP:{url:"http://cineplexbd.net",label:"CinePlexBD",mode:"web"},
CTGFTP:{url:"https://ctgmovies.com",label:"CTG Movies",mode:"web"},
DiscoveryFTP:{url:"http://movies.discoveryftp.net",label:"Discovery Movies",mode:"web"},
HiAnime:{url:"https://hianime.at",label:"HiAnime",mode:"web"},
KhulnaPlex:{url:"http://khulnaplex.com",label:"KhulnaPlex",mode:"web"},
MojaLoss:{url:"https://www.mojaloss.stream",label:"MojaLoss",mode:"web"},
MovieHaat:{url:"https://moviehaat.net",label:"MovieHaat",mode:"web"},
MovieLinkBD:{url:"https://movielinkbd.tv",label:"MovieLinkBD",mode:"web"},
OnlineMovies:{url:"https://111.90.159.132",label:"OnlineMovies",mode:"web"},
YouTube:{url:"https://www.youtube.com",label:"YouTube",mode:"youtube"},
YouTubeKids:{url:"https://www.youtube.com/kids",label:"YouTube Kids",mode:"youtube"}
};

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
let artifactState="loading";

function esc(s){return String(s).replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]));}
function fileFor(name,ext){const f=artifacts.providers?.[name]?.[ext];return f?ROOT+f:null;}
function sourceFor(name){return GH+name;}
function streamFor(name){return STREAMING[name]||null;}
function iconFor(name){const u=ICONS[name];return u?'<img class="icon icon-svg" src="'+u+'" alt="'+esc(name)+' icon" loading="lazy">':'<span class="icon fallback" aria-hidden="true">MC</span>';}

function card(p){
 const cs3=fileFor(p.name,"cs3"),jar=fileFor(p.name,"jar"),stream=streamFor(p.name);
 const status=artifactState==="loading"?'<span class="availability">Checking…</span>':artifactState==="unavailable"?'<span class="availability fail">Index unavailable</span>':cs3?'<span class="availability ok">CS3 ready</span>':'<span class="availability fail">CS3 missing</span>';
 const jarAction=jar?'<a class="jar-action" href="'+jar+'" target="_blank" rel="noopener noreferrer">JAR ↗</a>':'<button class="disabled jar-action jar-help" type="button" data-jar-help="'+esc(p.name)+'" title="No separate JAR artifact is published">JAR</button>';
 const streamAction=stream?'<button class="stream-action" type="button" data-stream="'+esc(p.name)+'">Streaming ▶</button>':'<button class="stream-action unavailable-stream" type="button" data-stream="'+esc(p.name)+'">Streaming info</button>';
 return '<article class="card" data-name="'+p.name.toLowerCase()+'" data-jar="'+Boolean(jar)+'" data-streaming="'+Boolean(stream)+'">'+status+
 '<div class="card-head">'+iconFor(p.name)+'<div><h3>'+esc(p.name)+'</h3><span class="tag">CloudStream provider</span></div></div>'+
 '<p class="card-desc">'+esc(p.desc)+'</p><div class="actions">'+
 (cs3?'<a class="cs3" href="'+cs3+'" target="_blank" rel="noopener noreferrer">CS3 ↗</a>':'<span class="disabled">CS3 missing</span>')+
 jarAction+'<a href="'+sourceFor(p.name)+'" target="_blank" rel="noopener noreferrer">Source ↗</a></div>'+
 '<div class="stream-row">'+streamAction+'</div></article>';
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

function openStreaming(name){
  ensureModal();
  const cfg=streamFor(name);
  $("#streamTitle").textContent=name+" · Media Player";
  $("#streamSubtitle").textContent="Direct HLS/MP4 playback through the project's allowlisted media gateway.";

  $("#streamBody").innerHTML='<div class="tool-card">'+
    '<label for="mediaInput">Authorized direct media URL (HLS .m3u8 or MP4)</label>'+
    '<div class="inline-form"><input id="mediaInput" type="url" placeholder="https://your-domain.example/video.m3u8">'+
    '<button id="loadMedia" class="btn primary" type="button">Play</button></div>'+
    '<div class="quick-links">'+
      (cfg?.url?'<a href="'+esc(cfg.url)+'" target="_blank" rel="noopener noreferrer">Provider ↗</a>':"")+
      '<span>Only allowlisted/authorized media hosts are relayed.</span>'+
    '</div></div>'+
    '<div class="player-shell" id="playerShell"><div class="player-empty"><span>▶</span><p>Enter an authorized HLS or MP4 URL to start playback.</p></div></div>'+
    '<div class="browser-note"><b>Server gateway:</b> MovieCloud no longer loads provider pages in an iframe. The Vercel Function relays media bytes only for domains explicitly configured in <code>ALLOWED_STREAM_HOSTS</code>, preserving Range requests for seeking.</div>'+
    '<div class="viewer-footer"><span>For sources you own or are authorized to relay.</span></div>';

  $("#loadMedia").addEventListener("click",()=>{
    const media=directMediaUrl($("#mediaInput").value);
    if(!media){
      $("#mediaInput").focus();
      $("#mediaInput").classList.add("input-error");
      return;
    }
    $("#mediaInput").classList.remove("input-error");
    const gateway="/api/stream?url="+encodeURIComponent(media);
    const ext=media.split("?")[0].toLowerCase();
    const type=ext.endsWith(".m3u8")?"application/x-mpegURL":"video/mp4";
    $("#playerShell").innerHTML='<video class="player-frame native-player" controls playsinline preload="metadata" crossorigin="anonymous">'+
      '<source src="'+esc(gateway)+'" type="'+type+'">'+
      'Your browser does not support HTML5 video.</video>'+
      '<div class="player-error" hidden></div>';
    const video=$("#playerShell video");
    video.play().catch(()=>{});
    video.addEventListener("error",()=>{
      const e=$("#playerShell .player-error");
      e.hidden=false;
      e.textContent="Playback failed. Check that the source is a direct HLS/MP4 URL and its host is configured in Vercel ALLOWED_STREAM_HOSTS.";
    });
  });

  $("#streamModal").hidden=false;
  document.body.classList.add("modal-open");
  setTimeout(()=>$(".modal-close")?.focus(),0);
}

function closeStreaming(){
 const modal=$("#streamModal");if(!modal)return;
 modal.hidden=true;document.body.classList.remove("modal-open");
 const frame=$("#providerFrame");if(frame)frame.src="about:blank";
}

async function loadArtifacts(){
 artifactState="loading";render();
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
