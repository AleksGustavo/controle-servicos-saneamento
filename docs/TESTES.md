# Testes — evidências reais

**Data:** 13/11/2026 · **Host:** Windows 11 Pro, JDK 21.0.6, Gradle 8.14.2, AGP 8.13.2 · **App:** 1.1.0 (banco v2)

**Dispositivos usados nesta rodada (aditivo v2):**
- Emulador `Medium_Phone_API_35` (Android 15, x86_64).
- **Aparelho físico Motorola edge 60 fusion (Android 16)**, via USB.

## Comandos executados

```bash
./gradlew :app:testDebugUnitTest                 # 71 testes JVM
./gradlew :app:connectedDebugAndroidTest         # 51 testes (emulador) e 51 testes (Motorola)
./gradlew :app:lintDebug :app:assembleDebug      # 0 erros, 19 avisos; APK gerado
```

## Resumo

| Suíte | Tipo | Testes | Emulador API 35 | Motorola Android 16 |
|---|---|---|---|---|
| TempoTest | JVM | 18 | APROVADO | — |
| EstatisticasTest | JVM | 10 | APROVADO | — |
| TextoNumerosTest | JVM | 11 | APROVADO | — |
| ExportacaoTest | JVM | 12 | APROVADO | — |
| AditivoV2Test (equipes, ramal, relógio, exportação v2) | JVM | 20 | APROVADO | — |
| RepositorioTest (Room) | Instrumentado | 17 | APROVADO | APROVADO |
| EquipeRamalTest (funcionários, participações, ramal, base de tempo) | Instrumentado | 12 | APROVADO | APROVADO |
| MigracaoTest (v1 → v2 real) | Instrumentado | 2 | APROVADO | APROVADO |
| BackupTest | Instrumentado | 4 | APROVADO | APROVADO |
| CompartilhamentoTest | Instrumentado | 3 | APROVADO | APROVADO |
| FluxoUiTest (Compose) | Instrumentado | 13 | APROVADO | APROVADO |
| **Total** | | **71 JVM + 51 instrumentados** | **122/122** | **51/51** |

### Falhas encontradas durante a rodada (todas corrigidas e re-executadas)

1. JVM — `AditivoV2Test.filtro por participante`: busca "c" casava com "Centro" (erro no teste). Corrigido o dado do teste.
2. JVM — `ExportacaoTest`: esperava 3 abas e esquema de exportação 1; mudança intencional para 5 abas e esquema 2.
3. Instrumentado — `MigracaoTest` (2): o setup criava o banco "v1" com os gatilhos da v2 (colunas inexistentes). O setup agora remove esses gatilhos, representando um v1 real.
4. Instrumentado — `telaFuncionariosCadastroHomonimoEInativacao`: corrida no encerramento (banco em memória fechado com a UI ainda coletando). O `@After` agora desmonta a UI antes.
5. Instrumentado — `corrigirParticipantesDuranteServico`: **defeito real** — ao salvar participantes a navegação podia ocorrer fora da thread principal ("setCurrentState must be called on the main thread"). Corrigido em `TelaParticipantesServico` (`Dispatchers.Main`).
6. Motorola — 13 testes de UI falharam com "No compose hierarchies found": a tela do aparelho bloqueou (tempo limite de 60 s). A Activity de teste agora mantém a tela acesa (`FLAG_KEEP_SCREEN_ON`, só no teste). Uma falha intermitente de toque em chip durante a animação do teclado real foi tratada no teste (`clicarAteSelecionar`).

## Rastreabilidade (IDs da especificação)

| ID | Onde | Resultado |
|---|---|---|
| LEM-001 | `RepositorioTest.lem001…`, `FluxoUiTest` (subtítulo "Leme · SP", sem campo de município) | APROVADO |
| LEM-002 | `RepositorioTest.lem002…` | APROVADO |
| EQP-001 | `EquipeRamalTest.eqp001…`, `AditivoV2Test EQP-001`, `FluxoUiTest.telaFuncionarios…` | APROVADO |
| EQP-002 | `EquipeRamalTest.eqp002…`, `FluxoUiTest.telaFuncionarios…` (aviso de homônimo) | APROVADO |
| EQP-003 | `EquipeRamalTest.eqp003…`, `FluxoUiTest.eqp003…` (via pesquisa na UI) | APROVADO |
| EQP-004 | `EquipeRamalTest.eqp004…` (repositório + PK no banco) | APROVADO |
| EQP-005 | `EquipeRamalTest.eqp005…` (inclui FK RESTRICT), `FluxoUiTest.telaFuncionarios…` | APROVADO |
| EQP-006/007/008/009/010 | `AditivoV2Test` | APROVADO |
| RAM-001/002/004 | `EquipeRamalTest.ram00x…` (repositório + gatilhos), `FluxoUiTest.ramalCondicionalNaInterface` | APROVADO |
| RAM-003 | `AditivoV2Test RAM-003`, `EquipeRamalTest.ram002…` (350 cm), UI (prévia "3,5 m" e detalhe) | APROVADO |
| RAM-005 | `AditivoV2Test RAM-005` | APROVADO |
| TEM-001/002 | `AditivoV2Test` (recalcula do marco) + **manual no Motorola** (abaixo) | APROVADO |
| TEM-003 | `RepositorioTest.pendenteDeComplementosSobreviveReabertura…`, `FluxoUiTest.servicoEmAndamentoRecuperadoAoAbrirApp` | APROVADO |
| TEM-004 | `FluxoUiTest.fluxoCompletoComRedeEncontrada`, `RepositorioTest.marcarRedeNaoPausa…` | APROVADO |
| TEM-005 | `FluxoUiTest.tem005FimFixadoNaConfirmacao` (relógio falso: 10 min + 7 s de hesitação no diálogo; formulário não altera) | APROVADO |
| TEM-006 | `RepositorioTest` (10 toques concorrentes em rede, 6 em finalizar) | APROVADO |
| TEM-007 | `AditivoV2Test TEM-007`, `RepositorioTest.relogioAlteradoDuranteMedicao…` | APROVADO |
| TEM-008 | `AditivoV2Test TEM-008`, `EquipeRamalTest.baseTempoRegistradaNaFinalizacao` | APROVADO (simulado) |
| DB-001 | `MigracaoTest.db001…` | APROVADO |
| EXP-001/002 | `AditivoV2Test EXP-001/002` (lidos com Apache POI) | APROVADO |
| EXP-003 | `CompartilhamentoTest` | APROVADO |
| UX-001 | `FluxoUiTest` (13 fluxos) | APROVADO |

## Teste manual no Motorola edge 60 fusion (Android 16), APK 1.1.0

Ver resultados na seção "Manual — aparelho físico" ao final.

## NÃO EXECUTADO

- Reinício real do aparelho durante a medição (TEM-008 só simulado com relógio falso/Room).
- Alteração manual real da hora do sistema (TEM-007 só simulado).
- Modo de economia de bateria ligado durante a tela apagada.
- Restauração de backup pela interface no aparelho (o fluxo de arquivo foi testado por `BackupTest`; a tela e o reinício do app não).
- Envio efetivo por WhatsApp/e-mail; TalkBack, fonte aumentada e telas pequenas.
- Falta de espaço em disco e destino SAF indisponível (tratamento implementado, não provocado).

## Avisos de lint (19, nenhum erro)

10 GradleDependency + 5 NewerVersionAvailable + 1 AndroidGradlePluginVersion + 1 OldTargetApi (versões mais novas existem; mantidas as estáveis verificadas),
1 ObsoleteSdkInt (`mipmap-anydpi-v26`), 1 ModifierParameter (estilo).

## Manual — aparelho físico (Motorola edge 60 fusion, Android 16, APK 1.1.0 instalado via adb)

| Cenário | Resultado observado |
|---|---|
| LEM-001: novo serviço sem campo de cidade | APROVADO — cabeçalho "Leme · SP"; detalhe "Município Leme/SP" |
| Iniciar sem participantes | APROVADO — confirmação "Iniciar sem participantes?" |
| TEM-001: tela apagada (KEYCODE_SLEEP) 18:11:42 → ≥ 18:15:03, aparelho em *Dozing* | APROVADO — às 18:17:37 o cronômetro mostrava 00:06:28 para início 18:11:09 (exato) |
| Consumo com tela apagada | APROVADO — `dumpsys power`: nenhum wake lock do app (só Google Fotos e `dream:doze` do sistema) |
| TEM-004: rede marcada 18:17:58 | APROVADO — "Tempo até encontrar 00:06:49" fixo; total seguiu |
| TEM-002: app em segundo plano (HOME) 18:18:14 → 18:19:48 | APROVADO — 00:08:50 às 18:19:58 (real 8:49 + latência da leitura); nenhum wake lock do app |
| TEM-005: finalizar e preencher o formulário por ~2 min | APROVADO — fim 18:20:45, total 00:09:35 inalterado após salvar às 18:22:30 |
| RAM-002/003: Sim → Lado longo → "3,5" | APROVADO — prévia "Será salvo: 3,5 m"; detalhe "Trocada? Sim / Lado longo / 3,5 m" |
| Salvar sem participantes | APROVADO — diálogo "Nenhum participante" exigiu "Salvar sem equipe"; detalhe "Equipe não registrada" |

Após o teste os dados do app no celular foram apagados (`pm clear`), para não deixar registro fictício. O app 1.1.0 continua instalado, vazio.
