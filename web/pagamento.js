// ============================================================================
// Cliente JS da maquininha (Moderninha Smart 2) para a UI web da lojinha.
//
// Embrulha a ponte nativa window.AndroidPagamento (PagBankBridge.kt) em
// Promises. Copie este arquivo para o seu app React (ex.: src/lib/pagamento.js).
//
// Uso no "Concluir venda":
//
//   import { cobrar, aoEventoMaquininha } from "./lib/pagamento";
//
//   aoEventoMaquininha((msg) => setMensagem(msg));   // "APROXIME OU INSIRA O CARTÃO"...
//   const r = await cobrar({ valorCentavos: 1550, metodo: "credito", referencia: venda.id });
//   if (r.aprovado) { salvarVenda({ ...venda, metodo: r.metodo, codigoTransacao: r.codigoTransacao, idTransacao: r.idTransacao }); }
//   else { alert(r.dica); }   // texto pronto para a vendedora; r.categoria diz o motivo
// ============================================================================

const pendentes = {};
const ouvintesEvento = new Set();

// O Android chama estas duas funções globais (ver PagBankBridge.kt).
window.__resultadoPagamento = function (r) {
  const resolver = pendentes[r.callbackId];
  if (resolver) {
    delete pendentes[r.callbackId];
    resolver(r);
  }
};

window.__eventoPagamento = function (evento) {
  ouvintesEvento.forEach((fn) => {
    try {
      fn(evento.mensagem, evento.codigo);
    } catch (e) {
      console.error(e);
    }
  });
};

/** true quando a página está rodando dentro do app da maquininha. */
export function maquininhaDisponivel() {
  return typeof window.AndroidPagamento !== "undefined";
}

/**
 * Recebe as mensagens que a maquininha manda durante o pagamento
 * ("APROXIME, INSIRA OU PASSE O CARTÃO", "SENHA: ****", "AUTORIZANDO"...).
 * Retorna uma função para parar de ouvir (útil no cleanup do useEffect).
 */
export function aoEventoMaquininha(fn) {
  ouvintesEvento.add(fn);
  return () => ouvintesEvento.delete(fn);
}

function novoId() {
  return "pg_" + Date.now() + "_" + Math.random().toString(36).slice(2);
}

function chamar(executar) {
  return new Promise((resolve, reject) => {
    if (!maquininhaDisponivel()) {
      reject(new Error("Maquininha não encontrada: abra a lojinha pelo app instalado na Moderninha Smart."));
      return;
    }
    const callbackId = novoId();
    pendentes[callbackId] = resolve;
    try {
      executar(callbackId);
    } catch (e) {
      delete pendentes[callbackId];
      reject(e);
    }
  });
}

/**
 * Envia a cobrança para a maquininha. A Promise só resolve quando o cliente
 * terminar (aprovado, negado ou cancelado) — não use timeout curto aqui.
 *
 * @param {object} p
 * @param {number} p.valorCentavos   valor total em centavos (R$ 15,50 → 1550)
 * @param {"credito"|"debito"|"pix"|"voucher"} p.metodo
 * @param {number} [p.parcelas=1]    só para crédito
 * @param {"vendedor"|"comprador"} [p.parcelamento="vendedor"]  quem paga os juros do parcelado
 * @param {string} [p.referencia]    id da venda no seu sistema (até 10 letras/números)
 * @param {boolean} [p.imprimirComprovante=true]
 * Valor mínimo R$ 1,00; parcelado a partir de R$ 10,00.
 *
 * @returns {Promise<{aprovado:boolean, categoria:string, dica:string, mensagem:string, codigoErro:string,
 *   metodo:string, codigoTransacao:string, idTransacao:string, nsu:string, autorizacao:string,
 *   bandeira:string, valor:string, data:string, hora:string}>}
 *
 * categoria: "aprovado" | "cancelado" | "recusado" | "recusado_risco" | "senha_incorreta" |
 *   "erro_comunicacao" | "erro_leitura" | "tipo_cartao" | "duplicado" | "tempo_esgotado" |
 *   "ocupado" | "nao_autenticado" | "servico_ausente" | "dados_invalidos" | "outro"
 * dica: frase pronta para mostrar à vendedora.
 */
export function cobrar({ valorCentavos, metodo, parcelas = 1, parcelamento = "vendedor", referencia = "", imprimirComprovante = true }) {
  const valor = Math.round(Number(valorCentavos));
  if (!Number.isFinite(valor) || valor <= 0) {
    return Promise.reject(new Error("Valor inválido para cobrança."));
  }
  return chamar((callbackId) =>
    window.AndroidPagamento.pagar(
      JSON.stringify({ callbackId, valorCentavos: valor, metodo, parcelas, parcelamento, referencia: String(referencia), imprimirComprovante })
    )
  );
}

/** Cancela a cobrança em andamento. A Promise do cobrar() resolve com aprovado=false. */
export function cancelarCobranca() {
  if (maquininhaDisponivel()) window.AndroidPagamento.cancelar();
}

/**
 * Estorna (devolve) uma venda aprovada. Passe os dados que o cobrar() devolveu:
 * codigoTransacao, idTransacao e metodo (o PIX usa um tipo de estorno diferente do cartão).
 */
export function estornar({ codigoTransacao, idTransacao, metodo = "", tipoPagamento = 0, imprimirComprovante = true }) {
  return chamar((callbackId) =>
    window.AndroidPagamento.estornar(
      JSON.stringify({ callbackId, codigoTransacao, idTransacao, metodo, tipoPagamento: tipoPagamento || 0, imprimirComprovante })
    )
  );
}

/**
 * Opções de parcelamento no crédito, com o valor de cada parcela calculado pela PagBank.
 * Sempre inclui 1x. Ex.: [{ parcelas: 1, valorParcelaCentavos: 3000, totalCentavos: 3000 }, { parcelas: 2, ... }]
 * @param {"vendedor"|"comprador"} parcelamento  quem paga os juros
 */
export function calcularParcelas(valorCentavos, parcelamento = "vendedor") {
  return chamar((callbackId) =>
    window.AndroidPagamento.calcularParcelas(Math.round(Number(valorCentavos)), parcelamento, callbackId)
  ).then((r) => (r.ok ? r.opcoes : Promise.reject(new Error(r.dica || r.mensagem))));
}

/** Reimprime o último comprovante. via = "cliente" | "estabelecimento". */
export function reimprimir(via = "cliente") {
  return chamar((callbackId) => window.AndroidPagamento.reimprimir(via, callbackId));
}

/** Última transação aprovada na maquininha (para conferir venda se o app travou). */
export function ultimaTransacao() {
  return chamar((callbackId) => window.AndroidPagamento.ultimaTransacao(callbackId));
}

/** { autenticado, ocupado, serial, modelo } */
export function verificarTerminal() {
  return chamar((callbackId) => window.AndroidPagamento.verificarTerminal(callbackId));
}

/** Ativa a maquininha com o código de ativação da conta PagBank (só se não estiver autenticada). */
export function ativarTerminal(codigoAtivacao) {
  return chamar((callbackId) => window.AndroidPagamento.ativar(String(codigoAtivacao), callbackId));
}
