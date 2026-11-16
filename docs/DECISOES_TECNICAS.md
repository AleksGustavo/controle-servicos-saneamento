# Decisões técnicas

| Decisão | Motivo |
|---|---|
| AGP 8.13.2, Gradle 8.14.2, Kotlin 2.2.21, KSP 2.2.21-2.0.4 | Versões estáveis já presentes/compatíveis no ambiente; AGP 9 evitado por mudanças de DSL |
| compileSdk/targetSdk 36, **minSdk 26** | `java.time` nativo sem desugaring, ícone adaptativo, `BOOT_COUNT`; cobre a maioria dos aparelhos em uso |
| Compose BOM 2025.12.00, Room 2.8.4, Navigation 2.9.6, DataStore 1.1.7 | Estáveis e verificados no build |
| DI manual (`AppContainer`) | Hilt não se paga no MVP; substituição simples em testes |
| XLSX gerado à mão (OOXML) | Evita Apache POI no APK (pesado, problemas no Android); POI usado só nos testes JVM para validar o arquivo |
| Filtragem/estatística em memória após um único SELECT com JOIN | Volume esperado (milhares) é pequeno; consulta de 1500 registros + estatística medida em teste; regras puras testáveis |
| Percentil Hyndman-Fan tipo 7 | Igual a `PERCENTIL.INC` do Excel, R e NumPy (reprodutível) |
| Capturar o fim no toque e confirmar em diálogo | Evita finalização acidental sem atrasar o horário registrado |
| Relógio monotônico no mesmo boot | Imune à alteração manual da hora; detecta e sinaliza divergência |
| Gatilhos SQLite | Garantem invariantes mesmo se algum caminho de código falhar |
| Exclusão lógica | Permite restaurar e preserva auditoria |
| Normalização de bairro sem acento/caixa/espaços | "São João" e "sao joao" são o mesmo bairro; números e palavras distintas nunca se fundem |
| Tema escuro não implementado | Opcional na especificação; prioridade à legibilidade ao sol |
| Backup Android desativado | Endereços são dados sensíveis; backup manual por exportação |

## APK release assinado (sem chaves no repositório)

1. `keytool -genkeypair -v -keystore C:\chaves\controle.jks -alias controle -keyalg RSA -keysize 4096 -validity 10000` (fora do projeto).
2. Android Studio › *Build › Generate Signed App Bundle / APK* › escolher a chave.
3. Ou criar `keystore.properties` local (ignorado pelo git) e um `signingConfigs` que o leia.
4. Guardar a chave e as senhas com backup seguro: perder a chave impede atualizar o app instalado.


## Versão 2

- **Sem ForegroundService/notificação/wake lock** para o cronômetro: a duração é diferença entre marcos persistidos;
  o Android calcula ao reabrir. Nada a pedir de otimização de bateria.
- **Fim na confirmação** (aditivo prevalece sobre a v1, que gravava o primeiro toque).
- **Comprimento em centímetros inteiros** (exato) em vez de REAL; entrada com no máximo 2 casas decimais para que
  "1.234" seja rejeitado em vez de virar 1234 m.
- **Chave de composição derivada** na leitura (não persistida) para nunca divergir após correções.
- **Coerência do ramal por gatilhos** (o SQLite não aceita `ADD CONSTRAINT CHECK`); validação também no repositório e na UI.
- **Iniciar sem equipe é permitido** (com confirmação) para não atrasar o início em campo; **salvar exige** ao menos
  um participante, salvo confirmação explícita "Salvar sem equipe".
- **Backup = arquivo SQLite** (versionado por `user_version`) em vez de JSON: restauração exata (incluindo auditoria)
  e migração automática de backups antigos.
