// --- REGISTRA GSAP E ScrollTrigger ---
gsap.registerPlugin(ScrollTrigger);

// --- FUNÇÕES DO FLASH ---
function resetElements(slide) {
  const option1 = slide.querySelector(".li-1");
  const option2 = slide.querySelector(".li-2");
  [option1, option2].forEach(el => {
    el.style.background = "white";
    el.style.boxShadow = "0 0 15px rgba(255, 255, 255, 0.8)";
  });
}

function flash(element, duration = 200) {
  element.style.background = "white";
  element.style.boxShadow = "0 0 25px white";
  setTimeout(() => {
    element.style.background = "black";
    element.style.boxShadow = "none";
  }, duration);
}

function sequence(slide) {
  const option1 = slide.querySelector(".li-1");
  const option2 = slide.querySelector(".li-2");

  resetElements(slide);

  setTimeout(() => flash(option1), 500);
  setTimeout(() => flash(option2), 2500);
  setTimeout(() => {
    option1.style.background = "black";
    option2.style.background = "black";
    option1.style.boxShadow = "none";
    option2.style.boxShadow = "none";
  }, 2700);

  const nextCycle = setTimeout(() => sequence(slide), 7700);
  return () => clearTimeout(nextCycle);
}

// --- SCROLL LATERAL + PROGRESS BAR ---
const slides = gsap.utils.toArray(".slide");
const progressBar = document.querySelector(".progress-fill");

const horizontalScroll = gsap.to(slides, {
  xPercent: -100 * (slides.length - 1),
  ease: "none",
  scrollTrigger: {
    trigger: ".scroll-horizontal",
    pin: true,
    scrub: 1,
    // 👇 adiciona uma margem extra (1 tela) para o scroll continuar e mostrar o conteúdo final
    end: () =>
      "+=" + (document.querySelector(".painel").offsetWidth + window.innerHeight),

    // Atualiza os flashes dos slides
    onUpdate: self => {
      const progress = self.progress;
      const index = Math.round(progress * (slides.length - 1));

      // Atualiza a barra de progresso (largura de 0% a 100%)
      if (progressBar) {
        gsap.to(progressBar, {
          width: `${progress * 100}%`,
          ease: "none",
          duration: 0.2
        });
      }

      slides.forEach((slide, i) => {
        if (i === index) {
          if (!slide.dataset.active) {
            slide.dataset.active = "true";
            slide._stopSeq = sequence(slide);
          }
        } else if (slide.dataset.active) {
          slide.dataset.active = "";
          if (slide._stopSeq) slide._stopSeq();
        }
      });
    },
    // Quando o scroll termina, adiciona classe pra liberar o final
    onLeave: () => document.querySelector(".scroll-horizontal").classList.add("is-done"),
    onEnterBack: () => document.querySelector(".scroll-horizontal").classList.remove("is-done")
  }
});
