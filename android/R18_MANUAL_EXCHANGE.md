# R18 manual two-device exchange protocol

This protocol is a temporary operational path while real BitTorrent execution is ignored.
It uses the same cryptographic objects that the final transport will carry.

## Bootstrap

On phone A:
- Contacts -> `Мой контакт` -> export JSON.

On phone B:
- Contacts -> `Импорт` -> import A JSON.
- Export B public contact.

On phone A:
- Import B public contact.

## Conversation creation

On A:
- Contacts -> choose B -> `Диалог`.
- In the conversation -> `Диалог` -> export canonical conversation descriptor.

On B:
- home -> `Импорт диалога` -> import the descriptor.

Both devices now have the same `ConversationId`.

## Message A -> B

On A:
- open conversation;
- `+`;
- compose eidogram from frozen glyph catalog;
- `Отправить`;
- `Экспорт` -> export latest outgoing encrypted message JSON.

On B:
- open same conversation;
- `Импорт` -> select message JSON.

B performs R15 authentication/decryption and R18 DAG/replay admission before displaying the
eidogram.

## Reply B -> A

Repeat the same process in the opposite direction. The reply automatically references A's
current message head.

## Important

This is not a substitute for BitTorrent. It is a no-network carrier for the exact objects that
R16/R17 later distribute.
