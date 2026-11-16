# Histórico de migrações do banco

Banco: `controle_servicos.db` (Room, `AppDatabase.VERSAO`). Esquemas exportados em `app/schemas/.../{1,2}.json`.
**Nunca** se usa `fallbackToDestructiveMigration()`.

## v1 → v2 (app 1.1.0) — `AppDatabase.MIGRATION_1_2`

Somente acréscimos; nenhum registro existente é alterado ou apagado.

| Alteração | SQL | Valor em registros antigos |
|---|---|---|
| `servico.ramalAguaTrocado` | `INTEGER NOT NULL DEFAULT 0` | 0 (Não) — o campo não existia; documentado como "não registrado como trocado" |
| `servico.ladoRamalTrocado` | `TEXT` (LONGO/CURTO) | NULL |
| `servico.comprimentoRamalCm` | `INTEGER` (centímetros) | NULL |
| `servico.baseTempo` | `TEXT` (MONOTONICO / RELOGIO_SISTEMA_APOS_REINICIO / RELOGIO_SISTEMA) | NULL (não registrada) |
| tabela `funcionario` | PK `id` (UUID), índice `nomeNormalizado` | vazia — **nenhum funcionário é inventado** |
| tabela `participacao_servico` | PK (`servicoId`,`funcionarioId`), FK CASCADE p/ serviço, FK RESTRICT p/ funcionário, índice `funcionarioId` | vazia — serviços antigos ficam "Equipe não registrada" |
| gatilhos `trg_servico_ramal_insert/update` | `RAISE(ABORT,'ramal_inconsistente')` | aplicam-se a gravações futuras |

**Município (Leme/SP):** a migração **não toca** em `municipio` nem em `servico.municipioId`. Registros antigos de
outra cidade continuam como foram gravados, aparecem com aviso no detalhe ("município diferente de Leme/SP") e
continuam legíveis/exportáveis. Como `municipioId` era `NOT NULL` na v1, não há registros sem município a preencher.
Novos serviços usam sempre Leme/SP (criado na primeira vez, ou reutilizado se já existia com esse nome normalizado + UF).

**Preferências:** as chaves `municipio_padrao`/`uf_padrao` da v1 passam a ser ignoradas (município fixo).

**Testes:** `MigracaoTest.db001Migrar1Para2PreservaHistoricoCompleto` cria um banco no esquema v1 exportado com
serviço concluído (rede, condições, eventos, município Araras), pendente de complementos e em andamento; migra com
`MigrationTestHelper.runMigrationsAndValidate` (valida o esquema contra `2.json`) e confere todos os campos, a
exportação e a continuidade do serviço ativo.

## Como criar a próxima versão

1. Alterar entidades; incrementar `VERSAO`; compilar (gera `N.json`).
2. Escrever `MIGRATION_(N-1)_N` copiando o `createSql` do JSON gerado; acrescentar a `MIGRACOES`.
3. Novo teste em `MigracaoTest` partindo do esquema anterior com dados reais.

## v2 → v3 (app 1.2.0) — `AppDatabase.MIGRATION_2_3`

| Alteração | Valor em registros antigos |
|---|---|
| `bairro.origem` NOT NULL DEFAULT 'LOCAL_SERVICO', `bairro.ativo` DEFAULT 1, `bairro.verificadoEm` | bairros existentes = "Base local", ativos |
| `servico.logradouroId`, `servico.cep`, `servico.origemEndereco` (+ índice) | `logradouroId` ligado ao catálogo criado a partir do texto do serviço; texto `logradouro` preservado |
| tabela `logradouro` (+ índices nome normalizado e CEP) | um item por nome normalizado ("Av. Brasil" = "Avenida BRASIL") dos serviços existentes, origem LOCAL_SERVICO |
| tabela `logradouro_bairro` (N:N) | vínculo rua ↔ bairro de cada serviço existente |
| tabela `ligacao_trocada` (+ gatilhos `trg_ligacao_*`) | cada serviço com `ramalAguaTrocado = 1` vira **uma** ligação `legado = 1`, `pendenteIdentificacao = 1`, `numeroTexto = NULL`, mesmo lado/comprimento; colunas antigas do serviço preservadas |
| gatilhos de ramal v2 → `trg_servico_ramal_v3_*` | "Não" continua exigindo colunas legadas vazias |

Testes: `MigracaoTest.migrar2Para3PreservaRamalComoLigacaoLegada` (v2 real) e `db001…` (v1 → v3, `runMigrationsAndValidate` contra `3.json`).
