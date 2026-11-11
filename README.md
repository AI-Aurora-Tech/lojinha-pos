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
| `app/src/main/java/.../PagBankBridge.kt` | Fala com o SDK PlugPagServiceWrapper 1.35.0: pagar, cancelar, estornar, reimprimir, consultar. |
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
      // guarde codigoTransacao e idTransacao: são necessários para estornar depois
      salvarVenda({ ...venda, codigoTransacao: r.codigoTransacao, idTransacao: r.idTransacao, nsu: r.nsu });
    } else {
      alert("Pagamento não aprovado: " + r.mensagem);
    }
  }
  // mostre `mensagem` numa tela de espera com um botão que chama cancelarCobranca()
}
```

Outras funções: `estornar({ codigoTransacao, idTransacao })`, `reimprimir("cliente" | "estabelecimento")`,
`ultimaTransacao()`, `verificarTerminal()`, `ativarTerminal(codigo)`, `maquininhaDisponivel()`.

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

- **Terminal de desenvolvimento/debug** (fornecido pela PagBank durante a homologação):
  instale com `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
  Abra o app e toque em "Abrir tela de teste da maquininha".
- **Maquininhas de produção** não aceitam instalar APK por fora: o app só chega a elas
  pela loja da PagBank depois de homologado e assinado.

## 4. Roteiro de testes para a homologação

Use `teste-maquininha.html` (ou a sua UI) no terminal de debug e confira:

- [ ] Status mostra o terminal **autenticado** (se não, use `ativarTerminal` com o código da conta).
- [ ] Crédito à vista aprovado, comprovante impresso.
- [ ] Crédito parcelado (2x ou mais).
- [ ] Débito aprovado com senha (a tela mostra `SENHA: ****`).
- [ ] PIX aprovado.
- [ ] Cartão negado / senha errada → UI mostra "não aprovado" com a mensagem da maquininha.
- [ ] Cancelar durante "aproxime ou insira o cartão" → volta para a UI sem cobrar.
- [ ] Estorno de uma venda aprovada.
- [ ] Reimpressão da via do cliente e do estabelecimento.
- [ ] Clicar "Concluir venda" duas vezes seguidas → a segunda é recusada com `OCUPADO`.

## Observações

- O SDK vem do repositório Maven oficial `https://github.com/pagseguro/PlugPagServiceWrapper/raw/master`.
  Ele usa RxJava 2 internamente sem declarar a dependência, por isso o `app/build.gradle` inclui `rxjava` e `rxandroid`.
- `applicationId` (`br.org.gescs.lojinha`) não deve mudar depois de homologado.
- Se o app fechar no meio de uma cobrança, use `ultimaTransacao()` para conferir se ela foi aprovada antes de cobrar de novo.
