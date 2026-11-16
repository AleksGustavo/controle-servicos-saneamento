# Atualização 03 — Histórico, compartilhamento individual, endereços de Leme, múltiplas ligações e exportação filtrada

App **1.2.0**, banco **v3**. Leme/SP fixo, offline-first. Nenhum dado existente é apagado.

## 1. Auditoria e tabela de impacto

| Área | Antes (v2 / 1.1.0) | Agora (v3 / 1.2.0) | Dados existentes | Testes |
|---|---|---|---|---|
| Menu/título | "Histórico por bairro", lista agrupada município › bairro | **"Histórico"**: abas Finalizados / Em andamento, busca, painel de Filtros (inclui **Equipe — composição exata**), chips ativos, Limpar filtros, cartão compacto com Compartilhar | rotas e IDs inalterados | `FluxoUiTest.historicoFiltraPorBairroEDetalhe` |
| Detalhe | ramal único | **Compartilhar serviço**, lista de ligações por imóvel, "Não registrado" quando sem marco | — | UI + `EnderecoCompartilhamentoTest` |
| Após salvar | ia para o detalhe | tela **Serviço salvo** com Compartilhar / Ver detalhes / Histórico / Início | — | `FluxoUiTest` (todas as rotas de salvar) |
| Endereço do novo serviço | bairro digitado com sugestões; rua digitada | **Seletor de bairro** (lista + busca local + cadastro manual) → **seletor de rua** habilitado após o bairro (lista do bairro, busca local, ViaCEP ≥ 3 caracteres, cadastro manual) | ruas digitadas viram catálogo "Base local" na migração; texto histórico preservado | `Atualizacao03Test`, `EnderecoCompartilhamentoTest`, UI |
| Ramal trocado | 1 lado + 1 metragem por serviço | **uma ligação por imóvel** (número obrigatório, lado, metros, endereço próprio opcional, identificador, observações); quantidade derivada | ramal único vira 1 ligação **legada pendente de identificação** (número NULL) | `MigracaoTest.migrar2Para3…`, `EquipeRamalTest`, UI |
| Exportar | escopo todos/filtro atual + formato | **tela de filtros** com Bairro em destaque (1 ou vários), período (atalhos ou datas), tipo, participante, material, estado, "Somente concluídos" (ligado); prévia "N serviços encontrados" e período; bloqueio de arquivo vazio; Excel ou CSV (.zip); depois Salvar ou Compartilhar | — | `Atualizacao03Test`, UI |
| Planilhas | Registros, Participantes, Resumo, Resumo_equipes, Metadados | **Serviços**, **Ligações trocadas**, Participantes, **Indicadores por bairro**, Resumo_equipes, Metadados | colunas antigas mantidas na mesma ordem; novas ao final (esquema 3) | `ExportacaoTest`, `Atualizacao03Test` |
| Permissões | nenhuma | **INTERNET** (somente sugestões de logradouro) | — | — |

## 2. Fontes de endereço — investigação (13/11/2026)

| Fonte | Verificação prática | Decisão |
|---|---|---|
| **ViaCEP** `GET https://viacep.com.br/ws/SP/Leme/{termo}/json/` | Testado com curl: termo < 3 caracteres → **HTTP 400**; máximo **50** resultados; busca em "Leme" também retorna **outras cidades** (ex.: "Rua Sao" trouxe Clementina); sem resultado → `[]`; CEP inexistente → `{"erro":"true"}`. Não lista "todas as ruas" nem "todos os bairros". | **Usada** só para sugerir logradouros por termo, com debounce 500 ms, cancelamento (`mapLatest`), cache de 100 consultas, no máximo 12 req/min, pausa de 2 min após HTTP 429, timeout 6 s/8 s, resposta ≤ 512 KB, **filtro estrito localidade = Leme, UF = SP, IBGE = 3526704**. |
| **Correios Busca CEP** (manual oficial) | Exige **Bearer Token** obtido com conta PJ Meu Correios e **contrato comercial** com o serviço 86738; tem `GET /v1/bairros/{uf}/localidades/{localidade}`. | **Não usada**: não há contrato; nenhuma credencial no APK. Poderia fornecer a lista de bairros se houver contrato no futuro. |
| **Prefeitura de Leme / dados abertos** | Busca no site oficial encontrou leis de denominação de vias e documentos, **não** uma lista publicada de bairros/logradouros. Sites "CEP Leme" encontrados são agregadores privados. | Nenhuma lista oficial embutida. **Importador opcional** (Configurações › Endereços › Importar CSV `bairro;logradouro;cep`) para uma lista autorizada que o usuário obtiver, com prévia e deduplicação. |
| **OpenStreetMap / Nominatim** | Política do Nominatim proíbe autocomplete pela API pública. | **Não usada**. |
| **IBGE localidades** | `municipios/3526704` = Leme/SP. | Usado só como código de validação. |

**Não há "lista completa" de bairros ou ruas no app.** O catálogo local cresce com: serviços registrados, resultados do ViaCEP (com bairro informado pela fonte), cadastros manuais (com confirmação) e importação autorizada. A origem fica guardada (`Base local`, `ViaCEP`, `Inserido manualmente`, `Lista importada`) e é exibida discretamente.

## 3. Fluxo de endereço (bairro → rua)

1. Município fixo "Leme · SP".
2. **Bairro**: toque abre lista rolável dos bairros conhecidos + "Pesquisar bairro" (sem acentos/caixa, a cada caractere, sem rede). Não encontrado → "Adicionar bairro" com confirmação (origem manual).
3. **Rua / Avenida / Logradouro**: só após o bairro. Seções "Neste bairro", "Salvas em outros bairros" (exige confirmação) e "Sugestões online (ViaCEP)" com CEP e bairro da fonte. Se a fonte associa a rua a outro bairro, pede confirmação; confirmado, grava vínculo manual (nunca inventa relação). "Usar 'texto' (cadastrar)" para rua manual.
4. Trocar o bairro limpa a rua escolhida.
5. Sem internet / 429 / timeout: aviso "Não foi possível atualizar endereços. Você pode continuar usando os dados salvos ou cadastrar manualmente." — nada bloqueia o serviço.
6. Número/complemento nunca são enviados a APIs.

## 4. Modelo de dados v3 (ver HISTORICO_MIGRACOES.md)

- `bairro` + `origem` (NOT NULL DEFAULT 'LOCAL_SERVICO'), `ativo`, `verificadoEm`.
- `logradouro` (id, tipo, nomeExibicao, nomeNormalizado [abreviações expandidas: "Av." = "Avenida"], cep, origem, origemExternaId, verificadoEm, createdAt, updatedAt, ativo).
- `logradouro_bairro` N:N (uma rua pode atravessar bairros).
- `servico` + `logradouroId`, `cep`, `origemEndereco`. O texto `logradouro` continua sendo a **fotografia histórica** (atualizações de catálogo não mudam serviços antigos).
- `ligacao_trocada` (servicoId FK CASCADE, ordem, numeroTexto, bairroTexto?, logradouroTexto? [só se diferente do principal], complemento, identificadorLigacao, lado, comprimentoCm, observacoes, pendenteIdentificacao, legado). Gatilhos: lado válido, comprimento > 0, número obrigatório salvo legado pendente.
- `servico.ramalAguaTrocado` = "houve troca". As colunas `ladoRamalTrocado`/`comprimentoRamalCm` da v2 ficam **somente** nos registros antigos (preservadas); novos registros as deixam vazias.
- "Ligações existentes no local" (campo antigo `quantidadeLigacoes`) continua separado de "ligações trocadas".

## 5. Ligações trocadas — regras

- Não → nenhum campo; Sim → cartões "Ligação 1, 2, …" e "+ Adicionar outra ligação"; remover com confirmação; Sim→Não com dados pede confirmação.
- Número do imóvel **obrigatório também quando há só uma**; aceita "12A", "S/N". O nº do endereço principal aparece só como **sugestão** ("Usar nº do endereço (100)").
- Metros: vírgula ou ponto, até 2 casas, > 0, teto configurável; guardado em centímetros inteiros.
- Mesmo número + complemento + rua + bairro no mesmo serviço → bloqueado, a menos que cada uma tenha "Identificador da ligação" distinto.
- Antes de salvar: "3 ligações trocadas: nº …". Gravação do serviço e das ligações na mesma transação, com evento de auditoria (antes/depois).

## 6. Compartilhar um serviço

- Pontos de acesso: detalhe ("Compartilhar serviço"), ícone no cartão do Histórico e tela **Serviço salvo** (usa o ID persistido; falha não desfaz nada).
- Formatos: **PDF** A4 (`android.graphics.pdf.PdfDocument`, paginação sem truncar, cabeçalho de tabela repetido), **PNG** 1080 px (uma imagem até 6000 px de altura; acima disso, várias páginas — nada é cortado), **DOCX** real (WordprocessingML com estilos e tabelas). Todos gerados no aparelho.
- "Ocultar nomes dos funcionários" troca nomes por "Participante N (F-xxxxxx)".
- Compartilhar: `FileProvider` (`content://`), MIME correto, `FLAG_GRANT_READ_URI_PERMISSION`, seletor nativo (`ACTION_SEND` ou `ACTION_SEND_MULTIPLE` para várias imagens). Salvar: SAF (`CreateDocument`; várias imagens → escolher pasta).
- Nome: `servico_LEME_AAAAMMDD_<8 caracteres do ID>.pdf|png|docx` (`_pN` para páginas de PNG).
- A nota "Gerado em …" fica no pé da última página (junto do número da página), nunca sozinha numa página extra.
- Layout neutro branco/azul, título "Relatório de serviço — Leme/SP", rodapé "registro pessoal, não é documento oficial". "Tempo até encontrar a rede" e "Duração total" em linhas separadas; ausente → "Não registrado"; desconhecido → "Não informado".

## 7. Exportação em lote e indicadores

- Exportar sempre abre filtros (nada é baixado automaticamente). Os filtros ficam guardados ao voltar. Inclui **Equipe (composição exata)**; sem nomes, as equipes aparecem por pseudônimos.
- Somente os registros que correspondem exatamente aos filtros. Sem resultados → botões desativados e aviso.
- **Excel**: Serviços (1 linha/serviço, inclui bairro em cada linha, `quantidade_ligacoes_trocadas`, `comprimento_total_ligacoes_m`, `duracao_total_minutos`, `tempo_ate_rede_minutos`, `cep`), **Ligações trocadas** (1 linha/ligação, `id_servico`), Participantes, **Indicadores por bairro**, Resumo_equipes, Metadados (filtros, data, versão de esquema 3).
- **CSV (.zip)**: `servicos.csv`, `ligacoes_trocadas.csv`, `participantes.csv`, `indicadores_por_bairro.csv`, `metadados.csv` (UTF-8 com BOM, `;`, proteção contra fórmula em texto; números não são alterados).
- **Indicadores por bairro**: agrupado por bairro (ordem alfabética) e tipo de serviço; `n` = serviços válidos (concluídos ou pendentes de complemento, sem revisão, duração coerente); média e mediana calculadas sobre as durações individuais (nunca média de médias), mínimo, máximo, P75/P90 a partir de n = 10, n e média/mediana até a rede, `servicos_com_troca` e `ligacoes_trocadas` em colunas separadas (ligações nunca entram no `n`), qualidade da amostra. Em andamento e inválidos ficam visíveis no histórico, fora dos indicadores.
- Estratificação adicional (profundidade, material, diâmetro, equipe) não está na planilha de indicadores; os dados por serviço permitem filtrar no Excel. Comparações por equipe seguem na aba Resumo_equipes com o aviso de que são descritivas.

## 8. Limitações conhecidas

- Sem lista oficial completa de bairros/ruas de Leme: o primeiro uso começa vazio (ou com o que veio dos serviços antigos). Uma base municipal autorizada pode ser importada.
- ViaCEP pode estar fora do ar, limitar consultas ou não conhecer ruas novas; resultados são parciais (máx. 50 por termo).
- Reinstalar o app apaga o catálogo e os dados — use Configurações › Backup.
- Dados de ligações de registros antigos (v2) ficam "pendentes de identificação" até alguém editar e informar o número.
- PNG muito longo vira várias imagens; salvar várias imagens pede uma pasta.
