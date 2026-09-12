# Revisão de segurança

Revisão da codebase (incluindo o webhook de checagem de mensagens) com o repositório público no GitHub. Foco: credenciais, autenticação e caminhos de ataque reais.

## Resumo

| Severidade | Local | Problema |
|---|---|---|
| Critical | `WebhookService.java` (~85–97) | Checagem de admin usa um JID e o ban usa outro (`participantAlt` / `participant`). Dá para remover admin forjando o payload. |
| High | `WebhookController.java` (~22–24) | `POST /webhook/message-check` sem autenticação; o Ingress em `api.dariodussin.com.br` expõe `/`. Qualquer um pode disparar remoção via Evolution. |
| Medium | `EvolutionWebhookPayload.java` (~16) | Campo `apikey` existe e nunca é comparado com `EVOLUTION_KEY`. |
| Medium | `WebhookController.java` + `EvolutionApiService.java` | Webhook anônimo dispara chamadas Evolution (lista participantes + remove) → abuso/DoS. |

**Prioridade imediata:** autenticar o webhook, remover só o JID canônico do participante encontrado na Evolution, restringir o Ingress. Rotacionar `EVOLUTION_KEY` e `EDGE_FUNCTIONS_KEY` se o `.env` tiver vazado fora do Git.

---

## 1. Critical — Bypass de admin e remoção indevida

**Arquivos:** `src/main/java/com/dariodussin/whatsappautomationbackend/service/WebhookService.java`

**Impacto:** um chamador sem autenticação pode remover **admins** de grupos geridos pela Evolution, não só membros comuns.

**Caminho de ataque:**

1. `POST` em `https://api.dariodussin.com.br/webhook/message-check` sem auth (Ingress em `k8s/network.yaml` publica `/`).
2. Forjar `messages.upsert` com `MessageKey` controlada:
   - `participant` = JID de um membro **não-admin** (precisa existir no grupo).
   - `participantAlt` = JID de um **admin**.
   - `remoteJid` = grupo `@g.us`, `fromMe: false`, texto com link.
3. `findParticipant()` casa o não-admin via `participant` → `sender.isGroupAdmin()` é falso → o ban segue.
4. `removeGroupParticipant()` usa `contactJid` de `resolveContactJid()`, que **prefere `participantAlt`** → o admin é removido.

**Correção:**

- Autenticar o webhook (item 2).
- Remover usando o JID canônico do `GroupParticipant` encontrado (ex.: `sender.jid()`), não aliases crus do webhook.
- Exigir que `resolveContactJid(key)` corresponda ao participante encontrado antes de remover.

---

## 2. High — Webhook sem autenticação

**Arquivos:** `WebhookController.java`, `WebhookService.java`

**Impacto:** qualquer cliente na Internet pode disparar remoção automática de membros não-admin nos grupos das instâncias alcançáveis com a API key da Evolution no backend.

**Caminho de ataque:**

1. Descobrir ou adivinhar o nome da `instance`.
2. Conhecer `groupJid` e o JID da vítima.
3. Enviar payload forjado com link em `message.conversation`, `fromMe: false`.
4. O backend chama a Evolution com o header privilegiado `apikey` (`EvolutionClientConfig`) e remove a vítima.

Não há Spring Security, filtro nem validação no endpoint. `EvolutionWebhookPayload.apikey` nunca é checado.

**Correção:**

- Validar `payload.apikey()` (comparação em tempo constante) contra `${evolution.key}`, e/ou HMAC/assinatura da Evolution se existir.
- Restringir Ingress (allowlist de IP, mTLS, ou Service interno sem Ingress público).
- Respostas genéricas; não confirmar validade de instance/grupo para anônimos.

---

## 3. Medium — Campo `apikey` não usado

**Arquivos:** `EvolutionWebhookPayload.java`, `WebhookController.java`

**Impacto:** a autenticação por segredo compartilhado não é aplicada; o endpoint permanece aberto mesmo se a Evolution enviar `apikey` nos callbacks.

**Correção:** rejeitar requests com `apikey` nulo ou diferente de `evolution.key` **antes** de processar.

---

## 4. Medium — Amplificação na Evolution API

**Arquivos:** `WebhookController.java`, `EvolutionApiService.java`

**Impacto:** cada POST anônimo pode gerar HTTP bloqueante na Evolution (`GET` de participantes e, em seguida, `POST` de remoção), pressionando a ponte WhatsApp (custo/DoS).

**Correção:** auth + rate limit em `/webhook/**`; processar de forma assíncrona com fila e cotas por IP/instância.

---

## Credenciais

### O que está correto

- Código Java carrega secrets de ambiente (`application.properties`).
- K8s usa `secretKeyRef` em `backend-secrets` (`k8s/deployment.yaml`).
- CI usa `${{ secrets.DOCKER_USERNAME }}`, `${{ secrets.DOCKER_PASSWORD }}` e `${{ secrets.KUBE_CONFIG }}`.
- Não há padrões de chave (`sk-`, `ghp_`, JWTs, etc.) nos arquivos rastreados pelo Git.

### `.env` local

- `.gitignore` inclui `*.env`.
- O histórico Git **não** contém `.env` (`git log --all -- .env` vazio).
- O `.env` na máquina local pode ter `EDGE_FUNCTIONS_KEY` e `EVOLUTION_KEY` reais: não commitar, não colar em issues/PRs/logs de CI.

### Identificadores públicos (não são secretos por si)

- Referência do projeto Supabase na URL default (`application.properties` e docs).
- Host público `api.dariodussin.com.br`.

---

## Outros riscos no repositório público

| Item | Risco | Notas |
|---|---|---|
| IP do cluster em CI | Médio (config) | `.github/workflows/deploy.yml` usa `https://213.136.92.71:6443` e `--insecure-skip-tls-verify=true`, o que enfraquece a validação TLS kubectl → cluster. |
| Actuator | Baixo | Starter no classpath sem `management.endpoints.web.exposure.include` explícito. Confirmar que produção só expõe endpoints seguros. |
| Logs do webhook | Baixo/operacional | `System.out.printf` imprime instance, JIDs e trecho da mensagem (`preview`). Risco se logs forem públicos ou retidos demais. |
| Ingress | Alto (exposição) | `k8s/network.yaml` roteia `/` para o backend; `/webhook/message-check` fica na Internet. |

---

## O que está razoável no webhook

- Status de admin do participante **casado** vem da Evolution (`GroupParticipant.isGroupAdmin()`), não de campos de admin no webhook — a intenção é boa, mas o mismatch de JID (item 1) a anula.
- Ignora `fromMe: true`, JIDs que não são grupo, mensagens sem link, ou remetente fora da lista de participantes.
- Chamadas de saída da Evolution usam `WebClient` com URL base fixa — sem SSRF a partir de campos do webhook.
- Records Jackson com `@JsonIgnoreProperties` — sem desserialização insegura óbvia no código revisado.

---

## Plano de correção sugerido

1. Autenticar `/webhook/message-check` (apikey / assinatura).
2. Remover apenas o JID canônico do participante validado na Evolution.
3. Rate limit + respostas genéricas.
4. Ingress só no que precisa ser público (ou allowlist da Evolution).
5. Tirar `--insecure-skip-tls-verify` do deploy e não publicar IP do API server se não for necessário.
6. Rotacionar chaves se houver qualquer suspeita de vazamento fora do Git.
7. Reduzir PII nos logs de webhook.
