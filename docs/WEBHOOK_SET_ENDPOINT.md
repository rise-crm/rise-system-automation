# `POST /webhook`: configurar o webhook de uma instância da Evolution

Configura o webhook de uma instância da Evolution API para enviar os eventos de mensagem para este backend. A rota devolve o token da instância.

## Requisição

**URL:** `https://api.dariodussin.com.br/webhook`
**Método:** `POST`

### Headers

| Header | Obrigatório | Valor |
|---|---|---|
| `Content-Type` | sim | `application/json` |
| `apikey` | sim | Chave global da Evolution API (`EVOLUTION_KEY`). O token de uma instância não é aceito aqui. |

### Corpo (JSON)

| Campo | Tipo | Obrigatório | Descrição |
|---|---|---|---|
| `instanceName` | string | sim | Nome exato da instância na Evolution, por exemplo `campaign-canal-guardiao-d68010d7`. Não pode ser vazio. |

Outros campos no corpo são ignorados. Um `apikey` enviado no corpo não autentica.

### Exemplo

```bash
curl -X POST https://api.dariodussin.com.br/webhook \
  -H 'Content-Type: application/json' \
  -H 'apikey: <EVOLUTION_KEY>' \
  -d '{"instanceName": "campaign-canal-guardiao-d68010d7"}'
```

## Respostas

| Status | Corpo | Quando |
|---|---|---|
| `200` | `{"instanceName": "...", "token": "..."}` | Webhook configurado. `token` é o token da instância na Evolution. |
| `400` | vazio | `instanceName` ausente ou vazio, ou JSON inválido. |
| `401` | vazio | Header `apikey` ausente ou diferente da `EVOLUTION_KEY`. |
| `404` | vazio | A instância não existe na Evolution. Nenhum webhook é configurado. |
| `413` | vazio | Corpo maior que 1 MB. |
| `502` | vazio | A Evolution API retornou erro ao buscar a instância ou ao configurar o webhook. |

### Exemplo de resposta 200

```json
{
  "instanceName": "campaign-canal-guardiao-d68010d7",
  "token": "00000000-0000-0000-0000-000000000000"
}
```

## Comportamento

- **Fixo no servidor:** o cliente não escolhe URL nem eventos. O backend sempre configura a instância com:
  - `enabled: true`
  - `url: https://api.dariodussin.com.br/webhook/message-check` (`{BASE_URL}/webhook/message-check`)
  - `events: ["SEND_MESSAGE", "MESSAGES_UPSERT", "MESSAGES_UPDATE", "MESSAGES_DELETE"]`
  - `headers: {}`
  - `base64: true`
- **Pode repetir:** chamar de novo para a mesma instância apenas reaplica a mesma configuração.
- **Token sensível:** o `token` devolvido é um segredo da instância. Não grave em log nem exponha a usuários finais.
