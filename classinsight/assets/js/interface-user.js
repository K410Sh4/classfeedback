const user = JSON.parse(localStorage.getItem("user"));
if(user) {
  document.getElementById("nomeUsuario").innerText = user.nome;
  document.getElementById("fotoUsuario").src = user.foto;
} else {
  // se não estiver logado, volta para login
  window.location.href = "/";
}
function logout() {
  localStorage.removeItem("user");
  localStorage.removeItem("token");
  window.location.href = "/"; // volta para página de login
}