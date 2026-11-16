# Regras de tempo

## Marcos

| Marco | Quando | Obrigatório |
|---|---|---|
| Início | toque em **Iniciar escavação** (máquina começa a abrir) | sim |
| Rede encontrada | toque em **Marcar rede encontrada** | não; não pausa a contagem |
| Fim | toque em **Finalizar serviço** (máquina conclui o fechamento) | sim |

- `tempo_total = fim − início`; `tempo_ate_rede = rede − início`; `tempo_apos_rede = fim − rede`.
- Guardados em milissegundos (`Long`), sem arredondamento. Exibição `HH:mm:ss` por **truncamento** (59,999 s → `00:00:59`); horas podem passar de 24.
- Sem marco de rede → tempos de rede `null` ("—"), nunca zero. O formulário final nunca inventa horário: pedir "Sim" sem marco grava "Não marcada".
- A rede só pode ser marcada com o serviço em andamento, portanto nunca antes do início.

## Finalizar

O instante é capturado **no toque**; o diálogo de confirmação apenas evita toque acidental e grava esse
instante já capturado (não o da confirmação). Após gravar, status = `ENCERRADO_PENDENTE_COMPLEMENTOS`.
O tempo de preencher o formulário não altera a duração (verificado em teste).
Limite: se o app morrer com o diálogo aberto, o fim não foi gravado; o serviço continua em andamento e pode ser finalizado de novo.

## Idempotência e concorrência

- `marcarRede`: `UPDATE ... WHERE redeEncontradaUtcEpochMs IS NULL AND status='EM_ANDAMENTO'`; toques repetidos retornam "já registrado" e não sobrescrevem.
- `finalizar`: `UPDATE ... WHERE fimUtcEpochMs IS NULL AND status='EM_ANDAMENTO'`.
- Botões ficam desabilitados enquanto gravam; o sucesso visual só aparece após o banco confirmar.

## Relógio de parede × relógio monotônico

No início gravamos `System.currentTimeMillis()`, `SystemClock.elapsedRealtime()` e `Settings.Global.BOOT_COUNT`.
Em cada marco (`ResolvedorRelogio`):

1. **Mesmo boot**: instante = `início + (elapsedAgora − elapsedInício)` — imune a alteração manual da hora.
   Se divergir do relógio de parede em mais de 2 min, o serviço é marcado **"requer revisão"** e o valor bruto fica no evento de auditoria (`wallClockBrutoMs`).
2. **Aparelho reiniciado** (boot diferente) ou dado indisponível: usa o relógio de parede (o tempo desligado conta, pois é tempo bruto de serviço).
   Se a hora estiver antes do início (relógio voltou), usa o próprio início (duração 0) e marca revisão — nunca duração negativa.
3. Serviços em revisão ficam **fora das estatísticas** até a correção em *Detalhes › Corrigir horários* (com justificativa; valores anteriores vão para o evento `CORRECAO`).

O cronômetro da tela recalcula a cada ~1 s a partir dos timestamps persistidos; nada é gravado por segundo.
Tela apagada, app em segundo plano, processo morto ou rotação não afetam a duração bruta.

**Não há precisão absoluta garantida** após reinício combinado com alteração manual da hora: nesse caso o app depende do relógio do sistema.

## Fuso horário

Armazenamento em epoch UTC; exibição no fuso atual do aparelho; exportação traz local e ISO 8601 UTC.
Horário de verão não altera durações (teste com America/New_York).


## Versão 2 — tela apagada, segundo plano e fim na confirmação

- **Fonte de verdade = marcos persistidos.** O cronômetro da tela é só apresentação: `rememberAgora` atualiza ~1×/s
  apenas com o ciclo de vida em `STARTED` (`repeatOnLifecycle`). Tela apagada, app em segundo plano ou processo morto
  ⇒ nenhum laço rodando, nenhum wake lock, nenhum `ForegroundService`, nenhuma notificação, nenhuma permissão.
  Ao voltar, a duração é recalculada de uma vez a partir do início gravado.
- `SystemClock.elapsedRealtime()` continua contando com o aparelho em suspensão; por isso a diferença monotônica
  no mesmo boot é exata mesmo após horas de tela apagada.
- **Reinício:** detectado por `Settings.Global.BOOT_COUNT` diferente **ou** `elapsedRealtime` menor que o do início
  (âncoras de boots diferentes nunca são comparadas). Usa-se o relógio de parede; o cronômetro mostra aviso e o fim
  grava `baseTempo = RELOGIO_SISTEMA_APOS_REINICIO` ("duração sujeita a ajuste de relógio", exibido no detalhe e
  exportado em `base_tempo`). Relógio anterior ao início ⇒ duração 0 + revisão, nunca negativa.
- **Fim = instante da confirmação.** "Finalizar escavação" abre o diálogo sem gravar nada; o toque em **Finalizar**
  no diálogo captura o instante (antes de qualquer trabalho assíncrono) e grava fim + duração na mesma transação.
  Assim, matar o app com o diálogo aberto simplesmente não finaliza (o serviço segue em andamento, coerente).
- O `Relogio` injetável (ClockProvider) tem implementação real (`RelogioAndroid`) e falsa nos testes (`RelogioFalso`).
- Exibição de horários no fuso `America/Sao_Paulo` (`LocalOperacional.ZONA`); armazenamento em UTC epoch ms.
