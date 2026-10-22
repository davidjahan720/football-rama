package fr.footballrama.app.pairing

/**
 * Pages HTML servies au telephone : francais, responsive, CSS inline, AUCUN script ni ressource externe
 * (CSP default-src 'none'). Toute valeur variable passe par [escapeHtml].
 */
object PairingPage {

    const val HOME_NETWORK_NOTICE =
        "Utilisez cette option uniquement sur votre réseau domestique : la liaison entre ce téléphone et le boîtier n’est pas chiffrée."

    /** Formulaire. [server] et [username] pre-remplis apres une erreur ; le mot de passe ne l'est JAMAIS. */
    fun form(action: String, error: String? = null, server: String = "", username: String = ""): String = page(
        title = "Connexion à Football Rama",
        content = buildString {
            append("<h1>Connexion à Football Rama</h1>")
            append("<p>Saisissez les informations de votre abonnement IPTV. Elles sont envoyées uniquement à votre boîtier.</p>")
            if (error != null) append("<p class=\"err\" role=\"alert\">").append(escapeHtml(error)).append("</p>")
            append("<form method=\"post\" action=\"").append(escapeHtml(action)).append("\" accept-charset=\"UTF-8\" autocomplete=\"off\">")
            field(
                id = "code", label = "Code affiché sur la TV",
                input = "<input id=\"code\" name=\"code\" type=\"text\" inputmode=\"numeric\" pattern=\"[0-9]{4}\" " +
                    "maxlength=\"4\" required autocomplete=\"one-time-code\" class=\"code\">",
            )
            field(
                id = "server", label = "Adresse du serveur (avec le port) ou lien M3U complet",
                input = "<input id=\"server\" name=\"server\" type=\"text\" inputmode=\"url\" maxlength=\"2048\" required " +
                    "autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\" placeholder=\"http://exemple.com:8080\" " +
                    "value=\"" + escapeHtml(server) + "\">",
            )
            field(
                id = "username", label = "Identifiant",
                input = "<input id=\"username\" name=\"username\" type=\"text\" maxlength=\"256\" " +
                    "autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\" value=\"" + escapeHtml(username) + "\">",
            )
            field(
                id = "password", label = "Mot de passe",
                input = "<input id=\"password\" name=\"password\" type=\"password\" maxlength=\"256\" autocomplete=\"off\">",
            )
            append("<p class=\"hint\">Si vous collez un lien M3U complet, l’identifiant et le mot de passe peuvent rester vides.</p>")
            append("<button type=\"submit\">Envoyer au boîtier</button>")
            append("</form>")
            append("<p class=\"note\">").append(HOME_NETWORK_NOTICE).append("</p>")
        },
    )

    fun success(): String = message(
        "Connexion réussie",
        "Vos identifiants ont été vérifiés et enregistrés sur le boîtier. Vous pouvez fermer cette page.",
        ok = true,
    )

    fun message(title: String, text: String, ok: Boolean = false): String = page(
        title = title,
        content = "<h1>" + escapeHtml(title) + "</h1><p class=\"" + (if (ok) "ok" else "err") + "\" role=\"status\">" +
            escapeHtml(text) + "</p><p class=\"note\">" + HOME_NETWORK_NOTICE + "</p>",
    )

    private fun StringBuilder.field(id: String, label: String, input: String) {
        append("<label for=\"").append(id).append("\">").append(escapeHtml(label)).append("</label>").append(input)
    }

    private fun page(title: String, content: String): String =
        "<!doctype html><html lang=\"fr\"><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<meta name=\"referrer\" content=\"same-origin\"><meta name=\"robots\" content=\"noindex, nofollow\">" +
            "<title>" + escapeHtml(title) + "</title><style>" + CSS + "</style></head><body><main>" +
            content + "</main></body></html>"

    private const val CSS =
        ":root{color-scheme:light dark;--bg:#f4f6f8;--fg:#14191f;--muted:#4a5560;--card:#fff;--line:#8a96a3;--accent:#b37a00}" +
            "@media (prefers-color-scheme:dark){:root{--bg:#101418;--fg:#eceff2;--muted:#aeb8c2;--card:#1a2027;--line:#5b6874;--accent:#ffc233}}" +
            "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);" +
            "font:16px/1.5 system-ui,-apple-system,'Segoe UI',Roboto,sans-serif}" +
            "main{max-width:30rem;margin:0 auto;padding:24px 16px}" +
            "h1{font-size:1.4rem;margin:0 0 8px}p{margin:0 0 16px;color:var(--muted)}" +
            "form{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:16px}" +
            "label{display:block;font-weight:600;margin:12px 0 4px;color:var(--fg)}label:first-child{margin-top:0}" +
            "input{width:100%;font-size:16px;padding:12px;border:1px solid var(--line);border-radius:8px;background:var(--bg);color:var(--fg)}" +
            "input:focus{outline:3px solid var(--accent);outline-offset:1px}" +
            ".code{font-size:1.6rem;letter-spacing:.5em;text-align:center;max-width:12rem}" +
            "button{width:100%;margin-top:8px;font-size:1.05rem;font-weight:700;padding:14px;border:0;border-radius:8px;" +
            "background:var(--accent);color:#101418;cursor:pointer}button:focus{outline:3px solid var(--fg);outline-offset:2px}" +
            ".hint{font-size:.9rem;margin:12px 0 0}.note{font-size:.9rem;margin-top:16px}" +
            ".err{color:#fff;background:#a12626;padding:12px;border-radius:8px}" +
            ".ok{color:#fff;background:#1f6b3a;padding:12px;border-radius:8px}"
}
