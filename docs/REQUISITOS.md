# Requisitos — rastreabilidade do MVP (P0)

| # | Requisito P0 | Situação | Onde |
|---|---|---|---|
| 1 | Início com Novo serviço, serviço ativo, acesso a histórico/estatísticas | Implementado | `feature/home` |
| 2 | Catálogo inicial das 4 categorias, local e expansível | Implementado (+ tipos/materiais/condições cadastráveis) | `Sementes`, `feature/settings` |
| 3 | Endereço separado: bairro, rua, número/S/N, complemento, município configurável, UF | Implementado; bairro único por município | `feature/create`, `ServicoRepository` |
| 4 | Tela de cronometragem com total, início, rede e finalizar | Implementado | `feature/timer` |
| 5 | Persistência imediata e recuperação (processo morto, reinício, rotação) | Implementado: estado vem do banco | `ServicoRepository`, `ResolvedorRelogio` |
| 6 | Formulário de finalização (diâmetro, material, profundidade, observações, condições) | Implementado | `feature/completion` |
| 7 | Histórico por município › bairro, filtros, detalhes | Implementado | `feature/history`, `feature/details` |
| 8 | Estatísticas por bairro e tipo, de registros válidos | Implementado | `Estatisticas`, `feature/statistics` |
| 9 | Exportação CSV e XLSX, salvar e compartilhar | Implementado | `core/export`, `feature/export` |
| 10 | Testes e documentação | Ver `TESTES.md` | `src/test`, `src/androidTest`, `docs/` |

## P1 incluído parcialmente

- Identificador de equipe opcional (campo livre) e filtro por equipe no domínio.
- Cadastro customizável de tipos, materiais e condições (adicionar, ativar/desativar).
- Correção auditável de horários com justificativa; cancelamento; exclusão lógica com restauração.

## P1 não implementado

Pausas/interrupções classificadas (tempo líquido), importação de backup, filtros técnicos (material/diâmetro/profundidade) nas estatísticas,
relatório resumido separado (o XLSX já tem a aba Resumo). Ver `LIMITACOES.md`.

## Hipóteses adotadas (configuráveis)

- O marco "rede encontrada" aplica-se aos 4 tipos iniciais; pode ser desligado por tipo em Configurações.
- "Nenhuma das condições listadas" é a condição marcada como `exclusiva`.
- Limiar de amostra pequena = 5 (Configurações). Percentis a partir de n = 10.


## Aditivo v2 (Leme, equipes, ramal, cronômetro)

Implementados: município fixo Leme/SP; cadastro/busca de funcionários; seleção por serviço com função exercida;
composição exata, participação individual, bairro + equipe e perfil de funções; ramal trocado condicional
(lado + metros); cronômetro robusto com tela apagada/segundo plano/processo morto/reinício; exportação v2 com opção
sem nomes; backup/restauração local testados. Rastreabilidade dos IDs de teste em [TESTES.md](TESTES.md).
