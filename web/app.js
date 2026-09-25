const ROOT="https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/builds/";
const providers=[
{name:"BasPlayFTP",desc:"Bangla movie and series provider.",jar:true,icon:null},
{name:"CinePlexFTP",desc:"Cine Plex streaming provider.",jar:true,icon:"https://cineplexbd.net/favicon.png?v=2"},
{name:"CTGFTP",desc:"Chattogram-focused movie provider.",jar:true,icon:"https://i.postimg.cc/QMxBDF9T/favicon-V2.png"},
{name:"DhakaFTP",desc:"Dhaka FTP movie provider.",jar:false,icon:null},
{name:"DiscoveryFTP",desc:"Discovery FTP content provider.",jar:true,icon:null},
{name:"HiAnime",desc:"Anime-focused CloudStream provider.",jar:true,icon:"https://hianime.at/theme/images/icons-192.png"},
{name:"KhulnaPlex",desc:"Khulna Plex streaming provider.",jar:true,icon:null},
{name:"MojaLoss",desc:"Movie and entertainment provider.",jar:true,icon:"https://www.mojaloss.stream/favicon.png"},
{name:"MovieHaat",desc:"Movie Haat streaming provider.",jar:true,icon:null},
{name:"MovieLinkBD",desc:"MovieLinkBD provider for Bangla content.",jar:true,icon:"https://movielinkbd.tv/favicon.png"},
{name:"OnlineMovies",desc:"Online movie streaming provider.",jar:false,icon:"https://raw.githubusercontent.com/MyselfHumayunShariarHimu/MovieCloud/main/OnlineMovies/icon.png"},
{name:"YouTube",desc:"YouTube content provider.",jar:true,icon:null},
{name:"YouTubeKids",desc:"Bengali India YouTube Kids provider.",jar:true,icon:"https://upload.wikimedia.org/wikipedia/commons/thumb/f/fb/YouTube_Kids_LogoVector.svg/512px-YouTube_Kids_LogoVector.svg.png"},
{name:"Zoryva",desc:"Zoryva CloudStream provider.",jar:true,icon:"https://zoryva.me/icon-512.png"},
{name:"MovieBox",desc:"MovieBox provider packaged for MovieCloud.",jar:false,icon:"https://github.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/raw/refs/heads/master/Logos/MovieBoxProvider/icon.png"}
];
const $=s=>document.querySelector(s);
function artifact(p,ext){return ROOT+p.name+"."+ext}
function escapeHtml(s){return s.replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]))}
function card(p){
 const icon=p.icon?'<img class="icon" loading="lazy" src="'+p.icon+'" alt="" onerror="this.outerHTML=\'<div class="icon fallback">'+p.name.slice(0,2).toUpperCase()+'</div>\'">':'<div class="icon fallback">'+p.name.slice(0,2).toUpperCase()+'</div>';
 return '<article class="card" data-name="'+p.name.toLowerCase()+'" data-jar="'+p.jar+'" data-icon="'+Boolean(p.icon)+'"><span class="availability" data-url="'+artifact(p,"cs3")+'">checking…</span><div class="card-head">'+icon+'<div><h3>'+escapeHtml(p.name)+'</h3><span class="tag">CloudStream provider</span></div></div><p class="card-desc">'+escapeHtml(p.desc)+'</p><div class="actions"><a class="cs3" href="'+artifact(p,"cs3")+'" target="_blank" rel="noreferrer" download>CS3 ↗</a>'+(p.jar?'<a href="'+artifact(p,"jar")+'" target="_blank" rel="noreferrer" download>JAR ↗</a>':'<span class="copy">JAR —</span>')+'<button class="copy" data-copy="'+artifact(p,"cs3")+'">Copy</button></div></article>'
}
function render(){
 const q=$("#search").value.trim().toLowerCase(), f=$("#filter").value;
 const list=providers.filter(p=>(!q||p.name.toLowerCase().includes(q)||p.desc.toLowerCase().includes(q))&&(f==="all"||(f==="jar"&&p.jar)||(f==="icon"&&p.icon)));
 $("#providerGrid").innerHTML=list.map(card).join("");$("#empty").hidden=list.length>0;
 document.querySelectorAll(".copy").forEach(b=>b.onclick=()=>navigator.clipboard?.writeText(b.dataset.copy||"").then(()=>{const t=b.textContent;b.textContent="Copied ✓";setTimeout(()=>b.textContent=t,1000)}));
 checkAvailability();
}
async function checkAvailability(){
 document.querySelectorAll(".availability").forEach(async el=>{
   try{const r=await fetch(el.dataset.url,{method:"HEAD",cache:"no-store"});el.textContent=r.ok?"online":"pending";el.classList.toggle("ok",r.ok);el.classList.toggle("fail",!r.ok)}
   catch{el.textContent="pending"}
 });
}
$("#search").oninput=render;$("#filter").onchange=render;
$("#copyRoot").onclick=()=>navigator.clipboard?.writeText(ROOT).then(()=>{const b=$("#copyRoot");b.textContent="Copied ✓";setTimeout(()=>b.textContent="Copy",1000)});
$("#providerCount").textContent=providers.length;$("#cs3Count").textContent=providers.length;$("#jarCount").textContent=providers.filter(p=>p.jar).length;
render();
