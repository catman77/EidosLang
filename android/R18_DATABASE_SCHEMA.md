# R18 local database schema

Database: `eidolang-messenger-r18.db`

## contacts

Primary key: `(user_id, device_id)`

Fields:
- `user_id`
- `device_id`
- `alias` — local presentation metadata
- `bundle_json` — canonical verified public identity bundle
- `imported_at_ms`

A refreshed valid certificate proof for the same user/device replaces `bundle_json` rather
than creating a second contact device.

## conversations

Primary key: `conversation_id`

Fields:
- `conversation_id`
- `descriptor_json` — canonical conversation descriptor
- `title` — local presentation metadata
- `created_at_ms`

## messages

Primary key: `message_id`

Unique key:
`(conversation_id, sender_device_id, sender_seq)`

Fields:
- `message_id`
- `conversation_id`
- `sender_user_id`
- `sender_device_id`
- `sender_seq`
- `created_at_ms`
- `parent_ids`
- `envelope_json` — canonical encrypted/signed R15 envelope
- `direction`
- `local_state`
- `stored_at_ms`

No decrypted eidogram document column exists.
