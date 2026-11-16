# Backup e restauração local

**Configurações › Backup e restauração.**

- **Gerar backup agora**: cria um arquivo `backup_controle_servicos_AAAA-MM-DD_HH-mm-ss.db` no local escolhido
  (pasta, cartão, Drive etc., via seletor do Android). É uma cópia íntegra do banco SQLite: dentro de uma transação
  (nenhuma gravação concorrente) copia o arquivo e o WAL, consolida num arquivo único e roda `PRAGMA integrity_check`.
- **Restaurar de um backup**: escolhe-se o arquivo; o app verifica cabeçalho SQLite, integridade, tabelas do app e
  versão do esquema (backup de versão mais nova é recusado; versão antiga é migrada ao abrir). Mostra quantos serviços
  e funcionários contém e pede confirmação. Antes de substituir, guarda uma **cópia de segurança do estado atual** em
  `files/backups_seguranca/` (3 mais recentes). Não restaura com serviço em andamento. Depois reinicia o app.
- A tela inicial mostra um lembrete discreto quando não há backup há mais de 7 dias.

**Limites honestos:** o backup é um arquivo **não criptografado** com endereços e nomes — guarde-o em local seguro.
O backup automático do Android continua desativado (`allowBackup=false`). Perda/roubo do celular sem um backup
externo recente = perda dos dados. A restauração substitui tudo (não mescla).

Testes: `BackupTest` (gerar + validar, restaurar com cópia de segurança e reabertura, arquivo inválido, versão mais nova).
