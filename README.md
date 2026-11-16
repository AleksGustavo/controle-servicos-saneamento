# Controle de Serviços — medição de tempos em saneamento

Aplicativo Android **offline-first** para cronometrar serviços de campo de saneamento em **Leme/SP**
(conserto de vazamento visível, PRP — vazamento não visível, ligação de água, ligação de esgoto
e outros tipos cadastráveis) e obter estatísticas de duração por tipo, bairro e **composição de equipe**.

Versão atual: **1.2.0** (banco v3 — catálogo de endereços de Leme, ligações trocadas por imóvel, compartilhamento PDF/PNG/DOCX, exportação filtrada por bairro). Ver [docs/ATUALIZACAO_03_HISTORICO_ENDERECOS_EXPORTACAO.md](docs/ATUALIZACAO_03_HISTORICO_ENDERECOS_EXPORTACAO.md).

Permissão: somente **INTERNET**, usada apenas para sugestões de logradouro (ViaCEP); tudo o mais funciona offline.

- 100% local (Room/SQLite). Sem login, servidor, internet, analytics ou anúncios.
- Única permissão: INTERNET (sugestões de logradouro via ViaCEP, opcionais).
- Exportação CSV e XLSX (real, OOXML) com salvar-como (SAF) e compartilhamento pelo seletor do Android.

## Fluxo de uso

1. **Início › Novo serviço** — tipo, bairro (autocomplete com os já usados em Leme), rua, número ou S/N, complemento.
   Município fixo **Leme · SP** (sem campo de cidade). **Equipe neste serviço**: buscar funcionários por parte do nome,
   tocar para adicionar, escolher a função *naquele* serviço (Encanador, Maquinista, Operador de basculante, Outra).
   Quem não existe pode ser cadastrado ali mesmo. "Reutilizar participantes do último serviço" é uma ação explícita.
2. **Iniciar escavação** — grava o início no banco no instante do toque e abre o cronômetro.
3. **Marcar rede encontrada** (opcional) — grava o marco sem pausar a contagem.
4. **Finalizar escavação** — abre uma confirmação; o fim gravado é o instante do toque em **Finalizar** no diálogo.
   A tela pode apagar à vontade durante o serviço: o tempo é calculado dos marcos gravados, não de um contador.
5. **Complementos** — equipe (corrigível), rede, diâmetro, material, profundidade, condições, **Ligação (ramal de água)
   trocada? Sim/Não** (se Sim: lado longo/curto e metros utilizados), observações. Para salvar é exigido ao menos um
   participante, ou a confirmação explícita "Salvar sem equipe".
6. **Histórico**, **Estatísticas** (por tipo, equipes, participação, funções), **Funcionários**, **Exportar** e
   **Configurações** (inclui **Backup e restauração**).

## Build

Requisitos: JDK 17+ (testado com JDK 21), Android SDK com plataforma 36.

```bash
./gradlew assembleDebug
```

APK gerado em `app/build/outputs/apk/debug/app-debug.apk`. Instalar num aparelho conectado:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Testes:

```bash
./gradlew testDebugUnitTest
```

```bash
./gradlew connectedDebugAndroidTest
```

```bash
./gradlew lintDebug
```

No Android Studio: *File › Open* na pasta do projeto, aguardar o sync do Gradle e executar a configuração `app`.

> Nota de ambiente (Windows): neste computador o cliente Gradle falhou com
> `Unable to establish loopback connection`. Contorno usado:
> `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\Users\<usuario>\.gradle\udstmp` (pasta existente).

## Release assinado

Nenhuma chave está no repositório (`*.jks`, `*.keystore`, `keystore.properties` estão no `.gitignore`).
Gere a chave localmente (`keytool -genkeypair ...`) e use *Build › Generate Signed App Bundle/APK* no
Android Studio, ou configure `signingConfigs` lendo um `keystore.properties` local fora do controle de versão.

## Documentação

Ver a pasta [docs/](docs/): requisitos, arquitetura, modelo de dados, [histórico de migrações](docs/HISTORICO_MIGRACOES.md),
regras de tempo, [equipes e estatísticas](docs/EQUIPES_E_ESTATISTICAS.md), [backup](docs/BACKUP.md), fluxos de UX,
testes (com resultados reais), [checklist manual](docs/CHECKLIST_MANUAL.md), manual do usuário, privacidade,
exportação, decisões técnicas e limitações.
