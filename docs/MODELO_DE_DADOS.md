# Modelo de dados (Room, versão de esquema 1)

Banco único `controle_servicos.db`. Bairros **não** ficam em tabelas/bancos separados: a separação é
lógica por `municipioId`/`bairroId`, com índices. Esquema exportado em `app/schemas/.../1.json`.

| Tabela | Campos principais | Restrições |
|---|---|---|
| `municipio` | id (UUID), nome, nomeNormalizado, uf ("" se não informada), createdAt | único (nomeNormalizado, uf) |
| `bairro` | id, municipioId, nomeOriginal, nomeNormalizado, createdAt | único (municipioId, nomeNormalizado); FK município |
| `tipo_servico` | id estável, nome, ativo, ordemExibicao, aplicaRedeEncontrada, createdAt | sementes: VAZAMENTO_VISIVEL, PRP_NAO_VISIVEL, LIGACAO_AGUA, LIGACAO_ESGOTO |
| `material_rede` | id, nome, ativo, ordemExibicao | sementes: PVC, PEAD, Ferro fundido, Aço, Fibrocimento, Outro, Não identificado |
| `condicao_observada` | id, codigo, nome, ativo, ordemExibicao, exclusiva, exigeDescricao | sementes: ESGOTO_ACIMA_REDE, REDE_DANIFICADA_ABERTURA, MAIS_DE_UMA_LIGACAO, OUTRAS (exige descrição), NENHUMA (exclusiva) |
| `servico` | ver abaixo | FKs RESTRICT; índices status, tipo, município, bairro, material, início |
| `servico_condicao` | servicoId, condicaoId, descricao | PK composta; CASCADE no serviço |
| `evento_servico` | id, servicoId, tipoEvento, timestampUtcEpochMs, wallClockBrutoMs, timestampMonotonicMs, bootCount, dadosAnteriores, dadosNovos, motivo, createdAt | trilha de auditoria |

## `servico`

- Identificação/local: `id`, `tipoServicoId`, `municipioId`, `bairroId`, `logradouro`, `numero` ("S/N" aceito), `complemento?`, `equipeIdentificador?`.
- `status`: `EM_ANDAMENTO`, `ENCERRADO_PENDENTE_COMPLEMENTOS`, `CONCLUIDO`, `CANCELADO`. Exclusão lógica via `deletedAt`.
- Tempo: `inicioUtcEpochMs`, `inicioElapsedRealtimeMs?`, `inicioBootCount?`, `inicioZoneId?`, `redeEncontradaUtcEpochMs?`, `fimUtcEpochMs?`.
- Snapshots: `duracaoTotalMs?`, `duracaoAteRedeMs?` — sempre recalculados junto com os timestamps no mesmo `UPDATE`; as estatísticas conferem o snapshot contra `fim - início` e descartam divergências.
- Técnicos: `situacaoRede?`, `diametroMm?`, `materialRedeId?`, `profundidadeMetros?`, `quantidadeLigacoes?`, `observacoes?` — `null` = não informado (nunca 0 implícito).
- Revisão: `requerRevisao`, `motivoRevisao?` (relógio alterado, etc.).
- Controle: `createdAt`, `updatedAt`, `deletedAt?`, `version` (incrementa a cada escrita).

## Gatilhos (criados em `onCreate` e reafirmados em `onOpen`)

- `trg_servico_unico_ativo_insert` / `_update`: aborta se houver outro serviço `EM_ANDAMENTO` não excluído.
- `trg_servico_ordem_tempos`: aborta atualizações com `fim < início`, `rede < início` ou `rede > fim`.

## Migrações

`AppDatabase.MIGRACOES` (vazio na v1). **Nunca** `fallbackToDestructiveMigration`. Ao criar a v2:
adicionar `MIGRATION_1_2`, exportar esquema, criar teste em `MigracaoTest` com `runMigrationsAndValidate`.
O teste atual valida que o esquema v1 exportado abre com o código atual, preservando dados e gatilhos.

## Preferências (DataStore)

`municipio_padrao`, `uf_padrao`, `limiar_amostra` (padrão 5), `formato_exportacao`.


## Versão 2 (app 1.1.0)

Ver também [HISTORICO_MIGRACOES.md](HISTORICO_MIGRACOES.md).

- `servico` + `ramalAguaTrocado` (0/1, NOT NULL DEFAULT 0), `ladoRamalTrocado` (LONGO/CURTO ou NULL),
  `comprimentoRamalCm` (inteiro em **centímetros** — 3,5 m = 350; representação exata, sem ponto flutuante),
  `baseTempo` (relógio usado ao congelar a duração). Gatilhos impõem: Não ⇒ lado e comprimento NULL; Sim ⇒ lado válido
  e comprimento > 0 (equivalente a CHECK, que o SQLite não permite adicionar por ALTER TABLE).
- `funcionario` (id UUID, nomeExibicao, nomeNormalizado [índice], nomeCompleto?, apelidoOuCodigo?, funcaoHabitual?,
  ativo, criadoEm, atualizadoEm).
- `participacao_servico` (servicoId, funcionarioId, funcaoNoServico, funcaoDescricao?, nota?, nomeSnapshot,
  funcaoSnapshot, adicionadoEm, atualizadoEm; PK servicoId+funcionarioId).
- `municipio`/`bairro` inalterados; novos serviços sempre em Leme/SP.
- "Quantidade de ligações" (campo da v1) e "comprimento de ramal substituído" (v2) são campos distintos; nenhum
  sobrescreve o outro.
- Gravações de serviço + participantes + eventos ocorrem na mesma transação (`withTransaction`).
