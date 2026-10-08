(() => {
  const key = "moviecloud:ui";
  let saved = {};
  try { saved = JSON.parse(localStorage.getItem(key) || "{}"); } catch {}
  const root = document.documentElement;
  const motion = document.querySelector("#reducedMotion"), compact = document.querySelector("#compactMode");
  const persist = () => { try { localStorage.setItem(key, JSON.stringify({ reducedMotion: !!motion?.checked, compactMode: !!compact?.checked, favorites, history })); } catch {} };
  if (motion) { motion.checked = !!saved.reducedMotion; root.classList.toggle("reduced-motion", motion.checked); motion.addEventListener("change", () => { root.classList.toggle("reduced-motion", motion.checked); persist(); }); }
  if (compact) { compact.checked = !!saved.compactMode; root.classList.toggle("compact-mode", compact.checked); compact.addEventListener("change", () => { root.classList.toggle("compact-mode", compact.checked); persist(); }); }
  let favorites = Array.isArray(saved.favorites) ? saved.favorites : [];
  let history = Array.isArray(saved.history) ? saved.history : [];
  const syncLibrary = () => {
    const count = document.querySelector("#favoriteCount"); if (count) count.textContent = String(favorites.length);
    const recent = document.querySelector("#recentList"); if (recent && history.length) recent.innerHTML = history.slice(0,6).map(name => `<button class="recent-item" type="button" data-enhanced-recent="${name}"><span class="icon fallback">MC</span><b>${name}</b><small>Recently used</small></button>`).join("");
  };
  const decorate = () => { document.querySelectorAll("#providerGrid .card").forEach(card => { const name = card.querySelector("h3")?.textContent?.trim(); if (!name || card.querySelector(".enhanced-favorite")) return; const button = document.createElement("button"); button.type="button"; button.className="favorite-btn enhanced-favorite"; button.textContent=favorites.includes(name)?"♥":"♡"; button.setAttribute("aria-label", `Save ${name}`); button.addEventListener("click", event => { event.stopPropagation(); favorites = favorites.includes(name) ? favorites.filter(x=>x!==name) : [name,...favorites]; button.textContent=favorites.includes(name)?"♥":"♡"; button.classList.toggle("active",favorites.includes(name)); persist(); syncLibrary(); }); card.appendChild(button); card.addEventListener("click", event => { if (event.target.closest("button,a")) return; history=[name,...history.filter(x=>x!==name)].slice(0,12); persist(); syncLibrary(); }); }); };
  const observer = new MutationObserver(() => { decorate(); });
  const grid = document.querySelector("#providerGrid"); if (grid) observer.observe(grid, { childList:true });
  document.querySelector("#clearFavorites")?.addEventListener("click", () => { favorites=[]; persist(); decorate(); syncLibrary(); });
  document.querySelector("#clearHistory")?.addEventListener("click", () => { history=[]; persist(); syncLibrary(); });
  document.addEventListener("click", event => { const recent=event.target.closest("[data-enhanced-recent]"); if (recent) { document.querySelector("#search")?.focus(); document.querySelector("#search").value=recent.dataset.enhancedRecent; document.querySelector("#search").dispatchEvent(new Event("input",{bubbles:true})); } });
  syncLibrary(); decorate();
  const menu = document.querySelector("#menuButton"), nav = document.querySelector("#primaryNav"); menu?.addEventListener("click", () => { const open=menu.getAttribute("aria-expanded")==="true"; menu.setAttribute("aria-expanded",String(!open)); nav?.classList.toggle("mobile-open",!open); }); nav?.querySelectorAll("a").forEach(a=>a.addEventListener("click",()=>{menu?.setAttribute("aria-expanded","false");nav.classList.remove("mobile-open");}));
  const pwa=document.querySelector("#installApp"); let installEvent; window.addEventListener("beforeinstallprompt",event=>{event.preventDefault();installEvent=event;if(pwa)pwa.hidden=false;}); pwa?.addEventListener("click",async()=>{if(!installEvent)return;installEvent.prompt();await installEvent.userChoice;installEvent=null;pwa.hidden=true;});
  if ("serviceWorker" in navigator) navigator.serviceWorker.register("/web/sw.js").catch(() => {});
})();
