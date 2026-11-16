# Testes — Atualização 03 (app 1.2.0, banco v3)

**Data:** 13/11/2026 · **Host:** Windows 11 Pro, JDK 21, Gradle 8.14.2, AGP 8.13.2
**Dispositivos:** emulador `Medium_Phone_API_35` (Android 15) para os testes instrumentados; Motorola edge 60 fusion (Android 16) somente para a atualização real com migração dos dados existentes (sem rodar a suíte, ver abaixo).

## Comandos

```bash
./gradlew :app:testDebugUnitTest                                   # JVM
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest   # instrumentados só no emulador
./gradlew :app:lintDebug :app:assembleDebug
```

## Resultados

| Suíte | Tipo | Testes | Resultado |
|---|---|---|---|
| TempoTest, EstatisticasTest, TextoNumerosTest, ExportacaoTest, AditivoV2Test | JVM | 71 | APROVADO |
| **Atualizacao03Test** (ViaCEP, debounce/corrida, ligações, filtros, indicadores, ZIP, relatório, DOCX) | JVM | 13 | APROVADO |
| RepositorioTest | Instrumentado | 17 | APROVADO |
| EquipeRamalTest (agora com ligações por imóvel) | Instrumentado | 13 | APROVADO |
| MigracaoTest (v1→v3, v2→v3 real) | Instrumentado | 3 | APROVADO |
| BackupTest | Instrumentado | 4 | APROVADO |
| CompartilhamentoTest | Instrumentado | 3 | APROVADO |
| **EnderecoCompartilhamentoTest** (catálogo, PDF/PNG/DOCX reais, intents) | Instrumentado | 9 | APROVADO |
| FluxoUiTest (13 fluxos Compose) | Instrumentado | 13 | APROVADO (2 execuções seguidas da classe) |
| **Total** | | **84 JVM + 62 instrumentados** | **146 aprovados** |
| Lint | | | 0 erros, 19 avisos (versões mais novas disponíveis etc.) |

### Falhas encontradas e corrigidas nesta rodada

1. Compilação: string com quebras de linha reais na tela de importação (erro de geração do código) — corrigida.
2. JVM: testes antigos esperavam a aba "Registros", "Resumo", esquema 2 e nome `servicos_saneamento_…` — atualizados para "Serviços", "Indicadores por bairro", esquema 3 e `servicos_saneamento_leme_…` (mudanças intencionais).
3. Instrumentado: `pngDecodificavelSemCortesEOcultaNomes` — 120 ligações ainda cabiam em uma imagem; o teste passou a usar 300 (gera várias imagens, todas decodificáveis e sem perder nenhuma ligação).
4. Instrumentado: `variasLigacoesCadaImovel…` salvava sem equipe sem a permissão explícita (erro do teste).
5. UI: rotação dentro de `createAndroidComposeRule` recria a Activity sem conteúdo (limitação do teste); substituída por sair/voltar ao Histórico (filtros mantidos).
6. UI intermitente: toques enquanto o teclado desloca a tela (`eqp003…`, `tem005…`) — helper `abrirAte` repete o toque até o alvo aparecer; espera do texto "Pendentes" aumentada para 15 s. A classe FluxoUiTest passou 13/13 em duas execuções seguidas depois disso.

A suíte instrumentada **não** foi executada no Motorola nesta rodada: o `connectedAndroidTest` desinstala o app ao final e o aparelho tinha dados em uso (1 serviço e 4 funcionários). Antes de qualquer ação, o banco do celular foi copiado para o PC (integridade "ok").

## Cobertura dos requisitos (seção 8)

| Requisito | Evidência |
|---|---|
| "Histórico" no menu, título e testes | `FluxoUiTest.historicoFiltraPorBairroEDetalhe` toca "Histórico" |
| Lista, busca bairro/rua/número/tipo, filtro de bairro (multi), combinação AND, vazio, limpar | `historicoFiltraPorBairroEDetalhe` (busca sem acento, filtro via painel, chips, limpar), `Atualizacao03Test.filtros combinados…` (número "12A" como texto) |
| Detalhe com "Não registrado", equipe, cada ramal | UI + `Atualizacao03Test.relatorio…` |
| PDF válido, aberto por leitor real, todas as seções; longo pagina sem perder | `EnderecoCompartilhamentoTest.pdfValido…` (assinatura %PDF, `PdfRenderer` renderiza página), `pdfLongo…` (60 ligações, ≥ 2 páginas, todas presentes) |
| PNG decodificável, sem cortes, várias imagens quando longo | `pngDecodificavelSemCortesEOcultaNomes` (`BitmapFactory`, 1080 px; 300 ligações em várias imagens) |
| DOCX real | `Atualizacao03Test.DOCX real…` (Apache POI XWPF lê seções e tabela), `docxRealEIntent…` (partes OOXML) |
| Intent: `content://`, MIME, leitura temporária, chooser, múltiplas imagens | `docxRealEIntentDeCompartilhamentoSeguro`; manual: seletor nativo aberto no emulador |
| Compartilhar após salvar usa ID persistido; falha não desfaz | Tela "Serviço salvo" nos testes de UI; `compartilharServicoNaoFinalizadoFalhaSemAlterarRegistro` |
| Ocultar nomes | `pngDecodificavelSemCortesEOcultaNomes`, `relatorio…` |
| Busca local de bairros/ruas (acentos, caixa, parcial), cadastro manual, bairro habilita rua, troca de bairro limpa rua | UI (`escolherEndereco` em todos os fluxos), `Atualizacao03Test`, `EnderecoCompartilhamentoTest.bairroManual…` |
| API: outro município rejeitado, duplicados deduplicados sem perder CEP/trecho | `Atualizacao03Test.ViaCEP - rejeita outro municipio…` (resposta com Clementina, MG, item sem logradouro) |
| Falha de internet, timeout, 429, cache | `ViaCEP - minimo 3 caracteres, URL codificada, cache, 429 e falha de rede`, `busca externa - falha mostra aviso…`; manual no emulador sem rede |
| Debounce, cancelamento, corrida "Rua A" × "Rua B", nenhuma chamada < 3 caracteres | `busca externa - debounce, minimo e resposta atrasada…` |
| Uma, duas, três ligações; 12A, S/N, mesmo número em ruas distintas; metragem inválida; duplicados; Sim→Não com confirmação | `Atualizacao03Test.ligacoes…` (3 testes), `EquipeRamalTest.variasLigacoes…`, `ram002…`, `ram004…`, `FluxoUiTest.ligacoesTrocadasNaInterface` |
| Migração sem números falsos | `MigracaoTest.migrar2Para3PreservaRamalComoLigacaoLegada` + migração real no Motorola |
| Exportação por bairro único/múltiplos/todos, período, tipo, status; IDs = consulta filtrada | Exportação usa `FiltroServicos.aplicar` com o mesmo filtro da prévia; `Atualizacao03Test.filtros…`; UI conta "2 serviço(s) encontrado(s)" |
| Serviços 1 linha/serviço; Ligações N linhas; sem multiplicação por JOIN | `exportacao - Servicos 1 linha…` (ZIP), `ExportacaoTest` |
| CSV UTF-8/`;`/aspas/fórmula; XLSX real | `ExportacaoTest` (inalterado), `Atualizacao03Test` |
| Indicadores por bairro: n de serviços (não ligações), média/mediana, amostra pequena | `indicadores por bairro - n conta servicos…` |
| Sem resultados: não gera arquivo | botões desativados com `qtd = 0` + verificação em `gerar()` (código); não coberto por teste automatizado |

## Manual

| Cenário | Onde | Resultado |
|---|---|---|
| Atualização 1.1.0 → 1.2.0 com dados reais (`adb install -r`) | Motorola | APROVADO — `user_version` 3, integridade ok; 1 serviço, 4 funcionários, 4 participações, 4 eventos preservados; ramal antigo virou 1 ligação legada pendente (número vazio, lado CURTO, 500 cm); logradouro entrou no catálogo |
| Novo serviço offline: bairro cadastrado manualmente, rua sem internet → aviso e cadastro manual | Emulador (sem DNS) | APROVADO — mensagem "Não foi possível atualizar endereços…" e cadastro manual funcionando |
| Ligação 1 (nº 45A, lado longo, 3,5 m) → salvar sem equipe → tela "Serviço salvo" (1: nº 45A) | Emulador | APROVADO |
| Compartilhar PDF → seletor nativo (Quick Share, Drive, Mensagens, Bluetooth) | Emulador | APROVADO — `servico_LEME_20261113_2fef45e6.pdf` |
| PDF aberto por leitor independente no PC | PC | APROVADO — seções, "Não registrado", tabela de ligações; rodapé caiu numa 2ª página (cosmético, nada cortado) |
| Busca de rua com celular **sem internet** | Motorola | APROVADO — aviso "Não foi possível atualizar endereços…", cadastro manual disponível |
| Busca online "Rua Sao" no bairro São manoel, com internet | Motorola | APROVADO — ViaCEP devolveu só ruas de Leme (Rua São Caetano 13615-752 e Rua São Sebastião 13615-750, bairro Taquari); resultados de Clementina descartados; ruas e o bairro Taquari entraram no catálogo local como "ViaCEP". Nenhum serviço criado |
| Ruas repetidas em "Salvas em outros bairros" e "Sugestões online" | Motorola | DEFEITO encontrado e corrigido; reverificado: "Rua Sao" mostra cada rua uma vez |
| Busca online nova "Avenida Joaquim" | Motorola | APROVADO — cada trecho com CEP e bairro próprios (ex.: Av. Joaquim Lopes Aguilla em 8 bairros), sem repetição; relação rua↔bairro só a informada pela fonte |

## NÃO EXECUTADO

- Suíte instrumentada no Motorola (preservação dos dados do usuário).
- Abrir o XLSX/DOCX gerados no aparelho em Excel/LibreOffice reais (validados com Apache POI).
- Salvar via SAF manualmente; salvar várias imagens PNG em pasta (código implementado, não exercitado à mão).
- Interrupção do app durante a geração do PDF; TalkBack, fonte aumentada e paisagem.
- Envio efetivo por WhatsApp/e-mail.

## Riscos remanescentes

- Catálogo começa sem lista oficial; qualidade depende do ViaCEP e de cadastros manuais.
- ViaCEP pode bloquear por uso; limites locais reduzem o risco, mas não o eliminam.

## Rodada de correções (16/11/2026)

Correções: documentação (privacidade com a permissão de internet, manual, fluxos, limitações), filtro **Equipe (composição exata)** no Histórico e na Exportação, nota "Gerado em…" no pé da última página do PDF/PNG (sem página extra), contagem da Exportação mostra "Contando serviços…" enquanto carrega (antes mostrava "0" por um instante), dois avisos de lint (UseKtx) resolvidos.

| Suíte | Resultado |
|---|---|
| JVM (inclui `filtro por composicao exata…`) | 85/85 APROVADO |
| Instrumentados no emulador API 35 (inclui `pdfCurtoCabeEmUmaPaginaComNotaDeRodape`) | 63/63 APROVADO |
| Lint | 0 erros, 19 avisos |
| APK | `app/build/outputs/apk/debug/app-debug.apk`, 14,4 MB |

Observações da rodada: uma execução anterior levou 19 h (máquina suspensa) e outra terminou com "0 testes" (emulador degradado); o emulador foi reiniciado do zero antes da rodada final. Duas falhas intermitentes de espera em testes de UI foram tratadas com esperas maiores; a da Exportação revelou o "0" momentâneo, corrigido no app.

Pendente: instalar este APK no Motorola (não estava conectado ao fim da rodada).
