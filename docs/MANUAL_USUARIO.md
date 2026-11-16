# Manual do usuário

## Medir um serviço

1. Toque em **Novo serviço**.
2. Escolha o tipo, confira o município (padrão em Configurações), digite o bairro — toque numa sugestão se ele já foi usado —, a rua e o número (ou marque **S/N**).
3. Quando a máquina começar a escavar, toque **Iniciar escavação**.
4. Quando a equipe achar a rede, toque **MARCAR REDE ENCONTRADA**. O cronômetro continua.
5. Quando a máquina terminar de fechar o buraco, toque **FINALIZAR SERVIÇO** e confirme. O horário gravado é o do primeiro toque.
6. Preencha os complementos com calma (não contam no tempo) e toque **Salvar registro**.

Pode sair do app, bloquear a tela ou desligar o celular: o tempo continua a partir do horário gravado.
Para voltar, abra o app e toque **Continuar**. Só é possível ter um serviço em andamento por vez.

Se não deu tempo de preencher os complementos, o registro aparece em **Pendentes de complementos** na tela inicial.

## Erros de marcação

- Iniciou por engano: no cronômetro, menu ⋮ › **Cancelar serviço** (não entra nas estatísticas).
- Horário errado: **Histórico › registro › Corrigir horários**, informe a justificativa. O valor original fica na auditoria.
- Aviso "Hora do aparelho alterada": confira e corrija os horários; até lá o registro fica fora das estatísticas.

## Histórico e estatísticas

- **Histórico por bairro**: agrupado por município e bairro. Toque no nome do bairro para filtrar; use os chips de tipo, período, estado e ordenação; busca por rua/observação.
- **Estatísticas**: por tipo de serviço (nunca misturando tipos, salvo se ligar o agregado). Mostra n, média, mediana, mínimo, máximo, percentis (n ≥ 10) e média até a rede. Amostras pequenas aparecem com aviso.

## Exportar e backup

**Exportar › Todos os dados › Excel › Salvar arquivo** gera uma cópia completa — é o backup manual.
Guarde-a em local seguro (o arquivo contém endereços). **Compartilhar** abre WhatsApp, e-mail etc.

**Atenção:** desinstalar o app ou "Limpar dados" apaga todos os registros. O backup automático do Android está
desativado de propósito (privacidade). Exporte antes de trocar de aparelho ou desinstalar. A importação de backup ainda não existe.


## Novidades da versão 1.1

- **Cidade fixa:** todos os serviços novos são de Leme/SP; basta informar bairro, rua e número.
- **Funcionários:** cadastre em *Funcionários* ou direto ao montar a equipe. Use apelido/código só para diferenciar
  homônimos. Quem saiu da empresa: desative (não apague) — o histórico continua.
- **Equipe do serviço:** em *Novo serviço › Selecionar funcionários*, digite parte do nome e toque para marcar.
  Ajuste a função de cada um naquele serviço. Esqueceu alguém? No cronômetro, toque em "Equipe … — alterar".
- **Tela apagada:** pode bloquear o celular durante o serviço. Ao desbloquear, o tempo estará certo.
- **Finalizar:** toque em *Finalizar escavação* e confirme em *Finalizar* — vale o horário da confirmação.
- **Ramal trocado:** nos complementos, marque *Sim* só se a ligação/ramal de água foi trocada; informe o lado e os
  metros (ex.: 3,5).
- **Estatísticas por equipe:** compare equipes dentro do mesmo tipo de serviço, sempre olhando o *n*.
  Não é ranking: profundidade, solo e acesso mudam muito a duração.
- **Backup:** *Configurações › Backup e restauração › Gerar backup agora*, e guarde o arquivo fora do celular.


## Novidades da versão 1.2

- **Histórico** (antes "Histórico por bairro"): abas *Finalizados* e *Em andamento*, busca por bairro, rua, número
  ou tipo, botão **Filtros** (um ou vários bairros, datas, tipo, participante, equipe, material, estado) e
  *Limpar filtros*. Toque no cartão para ver detalhes; o ícone de compartilhar envia o relatório do serviço.
- **Bairro e rua**: toque em *Bairro* e escolha na lista ou pesquise; se não existir, *Adicionar bairro*. Depois
  toque em *Rua*: aparecem as ruas já usadas no bairro e, com internet, sugestões do ViaCEP (digite 3 letras ou
  mais). Sem internet, use *Usar "…" (cadastrar)*. A lista do app não é a lista oficial completa de Leme.
- **Ligações trocadas**: em *Houve troca de ligação/ramal de água?* marque *Sim* e preencha um cartão por casa
  atendida — número da casa (obrigatório, aceita 12A e S/N), lado, metros e, se for em outra rua, o endereço.
  *Adicionar outra ligação* para cada casa a mais. Se forem duas ligações na mesma casa, preencha o
  *Identificador da ligação* em cada uma.
- **Registros antigos** com ramal trocado aparecem como "número pendente de identificação": abra o serviço,
  *Editar complementos* e informe o número da casa, se souber.
- **Compartilhar um serviço**: depois de salvar aparece a tela *Serviço salvo* com *Compartilhar*; também no
  detalhe e no Histórico. Escolha PDF, imagem (PNG) ou documento editável (DOCX), e se quer *Ocultar nomes*.
- **Exportar**: escolha primeiro o(s) bairro(s) e os filtros; o app mostra quantos serviços entram. Gere Excel
  ou CSV (.zip) e depois *Salvar* ou *Compartilhar*.
- **Endereços** (Configurações): mostra quantos bairros/ruas há no aparelho e permite importar uma lista
  autorizada em CSV (`bairro;logradouro;cep`).
