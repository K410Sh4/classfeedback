gsap.from(".video",{
  x:-100,
  opacity:0,
  duration:1,
  ease:"power2.out",
  scrollTrigger:{
    trigger:".video",
    start:"top 60%",
     end:"top 40%",
    toggleActions:"play none none reverse",
    markers: true
  }
});
gsap.utils.toArray(".card-part").forEach(el => {
  gsap.from(el, {
    y: -100,
    opacity: 0,
    duration: 2,
    ease: "power2.out",
    scrollTrigger: {
      trigger: el,
      start: "top 50%",
      end: "top 40%",
      toggleActions: "play none none reverse",
      markers: true
    }
  });


});