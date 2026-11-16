# Fluxos de UX

```text
Início ──► Novo serviço ──[Iniciar escavação]──► Cronômetro ──[Finalizar + confirmar]──► Complementos ──[Salvar]──► Detalhes
  │            (se já há serviço ativo: aviso + "Continuar")   │                              │ (sair = pendente)
  │                                                            └─ menu ⋮ › Cancelar serviço   │
  ├─ cartão "Serviço em andamento" › Continuar ──► Cronômetro                                 │
  ├─ "Pendentes de complementos" › Complementar ──► Complementos ◄────────────────────────────┘
  ├─ Histórico por bairro ──► Detalhes (editar complementos, corrigir horários, excluir/restaurar, auditoria)
  ├─ Estatísticas
  ├─ Exportar
  └─ Configurações
```

## Diretrizes aplicadas

- Fundo branco, azul `#1565C0` para ações primárias, azul-claro `#E3F0FD` para destaques; tokens em `core/design/Tema.kt`.
- Alvos de toque ≥ 48 dp; botões de marcação com 72 dp; cronômetro 64 sp com algarismos tabulares (`tnum`).
- Botão de rede vira cartão verde "Rede já registrada ✓" com horário e tempo até encontrar.
- Feedback tátil após gravação confirmada; mensagens de erro em snackbar e junto aos campos.
- Teclado numérico para diâmetro/ligações, decimal para profundidade (aceita vírgula), capitalização de palavras para endereço, ação Próximo/Concluir.
- Rótulos de acessibilidade (`contentDescription`) no cronômetro e ícones; títulos marcados como cabeçalho; região "live" ao registrar a rede.
- Tema claro apenas (escuro não implementado para não degradar legibilidade sob sol).
- Sem splash, carrosséis ou animações pesadas.


## Versão 2

- Tela inicial: "Leme · SP" no topo; itens Histórico, Estatísticas, **Funcionários**, Exportar, Configurações;
  lembrete discreto de backup (> 7 dias).
- Novo serviço: sem campo de município; seção "Equipe neste serviço" com busca em tela cheia (digitar parte do nome,
  tocar para marcar, "Cadastrar novo funcionário" quando não encontrado), lista compacta com chips de função e remover.
  Iniciar sem ninguém pede confirmação ("poderá adicionar depois").
- Cronômetro: "Equipe: N participante(s) — alterar"; menu › Participantes (corrigir) / Cancelar; aviso se o aparelho
  reiniciou; nenhuma instrução para manter a tela ligada.
- Complementos: seção Equipe; "Ligação (ramal de água) trocada?" com dois botões grandes Não/Sim; com Sim aparecem
  Lado longo/Lado curto e "Comprimento utilizado (m)" com prévia "Será salvo: 3,5 m". Sim→Não limpa os campos.
- Estatísticas: abas Por tipo / Equipes / Participação / Funções, filtro de participante.


## Versão 3 (atualização 03)

- Início → **Histórico** → abas Finalizados/Em andamento, busca, Filtros (bottom sheet com bairro em destaque),
  chips só dos filtros ativos, cartão compacto (tipo, data, bairro, rua/nº, duração, estado, compartilhar, detalhes).
- Novo serviço → *Bairro* (tela de busca local; adicionar manual com confirmação) → *Rua* (habilitada só após o
  bairro; ruas do bairro, de outros bairros com confirmação, sugestões ViaCEP com 3+ letras; cadastro manual) →
  número/complemento → equipe → Iniciar. Trocar o bairro limpa a rua.
- Complementos → "Houve troca de ligação/ramal de água?" Não/Sim → cartões "Ligação N" → resumo
  "N ligações trocadas: nº …" → Salvar → **Serviço salvo** (Compartilhar / Ver detalhes / Histórico / Início).
- Compartilhar → seletor de formato (PDF, PNG, DOCX) + "Ocultar nomes" → seletor do Android ou Salvar no dispositivo.
- Exportar → bairro(s) em destaque → "Período, tipo e outros filtros" (inclui Equipe — composição exata) →
  "N serviço(s) encontrado(s)" + período → Excel / CSV (.zip) → Salvar ou Compartilhar.
