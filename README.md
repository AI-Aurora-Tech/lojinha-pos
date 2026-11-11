# Lojinha GESCS — app para Moderninha Smart 2

App Android que roda **dentro da Moderninha Smart 2** e recebe as cobranças do
**GESCS** (repo `AI-Aurora-Tech/GESCS`). A vendedora escolhe os itens, o método
de pagamento e as parcelas no GESCS (celular ou PC), clica em **Confirmar Venda**
e a maquininha já pede o cartão (ou mostra o PIX) ao cliente, com o valor
preenchido. Ninguém digita nada na maquininha.

```
 Celular/PC da vendedora              Supabase                      Moderninha Smart 2
┌──────────────────────┐   1. grava   ┌───────────────────────┐    ┌───────────────────────────────┐
│ GESCS (Lojinha ou    │ ───────────▶ │ cobrancas_maquininha  │    │ App "Lojinha GESCS" (este repo)│
│ Cantina)             │  'pendente'  │                       │ 2. │ ┌───────────────────────────┐ │
│ "Confirmar Venda"    │              │                       │◀───┤ │ GESCS /maquininha (WebView)│ │
│                      │ 4. vê o      │                       │pega│ └─────────────┬─────────────┘ │
│ registra a venda ◀───┼──resultado───┤ 'aprovado'/'recusado' │◀───┤ 3. cobra pelo │ AndroidPagamento│
└──────────────────────┘              └───────────────────────┘    │    PagBankBridge ──▶ PlugPag SDK│
                                                                   └───────────────────────────────┘
```

1. O GESCS grava a cobrança (valor, método, parcelas) na tabela `cobrancas_maquininha` do Supabase.
2. A maquininha está com a página `/maquininha` do GESCS aberta neste app. Ela pega a cobrança na hora.
3. A página chama `window.AndroidPagamento.pagar(...)` e o cliente passa o cartão.
4. A maquininha grava o resultado. O GESCS só registra a venda quando o resultado é **aprovado**.

O código do GESCS fica no repo dele: `src/lib/maquininha.ts`, `src/pages/Maquininha.tsx`,
`src/components/CobrancaMaquininha.tsx` e `scripts/maquininha_cobrancas.sql`.

| Arquivo (este repo) | O que faz |
|---|---|
| `app/src/main/java/.../MainActivity.kt` | Abre a página do GESCS (`urlSistema`) numa WebView e expõe a ponte como `window.AndroidPagamento`. |
| `app/src/main/java/.../PagBankBridge.kt` | Fala com o SDK PlugPagServiceWrapper 1.35.0: pagar, cancelar, estornar, parcelas, reimprimir, consultar. |
| `app/src/main/java/.../Maquininha.kt` | Instância única do SDK e a fila que impede duas operações ao mesmo tempo. |
| `gradle.properties` → `urlSistema` | Endereço que a maquininha abre. Padrão: `https://gescs.vercel.app/maquininha`. |
| `app/src/main/assets/sem-conexao.html` | Tela mostrada quando a maquininha está sem internet. |
| `web/teste-maquininha.html` | Tela de teste com todas as operações, sem depender do GESCS (útil na homologação). |
| `web/pagamento.js`, `web/mock-android-pagamento.js` | Cliente JS da ponte e simulador para testar no PC. |

## 1. Colocar para funcionar

1. **Supabase:** rode `scripts/maquininha_cobrancas.sql` (repo GESCS) no SQL Editor, uma vez.
2. **GESCS:** publique na Vercel a versão com a página `/maquininha`.
3. **Maquininha:** instale este app (seção 3), abra "Lojinha GESCS" e entre com um usuário
   que tenha acesso à Lojinha ou à Cantina. O login fica salvo.
4. Na maquininha, toque em **Caixa** no topo e escolha quais vendas ela cobra:
   Lojinha, Cantina ou as duas. Com duas maquininhas, deixe cada uma no seu caixa.
5. No GESCS, faça uma venda com Crédito, Débito ou Pix e clique em **Confirmar Venda**.

Regras de segurança da fila:

- Cada cobrança é pega por uma maquininha só.
- Se nenhuma maquininha pegar a cobrança em até 3 minutos, ela expira e **nunca** é cobrada depois
  (o GESCS já avisa a vendedora aos 2 minutos).
- Se o app fechar no meio de uma cobrança, ao reabrir ele confere a última transação aprovada
  e marca a cobrança como aprovada ou interrompida.
- Sem internet depois do pagamento, o resultado fica guardado na maquininha e é reenviado.

## 2. Ponte JavaScript (`window.AndroidPagamento`)

A página `/maquininha` do GESCS já usa a ponte. Para outros usos (ou a tela de teste), `web/pagamento.js`
embrulha tudo em Promises:

- `cobrar({ valorCentavos, metodo, parcelas, parcelamento, referencia })`: devolve `aprovado`,
  `categoria` (motivo: `cancelado`, `recusado`, `senha_incorreta`, `erro_comunicacao`...), `dica`
  (frase pronta para a vendedora), `codigoTransacao`, `idTransacao` e outros dados.
- `cancelarCobranca()`, `aoEventoMaquininha(fn)` (mensagens como "APROXIME OU INSIRA O CARTÃO").
- `calcularParcelas(valorCentavos, "vendedor" | "comprador")`.
- `estornar({ codigoTransacao, idTransacao, metodo })`: o estorno de PIX é diferente do de cartão.
- `reimprimir("cliente" | "estabelecimento")`, `ultimaTransacao()`, `verificarTerminal()`, `ativarTerminal(codigo)`.

Limites (os mesmos do exemplo oficial): mínimo R$ 1,00; parcelado a partir de R$ 10,00.

A tela de teste também abre no PC (com o simulador): rode `python3 -m http.server -d web 8000`
e acesse `http://localhost:8000/teste-maquininha.html`.

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

4. Abra "Lojinha GESCS". Ele abre o GESCS em `/maquininha`; entre com o usuário do caixa.
   A tela de teste sem o GESCS fica em `https://appassets.androidplatform.net/assets/teste-maquininha.html`
   (link na tela "Sem conexão").
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

No terminal de debug, com o GESCS aberto num celular ou PC, confira:

- [ ] Venda na Lojinha com Crédito → a maquininha mostra o valor e pede o cartão sozinha.
- [ ] Venda na Cantina com a maquininha no caixa "Lojinha" → fica esperando; mudando para "Cantina", ela cobra.
- [ ] "Cancelar cobrança" no GESCS enquanto a maquininha pede o cartão → nada é cobrado.
- [ ] Maquininha desligada → após 2 minutos o GESCS avisa que ela não respondeu; ao ligar, a venda antiga não é cobrada.

Na tela `teste-maquininha.html`, confira também:

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
