// Substitua este ID pelo seu próprio Client ID do Google Cloud
const GOOGLE_CLIENT_ID = "362138088252-3lmb0avu0a1nbma72c7praj6met86nfk.apps.googleusercontent.com";

window.onload = () => {
  google.accounts.id.initialize({
    client_id: GOOGLE_CLIENT_ID,
    callback: handleGoogleCredentialResponse
  });

  google.accounts.id.renderButton(
    document.getElementById("googleSignInButton"),
    { theme: "outline", size: "large" }
  );
};

function handleGoogleCredentialResponse(response) {
  console.log("Google ID Token:", response.credential);

  fetch("http://localhost:3000/api/auth/google", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ id_token: response.credential })
  })
    .then(res => res.json())
    .then(data => {
      if (data.ok) {
        console.log("✅ Login com Google bem-sucedido:", data.user);
      } else {
        console.error("❌ Erro no login com Google:", data);
      }
    })
    .catch(err => console.error("Erro de rede ou servidor:", err));
}
