# Memória persistente local

Comandos explícitos em português, disponíveis no chat sem internet e antes do modelo:
- `meu nome é Ana`, `me chamo Ana`, `pode me chamar de Ana`
- `qual meu nome?`
- `lembre que prefiro café sem açúcar` (até 500 caracteres)
- `listar memórias` (até 20 entradas locais com IDs)
- `apagar memória 12` (remove apenas a entrada local indicada)
- `esqueça meu nome`

Room conserva os fatos entre sessões e reinícios. Não há migração de schema.
Listar/apagar administram a memória local inteira, inclusive fatos de extração anterior.
Apagar um fato não remove suas mensagens antigas do histórico nem cópias remotas.
Os comandos explícitos não usam modelo, extração Groq ou sincronização Lazuri.
A voz usa o mesmo handler quando reconhece a frase; isso não adiciona reconhecimento
ou síntese de voz offline em português. O telefone deve estar desbloqueado.

Validação: 9 testes JVM passaram (compiler Kotlin 2.0.21, JUnit4, coroutines 1.9.0;
anotações Android/Room e serialization substituídas por stubs). Detekt standalone
1.23.8 passou com a configuração/baseline do projeto. Compilação Android completa
e persistência real em aparelho ainda dependem de CI e teste no celular.
