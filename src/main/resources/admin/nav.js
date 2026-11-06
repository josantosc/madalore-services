// Menu lateral da central de administração. Carregar no <head> (sem defer): a sessão expirada
// precisa ser detectada já nas primeiras chamadas de API da página.
(() => {
  const ICONES = {
    admin: '<path d="M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8"/><path d="M3 10a2 2 0 0 1 .709-1.528l7-5.999a2 2 0 0 1 2.582 0l7 5.999A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>',
    curadoria: '<path d="M12 5a3 3 0 1 0-5.997.125 4 4 0 0 0-2.526 5.77 4 4 0 0 0 .556 6.588A4 4 0 1 0 12 18Z"/><path d="M12 5a3 3 0 1 1 5.997.125 4 4 0 0 1 2.526 5.77 4 4 0 0 1-.556 6.588A4 4 0 1 1 12 18Z"/><path d="M12 5v13"/>',
    precos: '<path d="M12.586 2.586A2 2 0 0 0 11.172 2H4a2 2 0 0 0-2 2v7.172a2 2 0 0 0 .586 1.414l8.704 8.704a2.426 2.426 0 0 0 3.42 0l6.58-6.58a2.426 2.426 0 0 0 0-3.42z"/><circle cx="7.5" cy="7.5" r=".5"/>',
    simulador: '<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/><path d="M8 9h8"/><path d="M8 13h5"/>',
    agenda: '<path d="M8 2v4"/><path d="M16 2v4"/><rect width="18" height="18" x="3" y="4" rx="2"/><path d="M3 10h18"/><path d="m9 16 2 2 4-4"/>',
    funil: '<path d="M10 20a1 1 0 0 0 .553.895l2 1A1 1 0 0 0 14 21v-7a2 2 0 0 1 .517-1.341L21.74 4.67A1 1 0 0 0 21 3H3a1 1 0 0 0-.742 1.67l7.225 7.989A2 2 0 0 1 10 14z"/>',
    usuarios: '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>',
    conversas: '<path d="M7.9 20A9 9 0 1 0 4 16.1L2 22Z"/>',
    senha: '<circle cx="7.5" cy="15.5" r="5.5"/><path d="m21 2-9.6 9.6"/><path d="m15.5 7.5 3 3L22 7l-3-3"/>',
    sair: '<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><path d="m16 17 5-5-5-5"/><path d="M21 12H9"/>'
  };
  const PERFIS = { curadoria: "Curadoria", precos: "Financeiro", simulador: "Simulador", agenda: "Recepção", funil: "Funil", usuarios: "Usuários" };

  const voltaParaLogin = () => {
    location.href = "/admin/login?volta=" + encodeURIComponent(location.pathname + location.search);
  };
  const fetchOriginal = window.fetch.bind(window);
  window.fetch = async (...args) => {
    const resposta = await fetchOriginal(...args);
    const url = new URL(typeof args[0] === "string" ? args[0] : args[0].url, location.href);
    if (resposta.status === 401 && url.origin === location.origin) {
      voltaParaLogin();
      return new Promise(() => {});
    }
    return resposta;
  };

  function icone(nome) {
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("viewBox", "0 0 24 24");
    svg.innerHTML = ICONES[nome];
    return svg;
  }

  function el(tag, attrs, ...filhos) {
    const node = document.createElement(tag);
    Object.entries(attrs || {}).forEach(([k, v]) => node[k] = v);
    filhos.flat().forEach((f) => f !== null && f !== undefined && node.append(f));
    return node;
  }

  function item(nome, titulo, href, ativo) {
    return el("a", { className: "adm-item" + (ativo ? " ativo" : ""), href, title: titulo }, icone(nome), el("span", {}, titulo));
  }

  function sair() {
    const form = el("form", { method: "post", action: "/admin/logout" });
    form.style.display = "none";
    document.body.append(form);
    form.submit();
  }

  function montar(eu) {
    const caminho = location.pathname;
    const nav = el("nav", { className: "adm-nav", ariaLabel: "Central de administração" });
    const marca = el("a", { className: "adm-marca", href: "/admin" });
    if (eu.chatwootUrl) marca.append(el("img", { src: eu.chatwootUrl + "/brand-assets/logo_thumbnail.svg", alt: "" }));
    marca.append(el("span", {}, eu.nome || "Central"));
    nav.append(marca, item("admin", "Central", "/admin", caminho === "/admin"));

    const modulos = eu.modulos.filter((m) => m.ativo);
    if (modulos.length) nav.append(el("div", { className: "adm-secao" }, "Ferramentas"));
    modulos.forEach((m) => nav.append(item(m.id, m.titulo, m.caminho, caminho === m.caminho || caminho.startsWith(m.caminho + "/"))));
    if (eu.chatwootUrl) {
      nav.append(el("div", { className: "adm-secao" }, "Atendimento"), item("conversas", "Conversas", eu.chatwootUrl + "/app", false));
    }

    const perfis = eu.administrador ? "Administrador" : eu.modulos.map((m) => PERFIS[m.id]).join(", ");
    const nome = eu.pessoa || eu.usuario || "?";
    const trocarSenha = eu.cadastrado
      ? el("a", { className: "adm-sair", href: "/admin/senha", title: "Trocar senha" }, icone("senha"))
      : null;
    nav.append(
      el("div", { className: "adm-espaco" }),
      el("div", { className: "adm-usuario" },
        el("div", { className: "adm-avatar" }, nome.slice(0, 2)),
        el("div", { className: "adm-quem", title: eu.usuario }, el("span", {}, nome), el("small", {}, perfis)),
        trocarSenha,
        el("button", { className: "adm-sair", type: "button", title: "Sair", onclick: sair }, icone("sair"))));
    return nav;
  }

  async function iniciar() {
    const conteudo = el("div", { className: "adm-conteudo" });
    conteudo.append(...document.body.childNodes);
    document.body.classList.add("adm-layout");
    document.body.append(conteudo);
    try {
      const resposta = await window.fetch("/admin/api/eu");
      if (!resposta.ok) return;
      const eu = await resposta.json();
      document.body.insertBefore(montar(eu), conteudo);
      if (eu.chatwootUrl && !document.querySelector("link[rel=icon]")) {
        document.head.append(el("link", { rel: "icon", href: eu.chatwootUrl + "/favicon-32x32.png" }));
      }
      document.dispatchEvent(new CustomEvent("adm:usuario", { detail: eu }));
    } catch (e) {
      // sem menu, a tela continua funcionando
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", iniciar);
  } else {
    iniciar();
  }
})();
