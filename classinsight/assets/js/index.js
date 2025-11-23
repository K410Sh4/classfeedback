function mostrarSenha() {
  const input = document.getElementById("senha");
  input.type = input.type === "password" ? "text" : "password";
}