
const motionToggle = $("#reducedMotion");
if (motionToggle) { motionToggle.checked = !!state.settings.reducedMotion; document.documentElement.classList.toggle("reduced-motion", motionToggle.checked); motionToggle.addEventListener("change", () => { state.settings.reducedMotion = motionToggle.checked; save(STORAGE.settings, state.settings); document.documentElement.classList.toggle("reduced-motion", motionToggle.checked); }); }
