const ROOT="https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/builds/";
const INDEX=ROOT+"artifact-index.json";
const PLUGINS=ROOT+"plugins.json";
const GH="https://github.com/MyselfHumayunShariarHimu/MovieCloud/tree/main/";
const ICONS={"BasPlayFTP":"https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/BasPlayFTP/icon.svg","CinePlexFTP":"https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/CinePlexFTP/icon.svg","CTGFTP":"https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/CTGFTP/icon.svg","DhakaFTP":"https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/DhakaFTP/icon.svg","DiscoveryFTP":"https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/DiscoveryFTP/icon.svg","HiAnime":"https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/HiAnime/icon.svg","KhulnaPlex":"https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/KhulnaPlex/icon.svg","MojaLoss":"https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/MojaLoss/icon.svg","MovieHaat":"https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/MovieHaat/icon.svg","MovieLinkBD":"https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/MovieLinkBD/icon.svg","OnlineMovies":"https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/OnlineMovies/icon.svg","YouTube":"https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/YouTube/icon.svg","YouTubeKids":"https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/YouTubeKids/icon.svg","Zoryva":"https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/Zoryva/icon.svg","MovieBox":"https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/MovieBox/icon.svg"};
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

function fileFor(name,ext){const f=artifacts.providers?.[name]?.[ext];return f?ROOT+f:null}
function sourceFor(name){return GH+name}
function esc(s){return String(s).replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]))}

/* Local SVG icon: no remote SVG/image dependency, so every provider icon renders consistently on Vercel. */
function iconFor(name){const u=ICONS[name];return u?'<img class="icon icon-svg" src="'+u+'" alt="'+esc(name)+' icon" loading="lazy">':'<span class="icon fallback" aria-hidden="true">MC</span>'}

function card(p){
 const cs3=fileFor(p.name,"cs3"),jar=fileFor(p.name,"jar"),source=sourceFor(p.name),icon=ICONS[p.name];
 const status=artifactState==="loading"?'<span class="availability">Checking…</span>':artifactState==="unavailable"?'<span class="availability fail">Index unavailable</span>':cs3?'<span class="availability ok">CS3 ready</span>':'<span class="availability fail">CS3 missing</span>';
 return '<article class="card" data-name="'+p.name.toLowerCase()+'" data-jar="'+Boolean(jar)+'">'+status+
   '<div class="card-head">'+iconFor(p.name)+'<div><h3>'+esc(p.name)+'</h3><span class="tag">CloudStream provider</span></div></div>'+
   '<p class="card-desc">'+esc(p.desc)+'</p><div class="actions">'+
   (cs3?'<a class="cs3" href="'+cs3+'" target="_blank" rel="noopener noreferrer">CS3 ↗</a>':'<span class="disabled">'+(artifactState==="unavailable"?"CS3 unavailable":"CS3 missing")+'</span>')+
   (jar?'<a href="'+jar+'" target="_blank" rel="noopener noreferrer">JAR ↗</a>':'<span class="disabled" title="CloudStream installs the CS3 package directly">CS3 is the install package</span>')+
   '<a href="'+source+'" target="_blank" rel="noopener noreferrer">Source ↗</a>'+
   '</div></article>';
}

function render(){
 const q=$("#search").value.trim().toLowerCase(),f=$("#filter").value;
 const list=providers.filter(p=>(!q||p.name.toLowerCase().includes(q)||p.desc.toLowerCase().includes(q))&&
   (f==="all"||(f==="jar"&&fileFor(p.name,"jar"))));
 $("#providerGrid").innerHTML=list.map(card).join("");
 $("#empty").hidden=list.length>0;
}

async function loadArtifacts(){
 artifactState="loading";render();
 try{
   const r=await fetch(INDEX,{cache:"no-store"});
   if(!r.ok)throw new Error("artifact index unavailable");
   const data=await r.json();
   if(!data||typeof data.providers!=="object")throw new Error("invalid artifact index");
   artifacts=data;artifactState="ready";
 }catch{
   try{
     const r=await fetch(PLUGINS,{cache:"no-store"});
     if(!r.ok)throw new Error("plugin list unavailable");
     const list=await r.json();
     if(!Array.isArray(list))throw new Error("invalid plugin list");
     artifacts={providers:{}};
     for(const item of list){
       if(!item?.internalName)continue;
       artifacts.providers[item.internalName]={};
       if(item.url)artifacts.providers[item.internalName].cs3=item.url.split("/").pop();
       if(item.jarUrl)artifacts.providers[item.internalName].jar=item.jarUrl.split("/").pop();
     }
     artifactState="fallback";
   }catch{
     artifacts={providers:{}};artifactState="unavailable";
   }
 }
 const vals=Object.values(artifacts.providers||{});
 $("#providerCount").textContent=providers.length;
 $("#cs3Count").textContent=vals.filter(x=>x.cs3).length;
 $("#jarCount").textContent=vals.filter(x=>x.jar).length;
 render();
}

$("#search").addEventListener("input",render);
$("#filter").addEventListener("change",render);
$("#copyRoot").addEventListener("click",async()=>{
 try{
   await navigator.clipboard.writeText(ROOT);
   const b=$("#copyRoot");b.textContent="Copied ✓";setTimeout(()=>b.textContent="Copy",1000);
 }catch{
   const b=$("#copyRoot");b.textContent="Copy failed";setTimeout(()=>b.textContent="Copy",1200);
 }
});
loadArtifacts();
