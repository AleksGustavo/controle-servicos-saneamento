# Exportação

Tela **Exportar**: escopo (*Todos os dados* ou *Filtros atuais*, com contagem antes de gerar), formato
(*CSV* ou *Excel .xlsx*), **Salvar arquivo** (seletor do Android / SAF `ACTION_CREATE_DOCUMENT`) ou
**Compartilhar** (`ACTION_SEND` + chooser nativo; WhatsApp, e-mail ou qualquer app instalado que aceite o tipo).

- Registros excluídos logicamente não entram (a menos que o filtro atual inclua excluídos). Cancelados entram com `status=CANCELADO`.
- Geração em `Dispatchers.IO`, com barra de progresso e botão de cancelar.
- Nome: `servicos_saneamento_AAAA-MM-DD_HH-mm[_bairro].csv|xlsx` (bairro normalizado só se filtrado).
- MIME: `text/csv` e `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`.
- Compartilhamento: arquivo em `cache/exports/` exposto por `FileProvider` (`content://`), com `FLAG_GRANT_READ_URI_PERMISSION`. Arquivos com mais de 24 h são apagados ao abrir a tela (não antes, para não cortar a leitura do app receptor). O app não sabe se o envio foi concluído e não marca nada como "enviado".
- Erros tratados: cancelamento do seletor, falta de app receptor, falha de gravação, falta de espaço, sem permissão no destino.

## Colunas (esquema v1, ordem estável)

```text
id_servico;tipo_servico;status;municipio;uf;bairro;logradouro;numero;complemento;identificador_equipe;
inicio_local;inicio_utc_iso;rede_encontrada_local;rede_encontrada_utc_iso;fim_local;fim_utc_iso;
duracao_total_segundos;duracao_total_hhmmss;tempo_ate_rede_segundos;tempo_ate_rede_hhmmss;tempo_apos_rede_segundos;
diametro_mm;material_rede;profundidade_m;quantidade_ligacoes;condicoes_observadas;observacoes;criado_em_utc;atualizado_em_utc;
situacao_rede;requer_revisao;motivo_revisao
```

As três últimas foram **acrescentadas ao final** do contrato pedido. Novas colunas só ao final e com nova versão de esquema.

- `*_local`: `yyyy-MM-dd HH:mm:ss` no fuso do aparelho. `*_utc_iso`: ISO 8601 UTC.
- Segundos: inteiros truncados. `profundidade_m`: ponto decimal (`1.25`). Célula vazia = não informado (≠ 0).
- `condicoes_observadas`: itens separados por ` | `; dentro de um item, `\` vira `\\` e `|` vira `\|`.

## CSV

UTF-8 **com BOM**, separador `;`, linhas CRLF, aspas quando há `;`, `"`, quebra de linha ou espaço nas pontas (aspas duplicadas).
Sem linhas de metadados no meio dos dados (só cabeçalho + registros).

**Injeção de fórmula**: em células de texto, se o primeiro caractere não-espaço for `= + - @ | %` ou o texto começar com TAB/CR,
é prefixado `'`. Colunas numéricas não passam por isso. Ex.: `=SUM(A1)` → `'=SUM(A1)`.

**Excel pt-BR**: ao abrir com duplo clique, o Excel brasileiro usa `;` e reconhece UTF-8 pelo BOM. Como os decimais usam ponto,
`1.25` pode ser lido como texto/data. Para números corretos use *Dados › Obter dados › De Texto/CSV*, origem 65001 (UTF-8),
delimitador ponto e vírgula, e em *Transformar › Tipo › Usando localidade* escolha Inglês (EUA) nas colunas decimais —
ou prefira o **.xlsx**, que já traz números tipados.

## XLSX

Gerado sem bibliotecas (OOXML/SpreadsheetML via `ZipOutputStream`): **não é CSV renomeado** (teste confere o ZIP e lê com Apache POI).

- `Registros`: mesmas colunas; cabeçalho em negrito azul, primeira linha congelada, autofiltro; textos como `inlineStr` (nunca avaliados como fórmula); números como células numéricas.
- `Resumo`: por município, bairro e tipo — `n`, média/mediana/mín/máx (s), média em HH:mm:ss, `n_ate_rede`, média até rede e qualidade da amostra. Só registros válidos (mesma regra das Estatísticas).
- `Metadados`: app, versão, versão do esquema, data da exportação (local/UTC), fuso, filtros, quantidade, período, unidades e critérios.
- Sem fórmulas: valores já calculados.


## Esquema de exportação v2 (app 1.1.0)

Colunas acrescentadas **ao final** da aba Registros / CSV (as anteriores não mudaram de posição):
`participantes`, `funcoes_por_participante`, `n_participantes`, `chave_composicao`, `perfil_funcoes`,
`ramal_agua_trocado` (SIM/NAO), `lado_ramal_trocado` (LONGO/CURTO ou vazio), `comprimento_ramal_m`
(número, ex. 3.5, ou vazio), `base_tempo`.

- **Uma linha por serviço** na aba Registros (nunca repetida por pessoa, para não inflar médias em planilhas).
- Nova aba **Participantes** (e formato **CSV de participações**): uma linha por pessoa por serviço
  (`id_servico`, `referencia_funcionario`, `nome_no_servico`, `nome_atual`, `funcao_no_servico`, …, `chave_composicao`).
- Nova aba **Resumo_equipes**: composição exata, bairro + composição e perfil de funções, com n, média, mediana,
  mín/máx, n até a rede, média e mediana até a rede, qualidade da amostra. Sem ranking.
- **Incluir nomes dos funcionários**: desligado por padrão. Sem nomes, cada pessoa vira um pseudônimo estável
  `F-xxxxxx` (6 hex do SHA-256 do id), nomes/notas ficam vazios e a descrição do filtro não cita o participante.
- Campos não aplicáveis saem **em branco**, nunca zero. Itens múltiplos separados por ` | ` com escape de `|` e `\`.
- Metadados: `municipio_operacional = Leme/SP`, `versao_esquema_exportacao = 2`, definições de chave, ramal e base de tempo.
