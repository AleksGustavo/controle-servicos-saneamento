# Limitações conhecidas e backlog

## Limitações

- **Reinício + mudança manual de hora**: sem base monotônica entre boots, o app depende do relógio do sistema; a
  duração fica marcada como "sujeita a ajuste de relógio" (`base_tempo`). Se a hora voltar antes do início, o fim é
  ajustado ao início e o registro vai para revisão.
- **Registros anteriores à v2**: `ramal_agua_trocado` aparece como NAO porque o campo não existia (não significa que
  foi verificado); não têm equipe registrada nem `base_tempo`. Não são completados retroativamente.
- **Restauração substitui tudo** (não mescla) e exige reiniciar o app; o arquivo de backup não é criptografado.
- **Sem backup automático** (Android `allowBackup=false`): apenas o backup manual. Um celular sem backup externo
  recente é ponto único de falha.
- **CSV de participações**: é um arquivo separado (o CSV principal tem uma linha por serviço). O XLSX traz as duas
  estruturas no mesmo arquivo.
- **Correção de horários** digitada com precisão de segundos (campos não alterados preservam os milissegundos).
- **Sem pausas classificadas / tempo líquido** (somente tempo bruto).
- **Filtros técnicos** (material, diâmetro, profundidade, condições) não estão na tela de Estatísticas; o XLSX permite.
- **Período**: atalhos e, desde a 1.2.0, datas inicial/final no Histórico e na Exportação; a tela de Estatísticas usa só os atalhos.
- **CSV no Excel pt-BR**: decimais com ponto exigem importação com localidade (ver EXPORTACAO.md).
- **Tema escuro** e **criptografia do banco** não implementados.
- Bairros digitados com erro viram bairros distintos; não há tela de mesclagem.
- Funcionários não podem ser excluídos fisicamente quando referenciados (apenas inativados) — decisão intencional.

### Atualização 03 (1.2.0)

- **Não há lista oficial completa de bairros/ruas de Leme** no app. O catálogo começa com o que veio dos serviços
  antigos e cresce com ViaCEP (com internet), cadastros manuais e importação de lista autorizada.
- **ViaCEP**: resultados parciais por termo (máx. 50), pode estar indisponível ou limitar consultas; não lista
  "todas as ruas de um bairro". Ruas novas podem não constar.
- O app precisa de **permissão de internet** apenas para essas sugestões; todo o resto funciona offline.
- Ligações migradas da versão anterior ficam **pendentes de identificação** (número da casa vazio) até edição.
- Indicadores por bairro não são estratificados por profundidade, material, diâmetro ou equipe dentro da
  planilha de indicadores (os dados por serviço permitem filtrar no Excel).
- Relatório em PNG muito longo vira várias imagens; salvar várias imagens pede a escolha de uma pasta.
- XLSX/DOCX validados por leitura programática (Apache POI); não foram abertos em Excel/Word reais nos testes.

## Backlog sugerido

1. Mesclagem de bairros e de cadastros de funcionários duplicados, com auditoria.
2. Filtros técnicos e intervalo de datas personalizado na tela de Estatísticas.
3. Pausas/interrupções classificadas e tempo líquido.
4. Backup agendado para pasta escolhida (SAF persistente) e backup cifrado com senha.
5. Bloqueio do app e banco cifrado (SQLCipher), se o modelo de ameaça exigir.
6. Importação/mescla de backups de mais de um aparelho (hoje só substituição).
