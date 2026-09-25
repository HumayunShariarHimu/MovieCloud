
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

function youtubeId(value){
 const v=String(value||"").trim();
 if(!v)return null;
 const patterns=[/[?&]v=([A-Za-z0-9_-]{6,})/,/youtu\.be\/([A-Za-z0-9_-]{6,})/,/youtube\.com\/(?:shorts|live|embed)\/([A-Za-z0-9_-]{6,})/];
 for(const re of patterns){const m=v.match(re);if(m)return m[1];}
 return /^[A-Za-z0-9_-]{6,}$/.test(v)?v:null;
}

function openStreaming(name){
 ensureModal();
 const cfg=streamFor(name);
 $("#streamTitle").textContent=name+" · Streaming";
 $("#streamSubtitle").textContent=cfg?"Browser streaming workspace":"No verified public browser endpoint configured for this provider.";
 if(!cfg){
   $("#streamBody").innerHTML='<div class="info-panel"><div class="info-icon">WEB</div><h3>Provider endpoint is not publicly configured</h3>'+
   '<p>The CloudStream provider can still work through its published CS3 package. A stable public browser endpoint was not added here because the source does not expose one.</p>'+
   '<div class="info-actions"><a class="btn primary" href="'+sourceFor(name)+'" target="_blank" rel="noopener noreferrer">Open source ↗</a></div></div>';
 }else if(cfg.mode==="youtube"){
   $("#streamBody").innerHTML='<div class="youtube-tools"><div class="tool-card"><label for="videoInput">YouTube video / Shorts / live URL or video ID</label>'+
   '<div class="inline-form"><input id="videoInput" type="text" placeholder="Paste a YouTube URL or ID"><button id="loadVideo" class="btn primary" type="button">Watch</button></div>'+
   '<div class="quick-links"><a href="'+cfg.url+'" target="_blank" rel="noopener noreferrer">Browse ↗</a><a href="'+cfg.url+'/live" target="_blank" rel="noopener noreferrer">Live ↗</a><a href="'+cfg.url+'/results?search_query=movies" target="_blank" rel="noopener noreferrer">Search ↗</a></div></div></div>'+
   '<div class="player-shell" id="playerShell"><div class="player-empty"><span>▶</span><p>Paste a video URL above to load the official YouTube player.</p></div></div>'+
   '<div class="viewer-footer"><a class="btn ghost" href="'+cfg.url+'" target="_blank" rel="noopener noreferrer">Open externally ↗</a><span>Playback uses YouTube’s official embed player.</span></div>';
   $("#loadVideo").addEventListener("click",()=>{
     const id=youtubeId($("#videoInput").value);
     if(!id){$("#videoInput").focus();$("#videoInput").classList.add("input-error");return;}
     $("#videoInput").classList.remove("input-error");
     $("#playerShell").innerHTML='<iframe class="player-frame" src="https://www.youtube-nocookie.com/embed/'+encodeURIComponent(id)+'?autoplay=1&playsinline=1&rel=0" title="YouTube video player" allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowfullscreen referrerpolicy="strict-origin-when-cross-origin"></iframe>';
   });
 }else{
   $("#streamBody").innerHTML='<div class="viewer-toolbar"><div><b>'+esc(cfg.label)+'</b><span>'+esc(cfg.url)+'</span></div>'+
   '<div class="viewer-actions"><button class="btn ghost" id="reloadFrame" type="button">Reload</button><a class="btn primary" href="'+cfg.url+'" target="_blank" rel="noopener noreferrer">Open externally ↗</a></div></div>'+
   '<div class="browser-note"><b>Browser limitation:</b> some providers block iframe embedding with X-Frame-Options/CSP, use HTTP, or require a local/ISP network. If the viewer is blank, use <b>Open externally</b>.</div>'+
   '<div class="web-viewer"><iframe id="providerFrame" src="'+cfg.url+'" title="'+esc(cfg.label)+' browser viewer" loading="eager" referrerpolicy="strict-origin-when-cross-origin" sandbox="allow-forms allow-scripts allow-same-origin allow-popups allow-presentation"></iframe></div>'+
   '<div class="viewer-footer"><a class="btn ghost" href="'+cfg.url+'" target="_blank" rel="noopener noreferrer">Open '+esc(cfg.label)+' ↗</a><span>MovieCloud does not proxy or bypass provider security restrictions.</span></div>';
   $("#reloadFrame").addEventListener("click",()=>{const f=$("#providerFrame");if(f)f.src=cfg.url;});
 }
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
