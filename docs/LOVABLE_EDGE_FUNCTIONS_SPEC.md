# Especificações da API Externa — RISE

Documento de integração para o worker Java. Base URL: `EDGE_FUNCTIONS_URL` (default `https://xcufkvbnqshrpkdlohka.supabase.co/functions/v1`). Header: `x-api-token: EDGE_FUNCTIONS_KEY`.

## GET /worker-jobs

Query: `status` (default `pending`), `limit` (default 100).

Filtro de data é feito na Edge Function (`scheduled_at` entre 1º de janeiro do ano corrente e agora UTC).

Retorno: array de jobs (`id`, `campaign_id`, `scheduled_at`, `type`, `status`, `error_message`, `created_at`, `updated_at`, `processed_at`, `metadata`).

## PATCH /worker-jobs

Body: `{ "id", "status", "error_message?" }`. Status: `pending` | `executing` | `completed` | `error`.

Retorno: `{ "ok": true }`.

## GET /worker-instance?campaign_id=

Retorno: `[{ "instance_name": "..." }]`.

## GET /worker-groups?campaign_id=

Retorno: `[{ "group_id": "...@g.us" }]`.
