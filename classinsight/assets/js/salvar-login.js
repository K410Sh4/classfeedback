async function handleGoogleCredentialResponse(response) {
  try {
    const res = await fetch("http://localhost:3000/api/auth/google", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ id_token: response.credential })
    });

    const data = await res.json();
    if(data.ok) {
      // Salva os dados no localStorage
      localStorage.setItem("user", JSON.stringify(data.user));
      localStorage.setItem("token", response.credential);

      console.log("Login bem-sucedido:", data.user);
      // redireciona após login
      window.location.href = "/assets/pages/pageMain.html"; 
    } else {
      console.error("Erro no login com Google:", data.message);
    }
  } catch (err) {
    console.error("Erro no login com Google:", err);
  }
}

// Preenche os dados do usuário no header de forma segura
window.addEventListener("DOMContentLoaded", () => {
    const user = JSON.parse(localStorage.getItem("user"));
    const userFoto = document.getElementById("userFoto");
    const userNome = document.getElementById("userNome");

    if(userFoto && userNome) {
        userFoto.src = user?.foto || "/assets/images/googleico.png";
        userNome.innerText = user?.nome || "Convidado";
    }
});

// Função de logout
function logout() {
    localStorage.removeItem("user");
    localStorage.removeItem("token");
    window.location.href = "/"; // volta para a página de login
}
