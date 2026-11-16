# Segurança e privacidade

- Dados apenas no armazenamento interno do app (isolamento padrão do Android).
- **Única permissão (desde a 1.2.0): INTERNET**, usada somente para pedir sugestões de logradouro ao ViaCEP
  (ver seção "Versão 3"). Sem localização, câmera, contatos ou armazenamento amplo. O app não envia registros,
  nomes, números de imóvel ou estatísticas a nenhum servidor; não há analytics nem sincronização.
- Sem analytics, crash reporting, anúncios ou rastreamento. O código não registra endereços em log.
- `allowBackup=false` e regras de extração excluindo banco/preferências: endereços não vão para backup em nuvem nem transferência entre aparelhos.
- Exportar/compartilhar é sempre ação explícita; a tela avisa que o arquivo sai do aparelho. Compartilhamento por `content://` com permissão de leitura temporária; nada de `file://` público.
- Não coletamos nome de morador nem telefone.
- **Não implementado**: criptografia do banco/backup e bloqueio do app por PIN/biometria. O banco não é cifrado; quem tiver o aparelho desbloqueado e acesso root/depuração pode lê-lo. Avaliar SQLCipher e bloqueio se o modelo de ameaça exigir.


## Versão 2 — dados de funcionários

- Nomes de funcionários são dados pessoais: ficam só no aparelho; sem analytics, telemetria, upload, GPS ou servidor.
  Não se coleta CPF, RG, telefone, matrícula, biometria, localização ou avaliações subjetivas.
- Exportação **sem nomes por padrão** (pseudônimos `F-xxxxxx`); incluir nomes exige ligar a opção conscientemente.
- Estatísticas individuais são rotuladas como **participação** (tempo da equipe), sem ranking automático.
- Arquivos temporários de exportação ficam no cache privado e são limpos após 24 h; o compartilhamento só ocorre
  pelo seletor do Android (FileProvider, permissão de leitura temporária).
- Backup é um arquivo não criptografado escolhido pelo usuário; o backup automático do Android segue desativado.
- Logs de produção não incluem nomes nem endereços.

## Versão 3 (app 1.2.0) — internet só para sugestões de endereço

- O que sai do aparelho: apenas o **termo digitado no campo de rua** (≥ 3 caracteres), em
  `https://viacep.com.br/ws/SP/Leme/{termo}/json/`, por HTTPS. Não são enviados número do imóvel, complemento,
  nomes de funcionários, horários ou qualquer registro de serviço.
- Sem internet, com erro ou bloqueio (HTTP 429), o app continua funcionando com os dados salvos e cadastro manual.
- Limites locais: no máximo 12 consultas por minuto, pausa de 2 minutos após 429, cache de consultas, respostas
  de até 512 KB e somente resultados de Leme/SP (código IBGE 3526704) são aceitos.
- Não se usa Nominatim/OSM público nem raspagem de sites; nenhuma chave/credencial está no APK.
- Relatórios individuais (PDF/PNG/DOCX) são gerados **no aparelho**; nada é enviado para renderização externa.
  "Ocultar nomes dos funcionários" troca os nomes por "Participante N (F-xxxxxx)". O documento contém o endereço
  do serviço: compartilhe só com quem precisa. O envio só acontece pelo seletor do Android, por ação do usuário.
- Filtro "Equipe (composição exata)" na exportação sem nomes mostra as equipes pelos pseudônimos.
