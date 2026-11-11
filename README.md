# Lojinha GESCS — app para Moderninha Smart 2

App Android que roda **dentro da Moderninha Smart 2** e liga a UI web da lojinha
à maquininha. A vendedora monta a venda na tela, clica em **Concluir venda** e a
própria maquininha já pede o cartão (ou mostra o PIX) ao cliente, com o valor
preenchido. Ninguém digita valor na maquininha.

```
┌──────────────── Moderninha Smart 2 ────────────────┐
│  App "Lojinha GESCS" (este repo)                   │
│  ┌──────────────┐  window.AndroidPagamento  ┌────┐ │
│  │ UI web       │ ────────────────────────▶ │Pag-│ │
│  │ (WebView)    │ ◀──── resultado/eventos ─ │Bank│ │──▶ PlugPagService ──▶ PagBank
│  │ pagamento.js │                           │Brid│ │    (SDK da maquininha)
│  └──────────────┘                           │ge  │ │
│                                             └────┘ │
└────────────────────────────────────────────────────┘
```

| Arquivo | O que faz |
|---|---|
| `app/src/main/java/.../PagBankBridge.kt` | Fala com o SDK PlugPagServiceWrapper 1.35.0: pagar, cancelar, estornar, parcelas, reimprimir, consultar. |
| `app/src/main/java/.../Maquininha.kt` | Instância única do SDK e a fila que impede duas operações ao mesmo tempo. |
| `app/src/main/java/.../MainActivity.kt` | Abre a UI web numa WebView e expõe a ponte como `window.AndroidPagamento`. |
| `web/pagamento.js` | Biblioteca JS (Promises) para usar na sua UI React. **Copie para o seu app web.** |
| `web/mock-android-pagamento.js` | Simulador da maquininha para testar no navegador do PC. |
| `web/teste-maquininha.html` | Tela de teste com todas as operações (útil na homologação). |
| `app/src/main/assets/` | Onde vai o build da sua UI web (hoje tem uma página provisória). |

## 1. Usar no "Concluir venda" da sua UI

Copie `web/pagamento.js` para o projeto web (ex.: `src/lib/pagamento.js`):

```jsx
import { cobrar, cancelarCobranca, aoEventoMaquininha } from "./lib/pagamento";

function ConcluirVenda({ venda }) {
  const [mensagem, setMensagem] = useState("");

  useEffect(() => aoEventoMaquininha(setMensagem), []); // "APROXIME OU INSIRA O CARTÃO", "SENHA: ****"...

  async function concluir() {
    const r = await cobrar({
      valorCentavos: venda.totalCentavos,   // R$ 15,50 → 1550
      metodo: venda.metodo,                 // "credito" | "debito" | "pix" | "voucher"
      parcelas: 1,
      referencia: venda.id,                 // até 10 letras/números
    });
    if (r.aprovado) {
      // guarde metodo, codigoTransacao e idTransacao: são necessários para estornar depois
      salvarVenda({ ...venda, metodo: r.metodo, codigoTransacao: r.codigoTransacao, idTransacao: r.idTransacao, nsu: r.nsu });
    } else {
      alert(r.dica); // ex.: "Senha incorreta. Tente de novo."
    }
  }
  // mostre `mensagem` numa tela de espera com um botão que chama cancelarCobranca()
}
```

Quando não é aprovado, `r.categoria` diz o motivo (`cancelado`, `recusado`, `senha_incorreta`,
`erro_comunicacao`, `tempo_esgotado`, `duplicado`, `ocupado`...) e `r.dica` traz uma frase pronta para a vendedora.
As categorias seguem a lista de códigos de erro do app de exemplo oficial.

Limites (os mesmos do exemplo oficial): mínimo R$ 1,00; parcelado a partir de R$ 10,00.

Outras funções:

- `calcularParcelas(valorCentavos, "vendedor" | "comprador")`: opções com o valor de cada parcela, calculado pela PagBank.
- `estornar({ codigoTransacao, idTransacao, metodo })`: passe o `metodo`, porque o estorno de PIX é diferente do de cartão.
- `reimprimir("cliente" | "estabelecimento")`, `ultimaTransacao()`, `verificarTerminal()`, `ativarTerminal(codigo)`, `maquininhaDisponivel()`.

Para testar no navegador do PC, carregue o simulador só em desenvolvimento:

```js
if (import.meta.env.DEV) await import("./lib/mock-android-pagamento.js");
```

A tela de teste também abre no PC (com o simulador): rode `python3 -m http.server -d web 8000`
e acesse `http://localhost:8000/teste-maquininha.html`.

## 2. Colocar a UI web dentro do app

**Offline (recomendado para caixa):**

1. No `vite.config.js` do app web, use caminhos relativos: `base: "./"`.
2. `npm run build`
3. Copie o conteúdo de `dist/` para `app/src/main/assets/` (substituindo o `index.html` provisório).

A UI é servida em `https://appassets.androidplatform.net/assets/index.html`, então
`<script type="module">`, `fetch` e `localStorage` funcionam normalmente.

**Online:** troque `URL_INICIAL` em `MainActivity.kt` pelo endereço do deploy e
adicione o domínio em `HOSTS_PERMITIDOS`. Sem internet, a lojinha não abre.

## 3. Compilar e instalar

Pré-requisitos: Android Studio (JDK 17). Abra a pasta do repo e espere o Gradle sincronizar, ou:

```bash
./gradlew assembleDebug
```

O APK sai em `app/build/outputs/apk/debug/app-debug.apk`.

- **Maquininhas de produção** não aceitam instalar APK por fora: o app só chega a elas
  pela loja da PagBank depois de homologado e assinado.

### Terminal de desenvolvimento (DEBUG)

A PagBank fornece um terminal de testes (modelos GERTEC GPOS780 ou SUNMI P2, com marca d'água
na tela). As transações são simuladas e não cobram de verdade. Ele chega já ativado.

1. Antes de testar, atualize os aplicativos de serviço pela **Loja de Aplicativos** do terminal.
2. Conecte o terminal ao computador por USB (o terminal DEBUG aceita ADB; confira com `adb devices`).
3. Instale o app:

   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

4. Abra "Lojinha GESCS" e toque em "Abrir tela de teste da maquininha".
5. Para ver os logs da integração:

   ```bash
   adb logcat -s PagBankBridge
   ```

Se o terminal não aparecer ativado ou tiver divergências, abra um chamado na PagBank com o número
de série (aparece no topo da tela de teste).

### App de exemplo oficial (SmartCoffee)

O [SmartCoffee](https://github.com/pagseguro/pagseguro-plugpagservicewrapper-smartcoffeedemo) é o
app de demonstração da PagBank, com a mesma versão do SDK (1.35.0). Vale instalá-lo no terminal
DEBUG para comparar: se algo funciona no SmartCoffee e não na lojinha, o problema está no nosso app.
Esta integração segue as recomendações dele: SDK como instância única, uma operação por vez,
`isServiceBusy`/`isAuthenticated` antes de cobrar, estorno de PIX com `VOID_QRCODE` e o popup
"Imprimir via do cliente?".

## 4. Roteiro de testes para a homologação

Use `teste-maquininha.html` (ou a sua UI) no terminal de debug e confira:

- [ ] Status mostra o terminal **autenticado** (o terminal DEBUG já vem ativado).
- [ ] Crédito à vista aprovado, comprovante impresso e popup "Imprimir via do cliente?".
- [ ] Crédito parcelado (2x ou mais), com juros do vendedor e do comprador; os valores das parcelas aparecem na lista.
- [ ] Valor abaixo de R$ 1,00 é recusado antes de ir para a maquininha.
- [ ] Débito aprovado com senha (a tela mostra `SENHA: ****`).
- [ ] PIX aprovado.
- [ ] Cartão negado / senha errada → UI mostra "não aprovado" com a mensagem da maquininha.
- [ ] Cancelar durante "aproxime ou insira o cartão" → volta para a UI sem cobrar.
- [ ] Estorno de uma venda no cartão **e** de uma venda no PIX.
- [ ] Reimpressão da via do cliente e do estabelecimento.
- [ ] Clicar "Concluir venda" duas vezes seguidas → a segunda é recusada com `OCUPADO`.

## Observações

- O SDK vem do repositório Maven oficial `https://github.com/pagseguro/PlugPagServiceWrapper/raw/master`.
- `applicationId` (`br.org.gescs.lojinha`) não deve mudar depois de homologado.
- Se o app fechar no meio de uma cobrança, use `ultimaTransacao()` para conferir se ela foi aprovada antes de cobrar de novo.
