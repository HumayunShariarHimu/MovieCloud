const ROOT="https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/builds/";
const INDEX=ROOT+"artifact-index.json";
const providers=[
{name:"BasPlayFTP",desc:"Bangla movie and series provider.",icon:null},
{name:"CinePlexFTP",desc:"Cine Plex streaming provider.",icon:"https://cineplexbd.net/favicon.png?v=2"},
{name:"CTGFTP",desc:"Chattogram-focused movie provider.",icon:"https://i.postimg.cc/QMxBDF9T/favicon-V2.png"},
{name:"DhakaFTP",desc:"Dhaka FTP movie provider.",icon:null},
{name:"DiscoveryFTP",desc:"Discovery FTP content provider.",icon:null},
{name:"HiAnime",desc:"Anime-focused CloudStream provider.",icon:"https://hianime.at/theme/images/icons-192.png"},
{name:"KhulnaPlex",desc:"Khulna Plex streaming provider.",icon:null},
{name:"MojaLoss",desc:"Movie and entertainment provider.",icon:"https://www.mojaloss.stream/favicon.png"},
{name:"MovieHaat",desc:"Movie Haat streaming provider.",icon:null},
{name:"MovieLinkBD",desc:"MovieLinkBD provider for Bangla content.",icon:"https://movielinkbd.tv/favicon.png"},
{name:"OnlineMovies",desc:"Online movie streaming provider.",icon:null},
{name:"YouTube",desc:"YouTube content provider.",icon:null},
{name:"YouTubeKids",desc:"Bengali India YouTube Kids provider.",icon:"https://upload.wikimedia.org/wikipedia/commons/thumb/f/fb/YouTube_Kids_LogoVector.svg/512px-YouTube_Kids_LogoVector.svg.png"},
{name:"Zoryva",desc:"Zoryva CloudStream provider.",icon:"https://zoryva.me/icon-512.png"},
{name:"MovieBox",desc:"MovieBox provider packaged for MovieCloud.",icon:"https://github.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/raw/refs/heads/master/Logos/MovieBoxProvider/icon.png"}
];
const $=s=>document.querySelector(s);let artifacts={providers:{}};let artifactState="loading";
function fileFor(name,ext){const f=artifacts.providers?.[name]?.[ext];return f?ROOT+f:null}
function esc(s){return String(s).replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]))}
function card(p){
 const cs3=fileFor(p.name,"cs3"),jar=fileFor(p.name,"jar");
 const icon=p.icon?'<img class="icon" loading="lazy" src="'+p.icon+'" alt="" onerror="this.outerHTML=\'<div class="icon fallback">'+p.name.slice(0,2).toUpperCase()+'</div>\'">':'<div class="icon fallback">'+p.name.slice(0,2).toUpperCase()+'</div>';
 const status=artifactState==="loading"?'<span class="availability">Checking…</span>':cs3?'<span class="availability ok">CS3 ready</span>':'<span class="availability fail">Not published</span>';
 return '<article class="card" data-name="'+p.name.toLowerCase()+'" data-jar="'+Boolean(jar)+'" data-icon="'+Boolean(p.icon)+'">'+status+'<div class="card-head">'+icon+'<div><h3>'+esc(p.name)+'</h3><span class="tag">CloudStream provider</span></div></div><p class="card-desc">'+esc(p.desc)+'</p><div class="actions">'+(cs3?'<a class="cs3" href="'+cs3+'" target="_blank" rel="noopener noreferrer">CS3 ↗</a>':'<span class="disabled">CS3 —</span>')+(jar?'<a href="'+jar+'" target="_blank" rel="noopener noreferrer">JAR ↗</a>':'<span class="disabled">JAR —</span>')+(cs3?'<button class="copy" type="button" data-copy="'+cs3+'">Copy</button>':'')+'</div></article>'
}
function render(){
 const q=$("#search").value.trim().toLowerCase(),f=$("#filter").value;
 const list=providers.filter(p=>(!q||p.name.toLowerCase().includes(q)||p.desc.toLowerCase().includes(q))&&(f==="all"||(f==="jar"&&fileFor(p.name,"jar"))||(f==="icon"&&p.icon)));
 $("#providerGrid").innerHTML=list.map(card).join("");$("#empty").hidden=list.length>0;
 document.querySelectorAll(".copy").forEach(b=>b.onclick=async()=>{try{await navigator.clipboard.writeText(b.dataset.copy);const t=b.textContent;b.textContent="Copied ✓";setTimeout(()=>b.textContent=t,1000)}catch{b.textContent="Copy failed";setTimeout(()=>b.textContent="Copy",1200)}});
}
async function loadArtifacts(){
 artifactState="loading";render();
 try{const r=await fetch(INDEX,{cache:"no-store"});if(!r.ok)throw new Error("artifact index unavailable");const data=await r.json();if(!data||typeof data.providers!=="object")throw new Error("invalid artifact index");artifacts=data;artifactState="ready"}
 catch{artifacts={providers:{}};artifactState="unavailable"}
 const vals=Object.values(artifacts.providers||{});
 $("#providerCount").textContent=providers.length;$("#cs3Count").textContent=vals.filter(x=>x.cs3).length;$("#jarCount").textContent=vals.filter(x=>x.jar).length;render();
}
$("#search").addEventListener("input",render);$("#filter").addEventListener("change",render);
$("#copyRoot").addEventListener("click",async()=>{try{await navigator.clipboard.writeText(ROOT);const b=$("#copyRoot");b.textContent="Copied ✓";setTimeout(()=>b.textContent="Copy",1000)}catch{}});
loadArtifacts();
