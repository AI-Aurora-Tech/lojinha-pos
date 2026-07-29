// ============================================================================
// STUB de teste — simula a ponte nativa "AndroidPagamento" no NAVEGADOR.
// Inclua este arquivo APENAS em ambiente de desenvolvimento (nunca no build
// que vai para a maquininha, senão ele sobrescreve a ponte real).
//
// Ex. de guarda: if (import.meta.env.DEV) { import('./mock-android-pagamento.js') }
// ============================================================================
(function () {
  if (typeof window.AndroidPagamento !== "undefined") return; // já existe a ponte real

  window.AndroidPagamento = {
    pagar: function (valorCentavos, tipo, referencia, callbackId) {
      console.log("[MOCK] pagar", { valorCentavos, tipo, referencia, callbackId });

      setTimeout(function () {
        const aprovar = window.confirm(
          "[SIMULAÇÃO]\n" +
          "Tipo: " + tipo + "\n" +
          "Valor: R$ " + (valorCentavos / 100).toFixed(2) + "\n" +
          "Ref: " + referencia + "\n\n" +
          "OK = aprovado | Cancelar = negado"
        );

        const resultado = {
          callbackId: callbackId,
          aprovado: aprovar,
          codigoTransacao: aprovar ? "MOCK" + Date.now() : "",
          idTransacao: aprovar ? "id_" + Date.now() : "",
          mensagem: aprovar ? "APROVADO (simulado)" : "NEGADO (simulado)",
          bandeira: aprovar ? "MOCKCARD" : ""
        };

        if (typeof window.__resultadoPagamento === "function") {
          window.__resultadoPagamento(resultado);
        }
      }, 1200);
    }
  };

  console.log("[MOCK] AndroidPagamento carregado — modo navegador.");
})();