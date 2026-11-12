# Group Chats Implementation Plan

**Goal:** исправить перекрытие чата и добавить работающие групповые переписки.

**Architecture:** подписанные ревизии группы поверх существующих многоадресных
сообщений; независимая доставка через парные каналы. Управление группой и очередь
отделены от Android-интерфейса и проверяются несколькими независимыми профилями.

**Tech Stack:** Kotlin, Jetpack Compose, Android SQLite, AES-GCM, существующие crypto/transport.

**Spec:** `docs/superpowers/specs/2026-11-12-group-chats-design.md`.

Выполнение: последовательно в текущем сеансе.

## Global Constraints

- Android 8.0+, без новых сетевых служб и зависимостей.
- Сохранить applicationId, ключ подписи, пользовательские профили и историю.
- Не менять `docs/TODO.md`.
- Обычные payload без служебного расширения должны сохранять прежние байты.
- Не удалять файлы доставки группы после получения только одним участником.

## Tasks

- [x] **1. Геометрия чата.** `ChatsScreen.kt`: добавить `onWrite: (() -> Unit)?`,
  разместить кнопку под списком с `weight(1f)`. `EidoHomeApp.kt`: убрать FAB чата,
  передать callback. Проверка: `ChatLayoutTest.lastMessageStaysAboveWriteButton`.
- [x] **2. Подписанный состав.** `core-message/GroupConversation.kt`:
  `GroupStateV1`, `GroupMemberV1`, `GroupStateCodec.issue/parse/verifyChain`,
  `GroupControlV1` и канонический codec. Проверки подписи, смены владельца,
  номера ревизии, предыдущего хеша, состава и размера данных.
- [x] **3. Служебный payload.** `EidoMessagePayload.kt`, `MessageCrypto.kt`,
  `MessengerService.kt`: необязательный `controlCanonical: String? = null`,
  доступ к расшифрованному `TimelineItem.controlCanonical`. Сравнить старые
  канонические векторы, проверить сообщение с расширением и чужого получателя.
- [x] **4. Сервис групп.** `core-repository/GroupChatService.kt` и
  `GroupConversationStore.kt`: `create`, `update`, `leave`, `send`, `consume`,
  `pending`, `stage`, `published`, `timeline`. Очередь привязана к неизменяемому
  controlId, повторное получение не дублирует сообщения. JVM-тест трёх профилей
  передаёт реальные подписанные зашифрованные конверты через независимые репозитории.
- [x] **5. Android и копии.** `AndroidGroupConversationStore.kt`: AtomicFile и
  AndroidLocalSecretBox, namespace `group-chat`, каталог `group-chats`. Включить
  каталог в watchedDirectories, backup allowlist и восстановление namespace.
  `GroupDelivery.kt`: подготовка визиток, повтор очереди, публикация парных каналов,
  обработка новых служебных конвертов после `receiveFrom`.
- [x] **6. Интерфейс.** `GroupChatScreens.kt`, `ChatsScreen.kt`, `EidoHomeApp.kt`:
  создание, участники, управление, выход, имя отправителя, отправка в открытый
  conversationId группы, пустые группы в списке, скрытие служебных сообщений.
- [x] **7. Проверка поставки.** JVM-тесты core-message/core-repository, Android
  instrumented tests на отдельном эмуляторе, сборка подписанного APK и проверка
  сертификата. README описывает реальные возможности и условия доставки. Проверить
  diff и отсутствие изменений пользовательских данных. Публикация — отдельное действие.
