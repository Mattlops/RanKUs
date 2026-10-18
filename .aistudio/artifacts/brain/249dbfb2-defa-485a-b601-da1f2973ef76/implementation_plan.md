# Plano de Implementação: Correções Críticas de Segurança, Autenticação, Rankings e Recuperação

## 1. Visão Geral e Objetivos
Este plano tem como objetivo sanar todos os problemas críticos de autenticação, segurança, persistência e governança identificados no **RanKUs**:
1. **Persistência de Avatares**: Evitar que as fotos de perfil desapareçam ao reiniciar o app, gravando-as no armazenamento privado interno do aplicativo (`filesDir/avatars`) e sincronizando o caminho no Firestore e SharedPreferences.
2. **Cálculo Real da Sequência de Dias (Streak)**: Rastrear os dias consecutivos reais em que o usuário registrou jogos, mantendo a sequência ativa e resetando para zero caso deixe de registrar.
3. **Autenticação Real com Firebase Auth (Segurança Crítica)**: Eliminar qualquer bypass de login fictício. Exigir credenciais válidas com `signInWithEmailAndPassword` e `createUserWithEmailAndPassword`, exibindo mensagens claras de erro.
4. **Isolamento Estrito do Histórico**: Restringir a visualização e exclusão de partidas na tela de Histórico estritamente aos registros do próprio usuário logado.
5. **Cálculo Preciso da Pontuação e Presença no Ranking**: Garantir que o ranking reflita a soma exata dos registros válidos de cada usuário, e que o próprio usuário sempre apareça na classificação.
6. **Filtros Funcionais no Ranking (Jogo e Período)**: Implementar filtragem real tanto por jogo (Todos, Termo, Globle, SizeItUp) quanto por intervalo de datas (Hoje, Semana, Mês, Geral), refletindo tanto na tabela quanto no gráfico de evolução.
7. **Fluxo Completo de Esqueci Minha Senha**: Reestruturar a tela de recuperação em 4 etapas funcionais: solicitação do e-mail, geração/envio do código de 6 dígitos, validação do código no app e cadastro da nova senha.

---

## 2. Diagnóstico Técnico & Arquitetura

### A. Persistência de Avatares
- **Causa raiz**: O `ActivityResultContracts.PickVisualMedia` retorna uma URI temporária (`content://media/picker/...`) cujas permissões de leitura expiram assim que o processo do app é encerrado.
- **Solução**:
  - Ao receber a URI da galeria, ler os bytes da imagem via `contentResolver.openInputStream(uri)` e salvar em um arquivo permanente: `File(context.filesDir, "avatar_${userId}.jpg")`.
  - Salvar o caminho `file://${file.absolutePath}` no documento do usuário no Firestore (`users/{uid}.avatarUrl`) e no cache local (SharedPreferences).
  - Ao abrir o app, carregar imediatamente o avatar local salvo.

### B. Autenticação e Segurança (Firebase Auth)
- **Causa raiz**: O método `login` em `RankusRepository` aceitava qualquer string sem checar senha no Firebase Auth.
- **Solução**:
  - `loginWithEmailAndPassword(email, password, onResult)`: executa `Firebase.auth.signInWithEmailAndPassword`, tratando erros (`FirebaseAuthInvalidUserException`, `FirebaseAuthInvalidCredentialsException`).
  - `registerWithEmailAndPassword(email, password, nickname, onResult)`: executa `Firebase.auth.createUserWithEmailAndPassword`, cria o perfil no Firestore `users/{uid}` com nickname exclusivo.
  - Manter sessão ativa automaticamente checando `Firebase.auth.currentUser` na inicialização do repositório.

### C. Cálculo da Sequência Diária (Streak)
- **Regra**:
  - Buscar as datas distintas de submissão do usuário (`dateKey` formatado em YYYY-MM-DD).
  - Iniciar contagem a partir de Hoje (se jogou) ou de Ontem (se ainda não jogou hoje, mas jogou ontem para não perder a sequência antecipadamente).
  - Contar quantos dias consecutivos ininterruptos existem para trás.
  - Se não houver registro nem hoje nem ontem, `streak = 0`.

### D. Isolamento do Histórico
- A lista exposta para `HistoricoScreen` deve aplicar o filtro: `scoreHistory.filter { it.userId == currentUserId }`.
- A remoção de scores no Firestore só é permitida para `request.auth.uid == resource.data.userId` (já garantido nas security rules).

### E. Pontuação Total & Filtros do Ranking
- O `totalScore` no documento do usuário em `users/{uid}` e na memória deve ser a soma de todos os registros de pontuação válidos do usuário.
- Para a tela de Rankings, a listagem de competidores deve filtrar dinamicamente com base em `selectedGamePill` e `selectedTimeRange`:
  - **Jogo**: Se "Termo", soma apenas scores com `gameType == "termo"`; se "Globle", apenas "globle"; se "SizeItUp", apenas "sizeitup"; se "Todos os Jogos", soma todos.
  - **Período**:
    - "Hoje": registros onde `dateKey == LocalDate.now().toString()`.
    - "Semana": registros onde `dateKey >= LocalDate.now().minusDays(7).toString()`.
    - "Mês": registros onde `dateKey >= LocalDate.now().withDayOfMonth(1).toString()`.
    - "Geral": todos os registros.

### F. Recuperação de Senha (Fluxo 4 Passos)
1. **Passo 1 (E-mail)**: Usuário digita seu e-mail cadastrado. O app valida se o e-mail existe no banco.
2. **Passo 2 (Código)**: Um token de 6 dígitos aleatório é gerado (armazenado em documento temporário de recuperação ou estado seguro com validade de 15 minutos). O app dispara o e-mail de recuperação do Firebase (`sendPasswordResetEmail`) e disponibiliza o código para validação.
3. **Passo 3 (Verificação)**: Usuário digita os 6 dígitos nos inputs. O app valida se o token confere e não expirou.
4. **Passo 4 (Nova Senha)**: Usuário digita e confirma sua nova senha. O app atualiza a credencial no Firebase Auth e redireciona para o login com mensagem de sucesso.

---

## 3. Plano de Arquivos e Modificações

| Arquivo | Responsabilidade |
| :--- | :--- |
| `data/RankusRepository.kt` | Métodos reais de autenticação Firebase (`signIn`, `signUp`, `resetPassword`), cálculo de streak diário consecutivo, salvamento do avatar em arquivo local, atualização de `totalScore` no Firestore, e função de agregação de scores filtrada por jogo e período. |
| `ui/screens/AuthScreen.kt` | Integração dos campos de e-mail e senha com validação real do Firebase Auth, exibição de mensagens de erro específicas (senha incorreta, e-mail não encontrado, etc.) e bloqueio de credenciais inválidas. |
| `ui/screens/RecoveryScreen.kt` | Fluxo completo de 4 etapas (E-mail -> Código 6 dígitos -> Validação -> Nova Senha vinculada) com visual gamer moderno. |
| `ui/screens/RankingsScreen.kt` | Aplicação dos filtros dinâmicos de jogo e período na lista de competidores e no gráfico, exibindo sempre o usuário logado com seus pontos exatos. |
| `ui/screens/HistoricoScreen.kt` | Garantia de isolamento por `userId` para que nenhum jogador veja registros alheios. |
| `ui/components/ProfileModals.kt` | Persistência física do arquivo do avatar no `filesDir` e envio ao repositório para gravação definitiva. |

---

## 4. Verificação e Critérios de Aceite
1. **Teste de Login Inválido**: Tentar logar com credenciais falsas deve falhar com aviso claro em vermelho.
2. **Teste de Cadastro e Persistência de Sessão**: Criar conta, fechar o app e reabrir — a sessão e o avatar salvo devem permanecer intactos.
3. **Teste de Streak**: Registrar hoje -> sequência 1; registrar amanhã -> sequência 2; simular dia sem registro -> sequência reinicia em zero.
4. **Teste de Histórico Pessoal**: Usuário A só vê as partidas do Usuário A.
5. **Teste de Pontuação e Filtros de Ranking**: Submeter 180 pts no dia 1 e 199 pts no dia 2 -> o ranking geral deve exibir exatamente 379 pts para o usuário, e alternar para "Hoje" deve exibir apenas a pontuação do dia.
6. **Teste de Recuperação de Senha**: Completar as 4 etapas de recuperação e conseguir logar com a nova senha.
7. **Compilação**: Executar `compile_applet` e garantir status de sucesso sem avisos impeditivos.
