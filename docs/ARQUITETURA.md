# Arquitetura

Módulo Gradle único (`:app`), Kotlin + Jetpack Compose (Material 3), MVVM, fluxo unidirecional.

```text
app/src/main/java/br/controleservicos/app/
  ControleApp.kt            Application (container) + MainActivity
  core/
    AppContainer.kt         DI manual, DataStore (preferências), RelogioAndroid
    database/               Room: entidades, DAOs, AppDatabase, sementes e gatilhos
    model/                  Enums, constantes de negócio, LinhaServico (JOIN)
    domain/                 Regras puras: Tempo, ResolvedorRelogio, Estatisticas, Filtro, Texto/Numeros/Condicoes
    repository/             ServicoRepository: toda escrita transacional e auditoria
    export/                 Contrato de colunas, CSV, XLSX, gerador, FileProvider/SAF/chooser
    design/                 Tema, tokens, componentes
    navigation/             AppNavHost (Navigation Compose)
  feature/
    home/ create/ timer/ completion/ details/ history/ statistics/ export/ settings/ comum/
app/src/test/               Testes JVM (domínio, exportação com leitura via Apache POI)
app/src/androidTest/        Room, migração, FileProvider, fluxos Compose
app/schemas/                Esquemas Room exportados (v1)
```

## Princípios

- **Fonte única de verdade: Room.** Telas observam `Flow`s do banco; o cronômetro visível é apenas apresentação calculada dos timestamps persistidos.
- **Domínio sem Android** (`core/domain`, `core/export` exceto `ArquivosAndroid`) → testável na JVM.
- **Escritas transacionais** (`db.withTransaction`) com evento de auditoria na mesma transação.
- **Defesa em profundidade**: regras no repositório + gatilhos SQLite (serviço ativo único, ordem dos tempos) + `UPDATE ... WHERE` idempotentes.
- **DI manual** (`AppContainer`) em vez de Hilt: poucas dependências, testes trocam banco/relógio/preferências facilmente.
- **Filtros compartilhados** (`AppContainer.filtro`) entre Histórico, Estatísticas e Exportar ("Filtros atuais").

## Por que não multi-módulo / Clean Architecture completa

O MVP tem um banco, um repositório e nove telas. Camadas extras (use cases por ação, módulos Gradle)
aumentariam tempo de build e boilerplate sem ganho de testabilidade, já obtido com domínio puro e repositório.


## Versão 2 — novos módulos

- `core/domain/Equipes.kt` — `Composicao` (chave, assinatura, perfil de funções, pseudônimo) e `EstatisticasEquipe`
  (por composição, bairro + composição, participação individual, perfil de funções). Puro, testado na JVM.
- `core/backup/BackupBanco.kt` — gerar cópia consistente, validar, restaurar com cópia de segurança, reiniciar.
- `core/database` — `FuncionarioEntity`, `ParticipacaoServicoEntity`, `FuncionarioDao`, `MIGRATION_1_2`, gatilhos de ramal.
- `feature/team/Equipe.kt` — `EditorParticipantes`, busca em tela cheia, cadastro/edição com aviso de homônimo,
  `TelaFuncionarios`, `TelaParticipantesServico` (correção auditada), `ListaParticipantes`.
- Rotas novas: `funcionarios`, `participantes/{id}`.
