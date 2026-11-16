# Checklist manual (aparelho físico)

Testes automáticos não substituem a validação com tela realmente bloqueada. Marcar data/aparelho ao executar.

| ID | Passos | Esperado |
|---|---|---|
| TEM-001 | Iniciar serviço → apagar a tela (botão liga/desliga) por ≥ 3 min → desbloquear | Cronômetro mostra todo o intervalo, sem perda |
| TEM-002 | Iniciar → abrir outro app por alguns minutos → voltar | Continua do valor real, sem zerar |
| TEM-003 | Iniciar → forçar parada do app (Configurações › Apps) → reabrir › Continuar | Serviço restaurado, tempo correto |
| TEM-004 | Marcar rede → apagar tela → voltar | "Tempo até encontrar" fixo; total continua |
| TEM-005 | Finalizar → esperar alguns minutos no formulário → salvar | Duração = a do toque em Finalizar |
| TEM-007 | Mudar a hora do aparelho durante o serviço (mesmo boot) | Duração não salta; registro marcado para revisão se a divergência > 2 min |
| TEM-008 | Reiniciar o aparelho durante o serviço → reabrir | Serviço restaurado; aviso "aparelho reiniciado…"; base de tempo "relógio do sistema após reinício" no detalhe |
| Bateria | Modo economia de bateria ligado durante TEM-001 | Mesmo resultado (não há serviço em segundo plano) |
| RAM | Complementos: Sim → lado + 3,5 → salvar → detalhe | "3,5 m", lado correto |
| EQP | Selecionar 4 pessoas (2+1+1) por busca; corrigir no cronômetro | Funções corretas; evento PARTICIPANTES no detalhe |
| BKP | Gerar backup → restaurar o mesmo arquivo | Contagens iguais; app reinicia com os dados |

Resultados executados: ver [TESTES.md](TESTES.md).
