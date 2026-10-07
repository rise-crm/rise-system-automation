# Revisão de segurança

Revalidação contra o código atual (webhook de moderação, Kubernetes e CI). A revisão anterior tratava o `POST /webhook/message-check` como anônimo e a remoção como uso do JID cru do payload. Os dois pontos críticos dessa leitura **não se sustentam mais**.

## Resumo

| Severidade | Status | Local | Problema |
|---|---|---|---|
| Critical | Corrigido | `WebhookService` | Remoção usa o JID canônico do participante casado na Evolution. O bypass “checar não-admin e banir admin” não reproduz. |
| High | Corrigido | `WebhookController` | `apikey` é comparado com `WEBHOOK_TOKEN` antes de moderar. Request sem segredo recebe 401 e não chama Evolution nem Rise. |
| Medium | Corrigido | `EvolutionWebhookPayload.apikey` | O campo é usado. Segredo dedicado (`webhook.token`), não `EVOLUTION_KEY`. |
| Medium | Corrigido | `WebhookController` | O `println` do payload saiu. O `apikey` não vai mais para o stdout. |
| Medium | Corrigido | `k8s/network.yaml` | O Ingress publica só `/webhook`. O restante do processo não entra por `api.dariodussin.com.br`. |
| Medium | Corrigido | `.github/workflows/deploy.yml` | O `kubectl` usa o `server` e o CA de `KUBE_CONFIG`. Sem IP fixo e sem `--insecure-skip-tls-verify`. |
| Low | Corrigido | `WebhookController` | Depois da auth, a cota é 10 req/s com rajada de 100. Acima disso a resposta é 429 e não há chamada à Evolution nem à Rise. |
| Low | Corrigido | Logs de moderação | O webhook não grava mais instance, JID, push name nem trecho da mensagem. |
| Low | Corrigido | `build.gradle` | O starter do Actuator saiu do classpath. Não há `/actuator` na porta do processo. |
| Low | Corrigido | `WebhookController` | 200, 401 e 429 saem sem corpo. O endpoint não devolve `event` nem `handled`. |

**Prioridade agora:** confirmar que o secret `KUBE_CONFIG` tem `certificate-authority-data` e um `server` alcançável, sem `insecure-skip-tls-verify`. Se logs de produção anteriores à remoção do `println` já foram coletados, rotacionar `WEBHOOK_TOKEN`.

---

## 1. Critical — Bypass de admin — corrigido

**Arquivo:** `WebhookService.handleUpsert`

A remoção não usa mais `resolveContactJid()` (que ainda prefere `participantAlt`, só para log e para recusar JID ausente). O fluxo atual:

1. `findParticipant()` casa `participant`, `participantAlt` ou `senderPn` com `id` / `jid` / `phoneNumber` da lista da Evolution.
2. Se o registro casado é admin (`GroupParticipant.isGroupAdmin()`), a ação para.
3. `removeGroupParticipant()` recebe `canonicalParticipantJid(sender)` (`jid`, senão `id`, senão `phoneNumber` desse registro).

O ataque antigo (não-admin em `participant`, admin em `participantAlt`) não remove o admin: ou o admin é o primeiro match e a ação é ignorada, ou o não-admin é o match e só o JID canônico dele é removido.

Há gates novos antes disso: grupo ligado a campanha guardiã (`RiseApiService.getGuardianGroupCampaign`), `instance` do payload igual à instância guardiã, instância admin separada e conectada, e gatilho de link, template ou grupo fechado (`announce`).

**Residual (baixo):** os aliases continuam em OR e o primeiro match da lista vence. Um payload autenticado que misture dois não-admins pode remover o que aparecer primeiro, não necessariamente o de `resolveContactJid()`. `deleteMessageForEveryone` ainda manda o `participant` cru da key (`EvolutionApiService`, primeiro não vazio entre `participant`, `participantAlt` e `senderPn`), não o JID canônico. Isso não reabre a remoção de admin.

---

## 2. High — Webhook sem autenticação — corrigido

**Arquivo:** `WebhookController.messageCheck`

- Compara `payload.apikey()` com `${webhook.token}` (`WEBHOOK_TOKEN` em `application.properties` e `secretKeyRef` em `k8s/deployment.yaml`).
- `MessageDigest.isEqual` (tempo constante no conteúdo; tamanhos diferentes falham na hora).
- Token ausente, nulo ou em branco no servidor rejeita tudo (falha fechada).
- 401 com corpo vazio, antes de `WebhookService.checkMessage`.
- 200 e 429 também saem sem corpo. O endpoint não devolve `event` nem `handled`.

Não há Spring Security. O único endpoint de negócio é esse `POST`. Quem tem o token ainda pode moderar: apagar mensagem e remover não-admin em grupo guardião, desde que saiba `instance` guardiã, `groupJid` e um JID de membro.

---

## 3. Medium — Campo `apikey` não usado — corrigido

O campo é obrigatório na prática: nulo ou diferente de `WEBHOOK_TOKEN` encerra o request. A comparação é com o segredo do webhook, não com `evolution.key`. A Evolution precisa enviar esse mesmo valor em `apikey`.

---

## 4. Medium — Amplificação na Evolution — limitado depois da auth

Request sem token não chega em `findGroupParticipants`, `removeGroupParticipant`, `deleteMessageForEveryone` nem nas chamadas Rise, e também não consome a cota.

Com token válido, `WebhookController` só chama `checkMessage` se `WebhookRateLimiter` conceder uma vaga. O padrão é 10 requisições por segundo, com rajada de 100 (`webhook.rate-limit.permits-per-second` e `webhook.rate-limit.burst`). O excesso responde 429 com `Retry-After` e corpo vazio. A cota é em memória, por processo; o Deployment tem 1 réplica.

---

## 5. Medium — Token do webhook no log — corrigido

O `System.out.println(payload)` saiu de `WebhookController.messageCheck`. A rejeição loga só que a `apikey` é inválida, sem o valor. O `toString` do record, que inclui `apikey`, não é mais impresso.

Os logs de moderação em `WebhookService`, na remoção/apagamento da Evolution e nas falhas Rise desse caminho ficam no motivo (`link`, `template`, `closed_group`), no status e no id da campanha. Não incluem instance, JID, push name nem texto da mensagem. Falha registra a classe da exceção, não a mensagem da API.

Rotacionar `WEBHOOK_TOKEN` se o stdout de produção anterior a esta correção já foi retido.

---

## 6. Medium — Ingress público — corrigido

`k8s/network.yaml` publica só `path: /webhook` em `api.dariodussin.com.br`. Qualquer outra rota do processo fica de fora do Ingress. O Service continua `ClusterIP`. O starter do Actuator não está mais no classpath, então `/actuator/health` não existe nem dentro do cluster.

`/webhook/message-check` segue alcançável na Internet porque a Evolution precisa enviar o callback. A ação continua condicionada ao `WEBHOOK_TOKEN`. Allowlist de IP da Evolution não foi aplicada: os ranges de origem não estão no repositório.

---

## 7. Medium — TLS do deploy — corrigido

`.github/workflows/deploy.yml` não passa mais `--server=https://213.136.92.71:6443` nem `--insecure-skip-tls-verify=true`. O `kubectl` usa o endereço e o CA que estiverem em `secrets.KUBE_CONFIG`.

O secret precisa ter `certificate-authority-data` e um `server` que o runner do GitHub alcance, com esse host ou IP no SAN do certificado do API server. `insecure-skip-tls-verify: true` dentro do kubeconfig reabre o problema.

---

## Credenciais

### O que está correto

- Segredos via ambiente: `EDGE_FUNCTIONS_URL/KEY`, `EVOLUTION_URL/KEY`, `WEBHOOK_TOKEN` (`application.properties`).
- K8s usa `secretKeyRef` em `backend-secrets`, inclusive `WEBHOOK_TOKEN`.
- CI usa `${{ secrets.DOCKER_USERNAME }}`, `${{ secrets.DOCKER_PASSWORD }}` e `${{ secrets.KUBE_CONFIG }}`.
- Não há padrões de chave (`sk-`, `ghp_`, JWTs, etc.) nos arquivos rastreados.
- `.gitignore` inclui `*.env`.

### Identificadores públicos (não são secretos por si)

- Referência do projeto Supabase na URL default de `edge-functions.url`.
- Host `api.dariodussin.com.br`.

---

## O que está razoável

- Admin vem da Evolution (`isAdmin`, `isSuperAdmin` ou `admin` = `admin`/`superadmin`), aplicado ao participante casado, e a remoção usa o JID desse registro.
- Ignora `fromMe: true`, JID que não é grupo, remetente fora da lista e admin.
- Grupo precisa estar ligado à campanha guardiã; a `instance` do payload precisa ser a guardiã; a remoção sai por outra instância admin conectada.
- Gatilhos: link, mensagem template, ou grupo fechado (`GroupInfo.announce`).
- Saída Evolution/Rise usa `WebClient` com base URL fixa — sem SSRF a partir de campos do webhook.
- Records com `@JsonIgnoreProperties(ignoreUnknown = true)`.

---

## Plano restante

1. Rotacionar `WEBHOOK_TOKEN` se o stdout de produção anterior à remoção do `println` já foi retido.
2. Opcional: allowlist de IP da Evolution no Ingress, quando os ranges de origem forem conhecidos.
3. Opcional: só moderar quando todos os aliases da key apontarem para o mesmo participante, e apagar a mensagem com o JID canônico.
