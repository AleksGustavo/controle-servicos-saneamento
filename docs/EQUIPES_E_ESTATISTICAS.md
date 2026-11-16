# Funcionários, equipes e estatísticas

## Conceitos

- **Funcionário**: cadastro local (nome de exibição obrigatório; nome completo, apelido/código e função habitual
  opcionais; ativo/inativo). Identidade = UUID gerado pelo app, nunca o nome. Não se coleta CPF, RG, telefone ou matrícula.
- **Participação**: a presença de um funcionário num serviço, com a **função exercida naquele serviço**
  (Encanador, Maquinista, Operador de basculante, Outra + texto opcional) e uma fotografia do nome/função no momento.
  A mesma pessoa não pode aparecer duas vezes no mesmo serviço (PK no banco + validação).
- **Equipe (composição exata)**: o conjunto dos IDs dos participantes de um serviço. Não existe tabela de equipes fixas.

## Chave de composição

`chave = IDs distintos ordenados, unidos por "|"`; exportada como `chave_composicao = SHA-256(chave)` (12 hex).
Não usa nomes (renomear não quebra a série) nem funções (função é atributo da participação).
A/B/C/D em qualquer ordem → mesma chave; A/E/C/D → outra chave. A chave é **derivada** na leitura (não persistida),
o que evita divergência após correções de participantes.

## Visões em Estatísticas

| Aba | Agrupa por | Contagem | Observação exibida |
|---|---|---|---|
| Por tipo | tipo (+ bairro) | cada serviço 1× | como na v1 |
| Equipes | tipo + composição exata; e bairro + tipo + composição | cada serviço 1× (4 pessoas ≠ 4 serviços) | sem ranking; ordem alfabética |
| Participação | funcionário + tipo | o serviço entra para cada participante | "tempo da equipe, não produtividade individual" |
| Funções | tipo + perfil (ex.: 2 Encanador + 1 Maquinista + 1 Op. basculante) | cada serviço 1× | métrica complementar, não identifica pessoas |

Cada cartão mostra `n` (serviços válidos), período, média e mediana do tempo total, mínimo/máximo e, só com marcos
válidos, média e mediana até a rede com o seu próprio `n`. `n = 0` → "Sem dados"; `n = 1` → "Amostra insuficiente";
`n` abaixo do limiar configurável → "Amostra pequena". Cálculos em `Long` (ms); mediana usual (média dos dois
centrais quando `n` é par), sem overflow. Serviços sem equipe registrada não entram nas abas de equipe, mas continuam
em "Por tipo" (e a quantidade é informada).

## Correções e auditoria

A composição pode ser ajustada no cronômetro (menu › Participantes), nos complementos e no detalhe. Cada alteração
real grava um evento `PARTICIPANTES` com antes/depois (id=nome/função) e motivo opcional; o cronômetro não é afetado.
Renomear um funcionário muda o nome exibido em todo o histórico, mas o `nomeSnapshot` gravado na participação é
preservado e mostrado ("registrado como …"). Inativar remove a pessoa da seleção, sem apagar participações antigas
(exclusão física bloqueada por FK RESTRICT).
