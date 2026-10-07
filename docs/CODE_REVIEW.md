# Code review

Revisão do worker de campanhas e do webhook de moderação do canal guardião, contra o código atual (`WebhookService`, `JobExecutionService`, `JobRunner`, clientes HTTP, Kubernetes e CI).

O sistema faz duas coisas: dispara jobs de campanha na Evolution e modera grupos do canal guardião (apaga a mensagem e expulsa quem não é admin). A autenticação do webhook e o casamento de identidade do participante estão sólidos. Os problemas que mais importam estão na moderação em si, na confiabilidade do worker e no deploy.

## Resumo

| Severidade | Local | Problema |
|---|---|---|
| Alto | `WebhookService.extractVisibleText` | Citação e árvore inteira da mensagem entram na detecção de link e template. Resposta a um anúncio com URL expulsa quem só citou. |
| Alto | `WebhookController`, `EvolutionClientConfig`, `EdgeFunctionsClientConfig` | Moderação bloqueia a thread HTTP. `WebClient` sem timeout. Sem deduplicação por id da mensagem. |
| Alto | `JobExecutionService`, `JobRunner`, `RiseApiService.updateJobStatus` | Falha no meio da campanha marca o job inteiro como erro. Status engolido deixa o job reexecutável ou preso em `executing`. |
| Alto | `.github/workflows/deploy.yml`, `Dockerfile` | Push na `master` publica sem `./gradlew test` e sem `kubectl rollout status`. Imagem só com tag `latest`. |
| Médio | `WebhookService.isGroupJid` | Só `remoteJid` terminado em `@g.us` é grupo. `remoteJidAlt` não é lido. |
| Médio | `WebhookController` | Cota só depois da autenticação. 429 descarta o evento. Tentativa de chave é ilimitada. |
| Médio | `RiseApiService.isGuardianInstanceForGroup` | Uma chamada Rise por match, em toda mensagem de grupo vinculado. |
| Médio | `WebhookService.isGroupClosed` | Falha ao ler o grupo trata como aberto. Mensagem sem link fica no ar. |
| Médio | `WebhookService.canonicalClusterJid` | Expulsão prefere `jid`, mesmo `@lid`. `phoneNumber` só entra se `jid` e `id` forem vazios. |
| Médio | `JobExecutionService` | `Thread.sleep` de 3–5s por grupo na única thread do `@Scheduled`. `metadata.group_id` é ignorado. Lista vazia conclui o job. |
| Médio | `EvolutionApiService`, `JobRunner` | Log do worker grava trecho da mensagem, destino e body de erro da Evolution. |
| Médio | `EvolutionApiService.sendMediaMessage` | `file_url` vai cru para a Evolution. Query string vira nome de arquivo. |
| Médio | `k8s/network.yaml` | Ingress não declara TLS. HTTPS depende de configuração do Traefik fora deste repositório. |
| Médio | `k8s/deployment.yaml`, `Dockerfile` | Sem probe. Heap de 75% sobre limite de 1Gi. Imagem `latest`. |
| Médio | `build.gradle` | Kafka, `starter-web` duplicado e dotenv no classpath sem uso no código. |
| Médio | `docs/fluxo_canal_guardiao.md` | Spam (10 msgs/min) e lock Redis não existem no código. `messages.update` só loga. |
| Baixo | serviços em geral | Log por `System.out` / `System.err`. |
| Baixo | `WebhookController.apiKeyMatches` | `MessageDigest.isEqual` revela o tamanho do token quando os comprimentos diferem. |
| Baixo | `k8s/deployment.yaml`, `k8s/network.yaml` | O mesmo Service está nos dois manifestos. |
| Baixo | `docs/SECURITY_REVIEW.md` | Ainda descreve o matcher antigo (`findParticipant`, primeiro alias). |
| Baixo | `WebhookService.handleUpsert` | `fromMe` nulo segue como mensagem de terceiro. |
| Baixo | `Dockerfile` | O glob `*[^plain].jar` só acerta porque a versão termina em `T` (`SNAPSHOT`). |

**Ordem sugerida:** (1) detectar link e template só no texto da mensagem nova; (2) timeout no `WebClient`, processar o webhook fora da thread HTTP e deduplicar por id da mensagem; (3) claim de job com falha de status interrompendo a execução e recuperação de `executing` abandonado; (4) `./gradlew test` no pipeline, imagem por digest ou tag de commit, e `kubectl rollout status`.

---

## O que está sólido

- `POST /webhook/message-check` só segue depois de comparar `apikey` com `WEBHOOK_TOKEN` (`MessageDigest.isEqual`). Token ausente, nulo ou em branco rejeita tudo. Acima da cota a resposta é 429, sem corpo e sem chamar Evolution nem Rise.
- O Ingress publica só `/webhook`. O Service é `ClusterIP`. O container sobe como usuário não-root. Os segredos vêm de `backend-secrets`.
- A expulsão não usa o JID cru do payload. O código monta um cluster de identidade na lista da Evolution, recusa admin, conflito e JID ambíguo, e remove o JID canônico do participante casado. Os testes cobrem o bypass antigo (não-admin em `participant`, admin em `participantAlt`).

---

## 1. Alto — Citação e domínio casual expulsam o participante

**Arquivo:** `WebhookService.extractVisibleText`, `containsLink`, `containsTemplatePayload`

`extractVisibleText` percorre a árvore inteira da mensagem e do `contextInfo`. Qualquer campo `conversation`, `text`, `caption`, `matchedText`, `canonicalUrl` ou `description` entra na detecção. No payload da Evolution, a mensagem citada fica dentro de `contextInfo.quotedMessage`. Uma resposta “ok” a um anúncio com URL casa o link e segue para apagar e expulsar.

O mesmo vale para template: qualquer chave cujo nome contém `template`, em qualquer nível, dispara a moderação.

O regex de domínio também é largo: `empresa.com`, e-mail e sufixos como `.me`, `.co` e `.app` contam como golpe.

Em grupo fechado, qualquer texto de não-admin já apaga e expulsa. Isso está alinhado com `docs/fluxo_canal_guardiao.md`. O risco extra é expulsar no grupo aberto por citação ou por domínio casual.

---

## 2. Alto — Webhook síncrono, sem timeout e sem idempotência

**Arquivos:** `WebhookController.messageCheck`, `EvolutionClientConfig`, `EdgeFunctionsClientConfig`, `EvolutionApiService`, `RiseApiService`

Cada evento autenticado faz várias chamadas bloqueantes (Rise, participantes, delete, remove) antes do 200. A cota é 10 req/s com rajada de 100, por processo. Os `WebClient` não configuram timeout de conexão nem de resposta. Uma Evolution lenta ocupa threads do Tomcat até o pool encher. Não há probe de liveness no Deployment, então o Kubernetes continua tratando o pod como saudável.

Não há trava por `messageId`. `docs/fluxo_canal_guardiao.md` pede lock de 5s em Redis por `groupJid` + participante. A Evolution reenvia o mesmo evento e as duas passagens moderam de novo.

---

## 3. Alto — Job parcial, status engolido e `executing` preso

**Arquivos:** `JobExecutionService.handleTaskExecution`, `JobRunner.runJobs`, `RiseApiService.updateJobStatus`, `JobRetryRunner`

`JobExecutionService` manda para todos os grupos e, no primeiro erro, aborta o restante. Os grupos anteriores já receberam a mensagem. `JobRunner` marca o job inteiro como `error`.

`JobRetryRunner` está comentado por completo, então não há retry automático. Se alguém recolocar o job em `pending`, a campanha inteira recebe de novo.

`updateJobStatus` engole a exceção. Se o `PATCH` para `executing` ou `completed` falhar, o job continua `pending` e o ciclo de 10s dispara outra vez. Se o processo morrer depois de `executing`, nada varre esse estado: o fetch só pede `pending`, e o retry de `error` não está ativo.

Lista de grupos vazia não lança erro: o job é marcado `completed` sem ter enviado nada.

`metadata.group_id` é ignorado. Cada job vai para todos os grupos da campanha. Se a edge function já cria um job por grupo, cada execução multiplica o envio pelo número de grupos.

---

## 4. Alto — Deploy sem teste e sem espera do rollout

**Arquivos:** `.github/workflows/deploy.yml`, `Dockerfile`

Todo push na `master` faz build e `kubectl apply`. O workflow não roda `./gradlew test` nem `kubectl rollout status`. A imagem publicada é só `ezdussin/whatsapp-automation-backend:latest`.

O `Dockerfile` gera o jar com `-x test`. Os testes de bypass de admin existem e não travam o deploy. Tag `latest` dificulta rollback.

---

## 5. Médio — `remoteJidAlt` ignorado

**Arquivo:** `WebhookService.isGroupJid`, `MessageKey`

Só `remoteJid` terminado em `@g.us` entra na moderação. `remoteJidAlt` existe no record e não é lido. Evento em que o grupo vem no alt é ignorado.

---

## 6. Médio — Cota depois da auth, e 429 descarta moderação

**Arquivo:** `WebhookController.messageCheck`, `WebhookRateLimiter`

Request sem token não consome a cota, então a tentativa de chave é ilimitada. O corpo JSON já foi parseado antes da comparação. Request com token acima da cota recebe 429 e a mensagem não é moderada. Este código não garante retry da Evolution nesse caso.

---

## 7. Médio — Instância guardiã em N chamadas

**Arquivo:** `RiseApiService.isGuardianInstanceForGroup`

Para cada match guardião o método chama `getInstanceNameByCampaignId` em sequência. Toda mensagem de grupo vinculado paga isso, inclusive “oi” em grupo aberto, porque o anúncio fechado só é conhecido depois do lookup.

---

## 8. Médio — Grupo fechado falha aberto

**Arquivo:** `WebhookService.isGroupClosed`

Exceção em `findGroupInfo` trata o grupo como aberto. Mensagem sem link nesse grupo fica no ar.

---

## 9. Médio — Expulsão prefere `@lid`

**Arquivo:** `WebhookService.canonicalClusterJid`

A ordem é `jid`, depois `id`, depois `phoneNumber`. `phoneNumber` só entra se `jid` e `id` forem vazios. Se a Evolution só remove pelo número `@s.whatsapp.net`, a expulsão falha com o LID. O `catch` registra a classe da exceção, sem a mensagem da API.

---

## 10. Médio — Scheduler bloqueado e fan-out da campanha

**Arquivo:** `JobExecutionService.handleTaskExecution`

Há `Thread.sleep` de 3–5s por grupo. O pool padrão do `@Scheduled` tem uma thread. Uma campanha com dezenas de grupos segura a fila inteira. O `fixedDelay` de 10s só começa quando essa thread libera.

`metadata.group_id` não filtra o destino. Lista de grupos vazia conclui o job (ver item 3).

---

## 11. Médio — PII no log do worker

**Arquivos:** `EvolutionApiService.sendTextMessage`, `JobRunner`, `JobExecutionService`

O webhook de moderação evita instance, JID, push name e trecho da mensagem. O worker ainda grava os primeiros 30 caracteres do texto, o número de destino e o body de erro da Evolution no stdout e em `error_message` no Rise.

---

## 12. Médio — `file_url` cru na Evolution

**Arquivo:** `EvolutionApiService.sendMediaMessage`

`file_url` vira o campo `media`. O nome do arquivo sai de `Paths.get(url)`. Query string de URL assinada vira nome de arquivo. No Windows, `Paths.get` de URL com `https:` quebra; no container Linux, não. Quem consegue criar job na Rise escolhe a URL que a Evolution busca.

---

## 13. Médio — TLS do Ingress fora do repositório

**Arquivo:** `k8s/network.yaml`

O Ingress não declara certificado. O `WEBHOOK_TOKEN` viaja no JSON do webhook. HTTPS só existe se o Traefik do cluster terminar TLS fora deste manifesto.

---

## 14. Médio — Probe, memória e tag da imagem

**Arquivos:** `k8s/deployment.yaml`, `Dockerfile`

Não há liveness nem readiness. `-XX:MaxRAMPercentage=75.0` sobre o limite de 1024Mi deixa pouca margem para metaspace e threads. A imagem é `latest` com `imagePullPolicy: Always`.

---

## 15. Médio — Dependências sem uso

**Arquivo:** `build.gradle`

`spring-boot-starter-kafka` entra no classpath sem consumidor no código (broker default `localhost:9092`). Há `spring-boot-starter-web` junto de `spring-boot-starter-webmvc`, e `spring-dotenv` junto de `dotenv-java`.

---

## 16. Médio — Regras do fluxo guardião ausentes

**Arquivos:** `docs/fluxo_canal_guardiao.md`, `WebhookService.handleUpdate`

O documento pede spam (10 mensagens em menos de 1 minuto) e lock temporário no Redis. Nenhum dos dois está no código. `messages.update` só escreve log.

---

## 17. Baixo

| Local | Problema |
|---|---|
| Serviços em geral | Log por `System.out` / `System.err`, sem nível nem id de correlação. |
| `WebhookController.apiKeyMatches` | `MessageDigest.isEqual` revela o tamanho do token quando os comprimentos diferem. |
| `k8s/deployment.yaml`, `k8s/network.yaml` | O mesmo Service está nos dois manifestos. O apply seguinte vence se eles divergirem. |
| `docs/SECURITY_REVIEW.md` | Ainda descreve `findParticipant` e o primeiro alias. O código atual usa cluster de identidade e recusa conflito. |
| `WebhookService.handleUpsert` | `fromMe` nulo segue o fluxo como mensagem de terceiro. |
| `Dockerfile` | O glob `*[^plain].jar` é uma classe de caracteres. O boot jar atual entra porque `SNAPSHOT` termina em `T`. |

---

## Testes

Há cobertura do admin forjado, de LID versus telefone e da cota 401/429 (`WebhookServiceTest`, `WebhookControllerRateLimitTest`, `WebhookRateLimiterTest`).

Não há teste de contrato JSON com o snake_case da Rise (`campaign_id`, `guardian_linked`, `send_message`), de citação com link, de grupo fechado, de job parcial, nem de timeout do `WebClient`.

---

## Plano

1. Detectar link e template só no texto da mensagem nova, fora de `quotedMessage` / `contextInfo`.
2. Timeout no `WebClient`, processar o webhook fora da thread HTTP e deduplicar por id da mensagem.
3. Tratar o job como claim: falha de status interrompe a execução; sucesso parcial não reenvia o grupo que já passou; recuperar `executing` abandonado.
4. Rodar `./gradlew test` no pipeline e publicar a imagem por digest ou tag de commit, esperando `rollout status`.
