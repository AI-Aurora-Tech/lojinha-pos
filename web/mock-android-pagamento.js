// ============================================================================
// SIMULADOR da ponte nativa "AndroidPagamento" para testar a UI no NAVEGADOR
// do computador, sem maquininha.
//
// Se a página estiver rodando dentro do app da maquininha, a ponte real já
// existe e este arquivo não faz nada. Mesmo assim, prefira carregá-lo só em
// desenvolvimento:
//   if (import.meta.env.DEV) { await import("./mock-android-pagamento.js"); }
// ============================================================================
(function () {
  if (typeof window.AndroidPagamento !== "undefined") return; // ponte real presente

  let ultimaAprovada = null;
  let cancelado = false;

  function responder(r) {
    if (typeof window.__resultadoPagamento === "function") window.__resultadoPagamento(r);
  }
  function evento(mensagem, codigo) {
    if (typeof window.__eventoPagamento === "function") window.__eventoPagamento({ codigo: codigo || -2, mensagem });
  }
  const esperar = (ms) => new Promise((r) => setTimeout(r, ms));

  window.AndroidPagamento = {
    pagar: async function (pedidoJson) {
      const p = JSON.parse(pedidoJson);
      console.log("[MOCK] pagar", p);
      cancelado = false;

      evento(p.metodo === "pix" ? "LEIA O QR CODE" : "APROXIME, INSIRA OU PASSE O CARTÃO", 0);
      await esperar(1200);
      if (cancelado) {
        responder({ callbackId: p.callbackId, ok: false, aprovado: false, codigoErro: "-1028", mensagem: "OPERAÇÃO CANCELADA" });
        return;
      }
      if (p.metodo !== "pix") { evento("SENHA: ****", 16); await esperar(700); }
      evento("AUTORIZANDO...", 1);
      await esperar(500);

      const aprovar = window.confirm(
        "[SIMULAÇÃO DA MAQUININHA]\n" +
          "Método: " + p.metodo + (p.parcelas > 1 ? " em " + p.parcelas + "x" : "") + "\n" +
          "Valor: R$ " + (p.valorCentavos / 100).toFixed(2).replace(".", ",") + "\n" +
          "Ref: " + p.referencia + "\n\n" +
          "OK = aprovado | Cancelar = negado"
      );

      const agora = new Date();
      const r = {
        callbackId: p.callbackId,
        ok: aprovar,
        aprovado: aprovar,
        resultado: aprovar ? 0 : -1,
        codigoErro: aprovar ? "" : "M001",
        mensagem: aprovar ? "TRANSAÇÃO APROVADA (simulado)" : "TRANSAÇÃO NEGADA (simulado)",
        codigoTransacao: aprovar ? "MOCK" + Date.now() : "",
        idTransacao: aprovar ? "id_" + Date.now() : "",
        valor: String(p.valorCentavos),
        data: agora.toLocaleDateString("pt-BR"),
        hora: agora.toLocaleTimeString("pt-BR"),
        nsu: aprovar ? String(Date.now()).slice(-6) : "",
        autorizacao: aprovar ? "123456" : "",
        bandeira: aprovar && p.metodo !== "pix" ? "MOCKCARD" : "",
        parcelas: p.parcelas || 1,
        referencia: p.referencia,
        serialTerminal: "MOCK-SERIAL",
      };
      if (aprovar) ultimaAprovada = r;
      evento(aprovar ? "APROVADA" : "NÃO APROVADA", aprovar ? 18 : 19);
      responder(r);
    },

    cancelar: function () {
      console.log("[MOCK] cancelar");
      cancelado = true;
    },

    estornar: async function (dadosJson) {
      const d = JSON.parse(dadosJson);
      await esperar(800);
      const ok = window.confirm("[SIMULAÇÃO] Estornar transação " + d.codigoTransacao + "?");
      responder({ callbackId: d.callbackId, ok, aprovado: ok, mensagem: ok ? "ESTORNO APROVADO (simulado)" : "ESTORNO NEGADO (simulado)", codigoTransacao: d.codigoTransacao, idTransacao: d.idTransacao });
    },

    reimprimir: async function (via, callbackId) {
      await esperar(300);
      console.log("[MOCK] reimprimir via " + via);
      responder({ callbackId, ok: true, mensagem: "Via do " + via + " reimpressa (simulado)" });
    },

    ultimaTransacao: async function (callbackId) {
      await esperar(300);
      responder(Object.assign({}, ultimaAprovada || { ok: false, mensagem: "Nenhuma transação" }, { callbackId }));
    },

    verificarTerminal: async function (callbackId) {
      await esperar(300);
      responder({ callbackId, ok: true, autenticado: true, ocupado: false, serial: "MOCK-SERIAL", modelo: "SIMULADOR" });
    },

    ativar: async function (codigo, callbackId) {
      await esperar(300);
      responder({ callbackId, ok: true, mensagem: "Ativado (simulado) com código " + codigo });
    },
  };

  console.log("[MOCK] AndroidPagamento carregado — modo navegador.");
})();
