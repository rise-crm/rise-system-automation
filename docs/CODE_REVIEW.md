# Code review

Revisão completa do backend em 4 de novembro de 2026: worker de jobs, moderação do canal guardião, autenticação, clientes HTTP, Redis, Kubernetes e CI. Substitui a revisão anterior.

O sistema faz duas coisas: dispara jobs de campanha na Evolution e modera grupos do canal guardião (apaga a mensagem e expulsa quem não é admin). A identificação do remetente é sólida e bem testada. Os riscos que mais importam estão na moderação (mensagens que escapam e expulsões indevidas), na confiabilidade do worker (jobs duplicados ou presos) e no deploy (sem nenhuma barreira).

## Resumo

| # | Severidade | Status | Local | Problema |
|---|---|---|---|---|
| 1 | Alto | Corrigido | `WebhookRegistrationService`, `EvolutionApiService.setWebhook` | Eventos sem uso consumiam a cota do webhook e `base64: true` estourava o limite de 1 MB com mídia. |
| 2 | Alto | Corrigido | `InstanceTokenValidator` | Requisição sem autenticação disparava `fetchInstances` na Evolution, sem limite. |
| 3 | Alto | Parcial | `EvolutionClientConfig`, `EdgeFunctionsClientConfig`, `WebhookController` | `WebClient` sem timeout. Moderação síncrona na thread HTTP. |
| 4 | Alto | Aberto | `JobRunner`, `JobExecutionService`, `RiseApiService.updateJobStatus` | Job reexecutado quando o status falha, reenvio após falha parcial, `executing` preso. |
| 5 | Alto | Corrigido | `WebhookService.extractOwnText`, `containsLink` | Citação e e-mail expulsavam quem não mandou link. |
| 6 | Alto | Aberto | `.github/workflows/deploy.yml`, `Dockerfile`, `k8s/deployment.yaml` | Deploy sem testes, sem `rollout status`, sem probe e com imagem `latest`. |
| 7 | Médio | Aberto | `ApiKeyAuthenticationFilter`, `WebhookService` | Token de instância guardiã basta para forjar eventos e expulsar membros. |
| 8 | Médio | Aberto | `WebhookController.setWebhook` | `POST /webhook` exige a `EVOLUTION_KEY`, a chave mestra da Evolution. |
| 9 | Médio | Aberto | `ScheduleJob`, `JobType`, `JobMetadata` | Um job com dado inesperado derruba a leitura de todos. |
| 10 | Médio | Aberto | `JobPollingState`, `k8s/redis.yaml` | Pausa do polling se desfaz com o Redis fora do ar ou reiniciado. |
| 11 | Médio | Aberto | `WebhookService`, `EvolutionApiService`, `RiseApiService` | Telefones, JIDs e trechos de mensagem nos logs. |
| 12 | Médio | Aberto | vários | Itens da revisão anterior ainda abertos. |
| 13 | Baixo | Aberto | vários | Ver tabela no fim. |

**Ordem sugerida:** (1) tratar o job como claim e recuperar `executing` abandonado; (2) testes no pipeline, imagem por tag de commit, readiness e `rollout status`; (3) moderação fora da thread HTTP.

---

## O que está sólido

- O remetente é identificado por um cluster de identidade montado com a lista de participantes da Evolution. O código recusa admin, conflito e JID ambíguo, e expulsa o JID canônico do participante, não o JID cru do payload. Os testes cobrem admin forjado em `participantAlt` e LID versus telefone.
- A regra de spam (10 mensagens por minuto) usa uma janela no Redis deduplicada por id da mensagem.
- A expulsão tem trava no Redis por grupo e contato durante 5 minutos, então gatilhos seguidos pedem um único ban.
- A comparação de tokens é feita em tempo constante sobre hash SHA-256 e não revela o tamanho do token.
- O Ingress tem TLS via cert-manager e publica só `/webhook` e `/admin`. Qualquer outra rota recebe 401.
- O Redis roda sem root, com sistema de arquivos somente leitura e senha gerada no cluster.

---

## 1. Alto, corrigido: eventos sem uso e mídia acima de 1 MB

**Arquivos:** `WebhookRegistrationService`, `EvolutionApiService.setWebhook`

O webhook assinava `SEND_MESSAGE`, `MESSAGES_UPSERT`, `MESSAGES_UPDATE` e `MESSAGES_DELETE`, mas o código só modera `messages.upsert`. `MESSAGES_UPDATE` dispara a cada confirmação de entrega e leitura, várias vezes por mensagem em grupos grandes. Todos os eventos gastavam a mesma cota global de 10 requisições por segundo, e acima dela o `messages.upsert` recebia 429 e era perdido.

Com `base64: true`, a Evolution embutia a mídia no callback, que tem limite de 1 MB. Imagem ou vídeo maior recebia 413, então golpe com link na legenda de uma imagem nunca era analisado. O conteúdo em base64 não é usado pelo código.

**Correção:** o webhook agora assina só `MESSAGES_UPSERT`, com `base64: false`. Instâncias já configuradas só mudam quando o `POST /webhook` for chamado de novo para cada uma.

---

## 2. Alto, corrigido: busca de token sem limite

**Arquivo:** `InstanceTokenValidator`

O filtro de autenticação roda antes do limite de taxa do controller. Uma requisição sem autenticação, com qualquer `instance` e qualquer `apikey`, disparava `fetchInstances` na Evolution. Nomes inventados nunca entravam no cache, então cada requisição virava uma chamada.

**Correção:** a busca na Evolution, feita só quando o token não está em cache, tem um limite próprio (`WEBHOOK_TOKEN_LOOKUP_PERMITS_PER_SECOND`, padrão 1 por segundo, rajada `WEBHOOK_TOKEN_LOOKUP_BURST` de 10). Callbacks com token em cache ou com `EVOLUTION_KEY` não passam por esse limite. Respostas "não encontrado" não são guardadas: o atacante escolhe os nomes e encheria a memória.

---

## 3. Alto, parcial: timeout e moderação síncrona

**Arquivos:** `EvolutionClientConfig`, `EdgeFunctionsClientConfig`, `WebhookController.messageCheck`

Os dois `WebClient` não tinham timeout, e todas as chamadas usam `.block()`. Uma Evolution ou Rise lenta prendia as threads do Tomcat no webhook e prendia para sempre a única thread do `@Scheduled`, parando o polling em silêncio.

**Corrigido:** timeout de conexão (`HTTP_CONNECT_TIMEOUT_MS`, padrão 5 s) e de resposta (`EVOLUTION_RESPONSE_TIMEOUT_MS`, padrão 30 s; `EDGE_FUNCTIONS_RESPONSE_TIMEOUT_MS`, padrão 15 s) nos dois clientes.

**Aberto:** cada mensagem de grupo faz de 4 a 7 chamadas à Rise e à Evolution antes de responder 200. A moderação deveria sair da thread HTTP. Não há deduplicação por id da mensagem antes de apagar: a trava da expulsão evita ban duplicado, mas um reenvio do evento apaga e consulta tudo de novo. A trava de expulsão libera em caso de Redis fora do ar.

Timeout no envio de um job pode marcar como erro um envio que a Evolution concluiu. Com o item 4 aberto, reprocessar esse job reenvia a mensagem.

---

## 4. Alto, aberto: job duplicado, parcial ou preso

**Arquivos:** `JobRunner.runJobs`, `JobExecutionService.handleTaskExecution`, `RiseApiService.updateJobStatus`, `JobRetryRunner`

- `updateJobStatus` engole qualquer erro. Se o `PATCH` para `executing` e depois para `completed` falharem, o job continua `pending` e é reenviado para todos os grupos 10 s depois.
- `JobExecutionService` para no primeiro grupo que falhar. Os anteriores já receberam a mensagem, o job inteiro vira `error`, e reprocessá-lo reenvia para todos.
- Se o pod morrer durante a execução, o job fica `executing` para sempre. O `JobRetryRunner` está inteiro comentado.
- `metadata.group_id` é ignorado: cada job vai para todos os grupos da campanha. Campanha sem grupos é marcada `completed` sem ter enviado nada.
- A pausa de 3 a 5 s por grupo roda na única thread do agendador.

**Correção sugerida:** abortar o job se a marcação de `executing` falhar, registrar os grupos já enviados para não reenviar, e varrer `executing` antigo.

---

## 5. Alto, corrigido: expulsão por citação e e-mail

**Arquivo:** `WebhookService.extractOwnText`, `containsLink`, `containsTemplatePayload`

A extração de texto percorria a árvore inteira da mensagem e o `contextInfo`, inclusive a mensagem citada em `quotedMessage`. Responder "ok" a um anúncio com URL apagava e expulsava. A detecção de template também olhava dentro da citação. O regex de domínio contava e-mails como link.

**Correção:** link e template são analisados só na mensagem nova. As chaves `contextInfo` e `quotedMessage` são ignoradas em qualquer nível, e mensagens temporárias e de visualização única continuam analisadas. Domínio sem esquema continua contando como link, inclusive subdomínio (`promo.golpe.com`), mas não quando faz parte de um e-mail (`joao@empresa.com`, `joao.me@gmail.com`).

**Decisão mantida:** domínio solto como `site.com` conta como link por política do grupo. A detecção de template ainda dispara com qualquer chave que contenha `template` na mensagem nova.

---

## 6. Alto, aberto: deploy sem barreira

**Arquivos:** `.github/workflows/deploy.yml`, `Dockerfile`, `k8s/deployment.yaml`

O workflow não roda os testes e o `Dockerfile` compila com `-x test`. O deploy termina em `rollout restart`, sem `rollout status`. A imagem é sempre `latest`, sem rollback. O backend não tem readiness nem liveness, e o Actuator não está nas dependências: no rollout o tráfego vai para o pod antes de o Spring subir.

O `contextLoads` falha sem `EDGE_FUNCTIONS_KEY`. Para virar barreira, o teste precisa de propriedades de teste.

---

## 7. Médio, aberto: token de instância permite forjar eventos

**Arquivos:** `ApiKeyAuthenticationFilter`, `WebhookService.handleUpsert`

`/webhook/message-check` aceita o token da instância citada no payload e confia no conteúdo do evento sem confirmar a mensagem na Evolution. Quem tem o token de uma instância guardiã (visível no painel da Evolution e devolvido pelo `POST /webhook`) pode forjar um evento com qualquer `participant` e fazer o sistema expulsar qualquer membro não admin. Antes, isso exigia um segredo dedicado.

---

## 8. Médio, aberto: chave mestra da Evolution fora do servidor

**Arquivo:** `WebhookController.setWebhook`, `ApiKeyAuthenticationFilter`

O `POST /webhook` exige a `EVOLUTION_KEY`. Qualquer sistema que configure webhooks precisa guardar a chave que controla todas as instâncias. O `ADMIN_TOKEN`, numa rota em `/admin`, limitaria o estrago de um vazamento.

---

## 9. Médio, aberto: um job inválido trava todos

**Arquivos:** `ScheduleJob`, `JobType`, `JobMetadata`, `RiseApiService.fetchPendingTasks`

Um `type` desconhecido faz a leitura da lista inteira falhar, e o polling falha a cada 10 s até alguém corrigir o job na Rise. `mention_all` nulo, num `boolean` primitivo, pode ter o mesmo efeito conforme a configuração do Jackson. Ler a lista como JSON genérico e converter job a job isola o item ruim.

---

## 10. Médio, aberto: pausa do polling não é confiável

**Arquivos:** `JobPollingState.isEnabled`, `k8s/redis.yaml`

`isEnabled` devolve `true` quando o Redis está fora do ar. O Redis roda sem persistência (`emptyDir`, `--save ""`), então depois de reiniciar a chave some e o polling volta ligado. Um "pausar campanhas" pode ser desfeito sem ninguém perceber.

---

## 11. Médio, aberto: dados pessoais nos logs

**Arquivos:** `WebhookService`, `EvolutionApiService`, `RiseApiService`

Os logs de diagnóstico gravam telefones e JIDs de participantes, inclusive o cluster de identidade com telefone. O worker grava os primeiros 30 caracteres de cada mensagem enviada. O corpo de erro da Evolution vai inteiro para o `error_message` na Rise. Pesa para a LGPD.

---

## 12. Médio, aberto: itens da revisão anterior

| Local | Problema |
|---|---|
| `RiseApiService.isGuardianInstanceForGroup` | Uma chamada à Rise por campanha vinculada, em toda mensagem de grupo, sem cache. |
| `WebhookService.isGroupClosed` | Falha ao ler o grupo trata como aberto. Mensagem sem link fica no ar. |
| `WebhookService.canonicalClusterJid` | Expulsão prefere o JID `@lid` ao telefone. Confirmar se a Evolution expulsa por `@lid`. |
| `WebhookService.isGroupJid` | `remoteJidAlt` é ignorado. |
| `EvolutionApiService.sendMediaMessage` | `file_url` vai cru para a Evolution. Query string vira nome de arquivo. |
| `build.gradle` | Kafka, `starter-web` duplicado, dois pacotes de dotenv e `jackson-databind` 2 junto do Jackson 3, sem uso. |
| `Dockerfile` | `MaxRAMPercentage=75` sobre limite de 1 GiB deixa pouca margem. |

---

## 13. Baixo

| Local | Problema |
|---|---|
| Serviços em geral | Log por `System.out` e `System.err`, sem nível nem id de correlação. |
| `JobRetryRunner.java` | Arquivo inteiro comentado. |
| `k8s/deployment.yaml`, `k8s/network.yaml` | O mesmo `Service` nos dois manifestos. |
| `k8s/traefik-config.yaml` | Emissor ACME do Traefik além do cert-manager, fora do pipeline, com e-mail pessoal. |
| `JobExecutionService` | Interrupção do `Thread.sleep` engolida pelo `catch (Exception)`. |
| `WebhookController.setWebhook` | 502 para qualquer exceção, inclusive bug interno. |
| `WebhookService.handleUpsert` | `fromMe` nulo segue como mensagem de terceiro. |
| `Dockerfile` | O glob `*[^plain].jar` só acerta porque a versão termina em `T`. |
| `docs/SECURITY_REVIEW.md` | Ainda descreve o `WEBHOOK_TOKEN`, removido. |

---

## Testes

Há cobertura de identidade do remetente, spam, trava de expulsão, autenticação do webhook, cache e limite da busca de token, timeout do cliente HTTP, leitura de `fetchInstances` e corpo do `webhook/set`.

Há cobertura de citação com link e com template, legenda com link em resposta, mensagem temporária, e-mail e domínio solto.

Faltam testes de grupo fechado, job parcial, falha de status, `type` desconhecido e contrato JSON da Rise (`campaign_id`, `guardian_linked`, `send_message`).
