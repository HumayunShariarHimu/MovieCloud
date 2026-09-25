const ROOT="https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/builds/";
const INDEX=ROOT+"artifact-index.json";
const GH="https://github.com/MyselfHumayunShariarHimu/MovieCloud/tree/main/";
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
function iconFor(name){
  const letters=name.replace(/[^A-Za-z0-9]/g,"").slice(0,2).toUpperCase();
  const hue=Math.abs([...name].reduce((n,c)=>n+c.charCodeAt(0),0))%360;
  return '<svg class="icon icon-svg" viewBox="0 0 64 64" role="img" aria-label="'+esc(name)+' icon" xmlns="http://www.w3.org/2000/svg">'+
    '<defs><linearGradient id="g'+hue+'" x1="0" y1="0" x2="1" y2="1"><stop stop-color="hsl('+hue+',92%,68%)"/><stop offset="1" stop-color="hsl('+((hue+90)%360)+',88%,58%)"/></linearGradient></defs>'+
    '<rect x="3" y="3" width="58" height="58" rx="16" fill="#090d1a" stroke="url(#g'+hue+')" stroke-width="2"/>'+
    '<circle cx="32" cy="30" r="17" fill="url(#g'+hue+')" opacity=".18"/>'+
    '<path d="M25 22h9c5 0 8 3 8 7 0 3-2 5-5 6 4 1 6 4 6 8 0 5-4 8-10 8h-8V22zm7 12c3 0 5-1 5-4 0-2-2-3-5-3h-2v7h2zm1 13c3 0 5-2 5-5s-2-5-5-5h-3v10h3z" fill="#fff" opacity=".9"/>'+
    '<text x="32" y="17" text-anchor="middle" font-family="system-ui,sans-serif" font-size="7" font-weight="800" fill="#fff" opacity=".8">'+letters+'</text></svg>';
}

function card(p){
 const cs3=fileFor(p.name,"cs3"),jar=fileFor(p.name,"jar"),source=sourceFor(p.name);
 const status=artifactState==="loading"?'<span class="availability">Checking…</span>':cs3?'<span class="availability ok">CS3 ready</span>':'<span class="availability fail">Build pending</span>';
 return '<article class="card" data-name="'+p.name.toLowerCase()+'" data-jar="'+Boolean(jar)+'">'+status+
   '<div class="card-head">'+iconFor(p.name)+'<div><h3>'+esc(p.name)+'</h3><span class="tag">CloudStream provider</span></div></div>'+
   '<p class="card-desc">'+esc(p.desc)+'</p><div class="actions">'+
   (cs3?'<a class="cs3" href="'+cs3+'" target="_blank" rel="noopener noreferrer">CS3 ↗</a>':'<span class="disabled">CS3 pending</span>')+
   (jar?'<a href="'+jar+'" target="_blank" rel="noopener noreferrer">JAR ↗</a>':'<span class="disabled">JAR pending</span>')+
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
   artifacts={providers:{}};artifactState="unavailable";
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
