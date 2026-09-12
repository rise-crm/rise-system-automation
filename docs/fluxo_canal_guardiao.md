# Fluxo canal guardião

## Duas instâncias no mesmo grupo

- **Guardião** — detecta mensagem/spam (sem permissões)
- **Admin** — bane golpista e remove mensagem (com permissões)

1. Guardião (campanha) tem relacionamento com grupo que recebe mensagem
2. Guardião detecta golpe e/ou spam de mensagem por webhook.
3. Sistema encontra o admin usando o relacionamento:

   ```
   message.data.key.remoteJid = db:campaign_groups.group_id
     → campaign_id = db:whatsapp_instances.campaign_id
     → instance_name
   ```

4. Sistema usa rotas da Evolution com o `instance_name` do admin para banir o golpista e remover a mensagem.

## Rotas

- `/group/updateParticipant/{instanceName}?groupJid={groupJid}`
- `/chat/deleteMessageForEveryone/{instanceName}`

## Checagens

- Instância admin é encontrada
- `connectionStatus` da instância admin
- Instância admin não é guardião
- Retorno de erro de ação da instância (permissão no grupo)

## Regras

- **Spam:** 10 mensagens em menos de 1 minuto
- Detectar em mensagem tipo template / com botão
- Sempre apague a mensagem **primeiro**, depois expulse o participante
- Lock temporário no Redis usando `groupJid` + `participantJid` com TTL de 5 segundos, para processar apenas o primeiro disparo e descartar as requisições duplicadas
