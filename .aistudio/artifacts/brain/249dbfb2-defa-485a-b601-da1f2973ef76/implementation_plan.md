# Plano de Implementação: RanKUs - Ajustes de Ranking, Temporada, Modos do Termo e Nickname Único

## 1. Visão Geral e Objetivos
Ajustar o **RanKUs** para eliminar completamente quaisquer dados e nomes fictícios residuais (no Pódio, Gráficos do Ranking e na seção "Destaques" da Home), implementar contagem regressiva de dias até 31 de dezembro, permitir 1 registro por dia para cada modo do Termo (Clássico, Dueto e Quarteto com pontuação apenas no Quarteto), garantir que nicknames sejam estritamente únicos entre jogadores, e esclarecer o status da conexão da base Firebase para a geração do APK.

---

## 2. Mudanças Detalhadas

### A. Eliminação de Nomes e Pontos Falsos no Ranking e Pódio
- **Problema:** Em `RankingsScreen.kt`, o pódio possuía valores de fallback estáticos (`LucasTermo`, `Carol_G`, `Gui_Voxel`), e o gráfico semanal continha linhas fixas com os nomes `Lucas` e `Carol`.
- **Solução:**
  - Se a base não possuir jogadores suficientes para o Top 3 (1º, 2º e 3º), os campos do pódio não exibirão nomes falsos: exibirão traços (`"—"`) ou indicação de vaga aberta no pódio (`"Disponível"` / `"Sem competidor"`).
  - Caso não haja pontuações registradas na base, a tela exibirá o estado de pódio vazio (`EmptyPodiumCard`) convidando para pontuar.
  - O gráfico de evolução mostrará apenas os dados reais do jogador atual e de competidores reais existentes na base.

### B. Card de Encerramento da Temporada (Contagem até 31 de Dezembro)
- **Problema:** O card exibia o texto fixo `"Faltam 235 pts para o Pódio! Conclua o Termo Quarteto de hoje para ultrapassar Gui_Voxel."`.
- **Solução:**
  - Substituir o card por uma contagem regressiva oficial até o final da temporada anual:
    - Cálculo dinâmico: `val daysUntilEndOfYear = ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.of(now.year, 12, 31))`.
    - Título: `"Encerramento da Temporada ${today.year} ⏳"`.
    - Destaque: `"Faltam $daysUntilEndOfYear dias para 31 de dezembro!"`.
    - Descrição: `"Acumule seus pontos diários no Termo, Globle e SizeItUp antes do fechamento do ciclo anual."`.

### C. Registro Independente para Cada Modo do Termo (Clássico, Dueto e Quarteto)
- **Regra:** O jogador agora poderá registrar 1 resultado por dia para **cada modalidade** do Termo:
  - 1 registro para o **Termo Clássico** (0 pontos na temporada).
  - 1 registro para o **Termo Dueto** (0 pontos na temporada).
  - 1 registro para o **Termo Quarteto** (gera até 100 pontos oficiais).
- **Implementação:**
  - Atualizar `canSubmitGameToday(gameType: GameType, variant: TermoVariant?)` no repositório para diferenciar por variante quando o jogo for Termo.
  - A chave única de pontuação diária para o Termo passa a ser `${uid}_termo_${variant.id}_${todayStr}`.
  - No menu de cadastro, a verificação de duplicidade avalia o modo selecionado/detectado, liberando o envio se o jogador ainda não enviou aquela modalidade específica no dia.

### D. Validação de Nickname Único
- **Regra:** Não permitir que dois usuários utilizem o mesmo Nickname (case-insensitive).
- **Implementação:**
  - No `RankusRepository`, criar a função de checagem `isNicknameAvailable(nickname: String, currentUserId: String): Boolean`.
  - Verifica no Firestore (`users` collection) e na lista de usuários em memória se já existe alguém cadastrado com o mesmo nome (ignorando maiúsculas/minúsculas).
  - No modal de edição de perfil (`EditProfileDialog`), caso o nickname inserido já pertença a outro jogador, exibir mensagem de erro em vermelho: `"Este nickname já está em uso por outro jogador. Escolha um nome exclusivo!"` e bloquear a confirmação do salvamento.

### E. Seção "Destaques" da Tela Inicial
- **Problema:** A seção `DestaquesSection` em `HomeScreen.kt` exibia um card fixo com o jogador fictício `"Gabriel 'Apex' Souza"`, avatar estático e pontos não reais.
- **Solução:**
  - Conectar a seção de destaques aos dados reais:
    - Se houver jogadores com pontuação registrada, exibir o **líder real** atual (Top 1) da base com seu nome, avatar, pontuação e streak autênticos.
    - Se apenas o jogador atual estiver registrado, destacar seu próprio perfil e desempenho de hoje/total.
    - Se ninguém pontuou ainda, exibir estado esportivo convidando o jogador a ser o primeiro destaque do dia ("O topo do pódio está aberto hoje!").

### F. Resposta e Garantia de Conexão Firebase / APK
- Fornecer na resposta final a explicação detalhada de que o aplicativo já está provisionado com `google-services.json`, `firebase_applet_config.xml`, regras de segurança implantadas no Firestore (`firestore.rules`), e que ao instalar o APK a conexão funciona diretamente pela internet com o banco na nuvem.

---

## 3. Arquivos a Serem Modificados

| Arquivo | Ação | Modificações |
|---|---|---|
| `app/src/main/java/com/example/ui/screens/RankingsScreen.kt` | Modificar | Remover fallbacks de nomes fictícios no Pódio/Gráfico; substituir card do pódio pela contagem de dias até 31 de dezembro |
| `app/src/main/java/com/example/ui/screens/HomeScreen.kt` | Modificar | Substituir jogador fake na seção Destaques pelo líder/jogador real da base |
| `app/src/main/java/com/example/data/RankusRepository.kt` | Modificar | Permitir 1 registro por dia para cada modo do Termo; adicionar verificação de unicidade de Nickname |
| `app/src/main/java/com/example/ui/screens/CadastrarScreen.kt` | Modificar | Ajustar trava de duplicidade para avaliar a modalidade específica do Termo |
| `app/src/main/java/com/example/ui/components/ProfileModals.kt` | Modificar | Adicionar validação e alerta visual de Nickname já em uso |
| `app/src/main/java/com/example/ui/navigation/RankusApp.kt` | Modificar | Passar a lista de jogadores e validação de nickname para a Home e Diálogo de Perfil |

---

## 4. Verificação
- Executar `compile_applet` para assegurar build limpo.
- Executar a suíte de testes unitários (`gradle :app:testDebugUnitTest`).
- Validar visualmente e logicamente os 3 registros do Termo, a contagem de dias até 31/12, a ausência de dados fictícios e a rejeição de nicknames repetidos.
