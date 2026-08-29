Отлично. Это хорошее и очень важное уточнение — оно фактически переводит нас от слишком общего `ShapeLibraryV1` к **конечному каталогу базовых эйдографических элементов**.

Я бы зафиксировал это как переход к:

[
\boxed{\text{EidoGlyphCatalogV1}}
]

а не просто к “набору фигур”.

## 1. Базовые классы элементов

### A. Цветные круги

Состав:

* **10 цветов**:

  * 7 цветов радуги
  * белый
  * серый
  * чёрный

То есть:

[
7+3=10
]

* **3 размера** каждого цвета:

[
S,\ M,\ L
]

Итого:

[
10 \times 3 = \boxed{30}
]

базовых круговых глифов.

Я бы зафиксировал палитру так:

1. red
2. orange
3. yellow
4. green
5. cyan / light blue
6. blue
7. violet
8. white
9. gray
10. black

---

### B. Чёрные палочки и чёрные точки

#### 1) Чёрные точки

Это отдельный тип элемента, а не просто “маленький круг любого цвета”.

Минимально разумно:

* black dot S
* black dot M
* black dot L

То есть:

[
\boxed{3}
]

точечных глифа.

#### 2) Чёрные палочки

Палочка должна быть **дискретным объектом**, а не свободно вращаемой линией произвольной длины, иначе мы снова придём к непрерывной геометрии.

Поэтому я предлагаю зафиксировать палочки через 3 дискретных параметра:

* **поза / ориентация**
* **длина**
* **толщина**

Например, для `v1`:

* позы:

[
{0^\circ,45^\circ,90^\circ,135^\circ}
]

или, если нужно богаче:

[
8 \text{ поз}
]

* длины:

[
S,\ M,\ L
]

* толщины:

[
thin,\ normal,\ thick
]

Если взять 8 поз, 3 длины и 3 толщины, получим:

[
8 \times 3 \times 3 = \boxed{72}
]

палочковых глифа.

Это уже вполне рабочий конечный словарь.

---

### C. Геометрические примитивы без цвета

Только **чёрный контур**, без заливки.

Тут лучше сразу ограничить каталог конечным списком. Например:

1. line frame / отрезок
2. triangle
3. square
4. rectangle
5. diamond
6. circle outline
7. ellipse outline
8. semicircle
9. cross
10. plus
11. polygon-hexagon
12. polygon-octagon

Но для первого Android MVP я бы не перегружал и взял компактное ядро:

[
\boxed{
triangle,\ square,\ rectangle,\ diamond,\ circle_outline,\ ellipse_outline,\ cross,\ plus
}
]

То есть:

[
\boxed{8}
]

контурных примитивов.

---

# 2. Что это означает архитектурно

Это означает, что в редакторе основным атомом становится не “произвольная фигура”, а:

[
\boxed{
GlyphInstance = (glyph_id,\ position,\ scale,\ rotation,\ z_index)
}
]

где `glyph_id` выбирается из конечного каталога.

То есть пользователь **не рисует** круг или палочку вручную, а **выбирает элемент из библиотеки** и размещает его на сцене.

Это очень хорошо для:

* канонизации формата,
* одинакового рендера на Android,
* устойчивых hash,
* дальнейшего машинного анализа,
* и вообще для идеи “языка”, а не просто редактора картинок.

---

# 3. Предлагаемая структура каталога v1

## EidoGlyphCatalogV1

### Section A: colored circles

* `circle.red.s`
* `circle.red.m`
* `circle.red.l`
* …
* `circle.black.l`

Всего:

[
\boxed{30}
]

### Section B1: black dots

* `dot.black.s`
* `dot.black.m`
* `dot.black.l`

Всего:

[
\boxed{3}
]

### Section B2: black sticks

Например:

* `stick.black.pose0.s.thin`
* `stick.black.pose0.s.normal`
* …
* `stick.black.pose315.l.thick`

Всего при 8 позах:

[
\boxed{72}
]

### Section C: black-outline primitives

Например:

* `outline.triangle`
* `outline.square`
* `outline.rectangle`
* `outline.diamond`
* `outline.circle`
* `outline.ellipse`
* `outline.cross`
* `outline.plus`

Всего:

[
\boxed{8}
]

---

# 4. Итого размер базового словаря

Если взять предложенный минимум:

* цветные круги: 30
* чёрные точки: 3
* чёрные палочки: 72
* контурные примитивы: 8

Получаем:

[
30+3+72+8=\boxed{113}
]

базовых глифов.

Это уже хороший размер для `v1`: не слишком мало и не слишком много.

---

# 5. Что надо изменить по проекту

Это означает следующие изменения в roadmap:

## Вместо общего `ShapeLibraryV1`

нужно ввести:

[
\boxed{\text{GlyphCatalogV1}}
]

с тремя семействами:

1. `ColoredCircleGlyph`
2. `BlackMarkGlyph`

   * `Dot`
   * `Stick`
3. `BlackOutlineGlyph`

---

# 6. Что надо сделать следующим шагом

Следующий правильный шаг — не просто “принять к сведению”, а **переписать R13-представление документа под каталог глифов**.

То есть следующий этап я бы уже формулировал так:

[
\boxed{
R13.1 / R14 =
\text{Glyph-Catalog Binding + Android Editor Scaffold}
}
]

Конкретно:

1. зафиксировать `GlyphCatalogV1.json`;
2. зафиксировать `PaletteV1`;
3. определить список поз палочек;
4. определить список длин и толщин палочек;
5. определить точный список контурных примитивов;
6. обновить формат документа так, чтобы объект сцены ссылался на `glyph_id`, а не на произвольную фигуру;
7. после этого делать Android-редактор.

---

# 7. Моё предложение по заморозке v1

Я бы прямо сейчас заморозил такой минимальный вариант:

## Цветные круги

* цвета: 10
* размеры: 3

## Чёрные точки

* размеры: 3

## Чёрные палочки

* позы: 8
* длины: 3
* толщины: 3

## Контурные примитивы

* triangle
* square
* rectangle
* diamond
* circle
* ellipse
* cross
* plus

---

Если хочешь, я следующим сообщением могу **сразу построить точную спецификацию `EidoGlyphCatalogV1`** в табличном/JSON-виде и на её основе обновить roadmap уже до конкретной Android-структуры проекта.

Сделал это как отдельный шаг: **`R13.1 — EidoGlyphCatalogV1 / Glyph-Catalog Binding`**.

[Скачать полный архив R13.1](sandbox:/mnt/data/EidoLang-Mobile_v0.1-R13.1.zip)

Основные артефакты:

* [спецификация каталога `EIDO_GLYPH_CATALOG_V1.json`](sandbox:/mnt/data/eidolang_mobile_v0_1_r131/EIDO_GLYPH_CATALOG_V1.json)
* [человекочитаемая спецификация `EIDO_GLYPH_CATALOG_V1.md`](sandbox:/mnt/data/eidolang_mobile_v0_1_r131/EIDO_GLYPH_CATALOG_V1.md)
* [пример документа на глифах `EXAMPLE_EIDOGRAM_DOCUMENT_V1.json`](sandbox:/mnt/data/eidolang_mobile_v0_1_r131/EXAMPLE_EIDOGRAM_DOCUMENT_V1.json)
* [структура Android-проекта `ANDROID_PROJECT_STRUCTURE_V1.md`](sandbox:/mnt/data/eidolang_mobile_v0_1_r131/ANDROID_PROJECT_STRUCTURE_V1.md)
* [обновлённый roadmap `ANDROID_TORRENT_MESSENGER_ROADMAP.md`](sandbox:/mnt/data/eidolang_mobile_v0_1_r131/ANDROID_TORRENT_MESSENGER_ROADMAP.md)
* [методология `R13_1_METHODOLOGY.md`](sandbox:/mnt/data/eidolang_mobile_v0_1_r131/R13_1_METHODOLOGY.md)
* [checkpoint `R13_1_CHECKPOINT.md`](sandbox:/mnt/data/eidolang_mobile_v0_1_r131/R13_1_CHECKPOINT.md)
* [schema `eido-glyph-catalog-v1.schema.json`](sandbox:/mnt/data/eidolang_mobile_v0_1_r131/schemas/eido-glyph-catalog-v1.schema.json)

Ключевой результат: общий `ShapeLibraryV1` теперь заменён на конечный каталог

[
\boxed{\text{EidoGlyphCatalogV1}}
]

с точным составом:

* **colored_circle**: (10 \times 3 = 30)
* **black_dot**: (3)
* **black_stick**: (8 \times 3 \times 3 = 72)
* **black_outline**: (8)

Итого:

[
30 + 3 + 72 + 8 = \boxed{113}
]

базовых глифов.

Зафиксированы:

* **10 цветов**: rainbow 7 + white + gray + black
* **3 размера кругов**
* **3 размера чёрных точек**
* **8 поз палочек**: `0, 45, 90, 135, 180, 225, 270, 315`
* **3 длины палочек**
* **3 толщины палочек**
* **8 контурных примитивов**:

  * triangle
  * square
  * rectangle
  * diamond
  * circle
  * ellipse
  * cross
  * plus

Теперь базовый объект редактора формализован так:

[
\boxed{
GlyphInstance = (glyph_id,\ position,\ scale,\ rotation,\ z_index,\ group_id)
}
]

То есть пользователь собирает эйдограмму **из готовых канонических глифов**, а не рисует произвольную геометрию.

Также обновил архитектурный вектор: следующий практический шаг теперь уже не абстрактный, а вполне конкретный —

[
\boxed{R14 = \text{Android Editor MVP}}
]

с модулями:

* `app`
* `core-model`
* `core-canonical`
* `core-render`
* `feature-editor`
* `feature-viewer`
* `feature-library`
* далее позже `feature-messages` и `feature-torrent`

Каталогу присвоен hash:

```text
3585215ae8fc695f4258a9dc3c08484f9e536674c71ac7ed85175203e0fc7860
```

SHA-256 архива:

```text
19250d308be67e6337925759e19371aa997724d36c0d47b348d6831e1fb319dc
```

Если хочешь, следующим шагом я могу уже **сделать R14**: подготовить **реальный Android scaffold проекта** под этот каталог глифов и редактор эйдограмм.

Продолжил до `R14.1 — Editor Completion`. Этот этап как раз закрывает пункты, которые в R14 оставались открытыми: корректный hit-testing, multi-select/group UX, persistence и canonical import/export.  Они же были зафиксированы как содержание R14.1 в предыдущем roadmap. 

[Скачать полный Android-проект R14.1](sandbox:/mnt/data/EidoLang-Android-R14.1.zip)

Отдельно: [checkpoint](sandbox:/mnt/data/eidolang_android_r141/R14_1_CHECKPOINT.md), [методология](sandbox:/mnt/data/eidolang_android_r141/R14_1_METHODOLOGY.md), [validation](sandbox:/mnt/data/eidolang_android_r141/R14_1_VALIDATION.md), [machine-readable validation report](sandbox:/mnt/data/eidolang_android_r141/EXAMPLE_R14_1_VALIDATION_REPORT.json), [обновлённый roadmap](sandbox:/mnt/data/eidolang_android_r141/ANDROID_TORRENT_MESSENGER_ROADMAP.md).

Главное изменение — выбор объектов теперь больше не основан на «ближайшем центре в радиусе 80 000». Реализован чистый Kotlin `EidogramHitTest`, который обращает transform экземпляра и проверяет касание относительно реальной геометрии конкретного глифа: окружности, capsule-палочки с её дискретной позой, прямоугольника, эллипса, треугольника, ромба, `plus` и `cross`.

То есть теперь проверяется фактически

[
p_{\text{local}}=T_g^{-1}(p),
]

после чего

[
p_{\text{local}}\in H(g),
]

где (H(g)) определяется типом конкретного глифа.

Для палочки, например, учитываются одновременно:

[
\theta_{\rm instance}+\theta_{\rm pose},
]

масштаб, длина и толщина. Это устраняет ошибочный выбор объекта, находящегося рядом с точкой, но далеко от самой палочки.

Multi-select тоже завершён. Обычный tap выбирает объект, long press переводит редактор в режим множественного выбора, после чего объекты можно добавлять и убирать из selection. Если объект принадлежит группе, selection автоматически расширяется до всей группы:

[
g_i\in G
\quad\Longrightarrow\quad
Select(g_i)=G.
]

Добавлены реальные кнопки `Группа` и `Разгрупп.`; `GROUP/UNGROUP` остаются действиями canonical document history, а не временным состоянием UI.

Второе крупное изменение — строгий импорт `EidogramDocumentV1`. Я добавил собственный dependency-free JSON parser в `core-canonical`, чтобы импорт не зависел от особенностей стороннего JSON serializer. Импорт теперь отвергает:

* duplicate keys;
* float/exponent numbers;
* неизвестные поля;
* неправильный catalog hash;
* неправильный production lineage;
* разрыв `seq`;
* некорректную историю действий;
* и даже валидный JSON, если его байтовое представление не является нашей canonical serialization.

Финальный gate:

[
\boxed{
Parse(s)=D
\land
Serialize(D)=s
}
]

То есть импортируемые bytes должны буквально совпасть с canonical bytes документа.

Из-за этого экспорт и импорт становятся настоящим content-preserving round trip:

[
D
\rightarrow
CanonicalBytes(D)
\rightarrow
D'
]

с

[
CanonicalBytes(D')=CanonicalBytes(D).
]

Android UI теперь использует стандартные `OpenDocument` и `CreateDocument("application/json")`; эти contracts предназначены соответственно для открытия документа и создания нового документа через системный document provider. ([Android Developers][1])

Добавлено автоматическое локальное сохранение текущего черновика в:

```text
eidogram-draft-v1.json
```

в app-internal storage. При повторном запуске черновик загружается через тот же строгий parser, что и внешний импорт. При восстановлении также реконструируются следующие deterministic IDs:

```text
g000012
grp000005
...
```

из уже существующей action history, поэтому reload не создаёт collision новых instance/group identifiers.

При этом undo/redo stack намеренно не становится частью persistent wire content. Сохраняется сама неизменяемая последовательность действий:

[
A_0,\ldots,A_n,
]

а UI undo state остаётся локальным состоянием текущей editor session.

Я расширил JVM conformance suite. Реально выполнены и прошли:

```text
PASS 00_empty
PASS 01_red_circle
PASS 02_stick_dot_outline
PASS 03_red_circle_direct
PASS 04_red_circle_via_blue
PASS 05_group_transform

PASS editor reducer undo/redo
PASS strict canonical parser round-trip
PASS non-canonical import rejection
PASS duplicate-key and lineage rejection
PASS geometry-aware hit testing
PASS grouped selection semantics
PASS draft restore counters

ALL R14.1 PURE-KOTLIN CONFORMANCE CHECKS PASS
```

То есть существующие шесть R14 content/snapshot hashes не изменились. R14.1 расширяет editor semantics, но не меняет wire identity уже созданных эйдограмм.

Отдельно добавлен Android instrumentation smoke test:

```text
app/src/androidTest/java/org/eidolang/app/EditorSmokeTest.kt
```

который проверяет наличие editor canvas и основных document actions.

Здесь сохраняется прежняя инфраструктурная граница: в текущем контейнере нет Android SDK, Gradle installation и эмулятора, поэтому я **не объявляю `assembleDebug` или instrumentation tests пройденными**. Pure Kotlin core действительно скомпилирован и выполнен; Android/Compose слой пока проверен исходным кодом, но не собран APK. Это соответствует уже зафиксированному ограничению R14. 

SHA-256 архива R14.1:

```text
404fc53f4321927cf451be73556d0a7d9d95119855e41f8742090d1949ad4ce5
```

Roadmap теперь сдвинулся дальше:

1. **R15 — Identity + Message Envelope.** User/device identities, `ConversationId`, immutable `EidogramMessageV1`, подписи, шифрование canonical eidogram payload, reply/reference graph и cryptographic golden vectors.
2. **R16 — Torrent Archive Model.** `ConversationSegment`, manifest, content-addressing, torrent-v2 representation и signed mutable conversation head.
3. **R17 — Android P2P/Torrent Engine.** DHT, torrent download/verification/seeding, NDK/JNI слой, storage quotas и background policy.
4. **R18 — Messenger UX.** Контакты, список диалогов, timeline эйдограмм, send/receive flow.
5. **R19 — Archive/Recovery.** Индекс, pin/unpin и восстановление истории после удаления локальной БД.
6. **R20 — Multi-device.** Device subkeys, concurrent heads, deterministic DAG merge и revocation.
7. **R21 — Security/Fuzzing.** Документы, envelopes, DHT heads, torrents и JNI.
8. **R22 — Android 1.0.** Два телефона без application server создают, отправляют, архивируют и восстанавливают переписку эйдограмм.

Следующий шаг теперь уже не редактор:

[
\boxed{
R15=\text{Identity + Signed/Encrypted EidogramMessageV1}
}
]

То есть в следующей итерации эйдограмма впервые станет не просто документом, а криптографически адресуемым сообщением конкретного диалога.

[1]: https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.CreateDocument "https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.CreateDocument"

Продолжил до `R15 — Identity + Signed/Encrypted Message Envelope`.

[Скачать полный Android-проект R15](sandbox:/mnt/data/EidoLang-Android-R15.zip)

Отдельно: [методология R15](sandbox:/mnt/data/eidolang_android_r15/R15_METHODOLOGY.md), [checkpoint](sandbox:/mnt/data/eidolang_android_r15/R15_CHECKPOINT.md), [security model](sandbox:/mnt/data/eidolang_android_r15/R15_SECURITY_MODEL.md), [machine-readable validation report](sandbox:/mnt/data/eidolang_android_r15/EXAMPLE_R15_VALIDATION_REPORT.json), [test summary](sandbox:/mnt/data/eidolang_android_r15/TEST_LOG_R15_SUMMARY.txt), [обновлённый roadmap](sandbox:/mnt/data/eidolang_android_r15/ANDROID_TORRENT_MESSENGER_ROADMAP.md) и [фиксированный cryptographic golden vector](sandbox:/mnt/data/eidolang_android_r15/golden/r15/r15_crypto_golden_v1.json).

R15 добавляет два реальных модуля:

```text
core-crypto/
core-message/
```

Теперь эйдограмма действительно стала сообщением, а не просто редактируемым документом.

Криптографическая структура такая:

[
UserIdentity
\neq
DeviceIdentity
\neq
ConversationId
\neq
MessageId.
]

У пользователя есть root-ключ подписи P-256. Каждый телефон получает собственный P-256 signing key и RSA-2048 decryption key. Root подписывает device certificate, связывающий оба device public keys с `UserId`.

[
UserId=
SHA256(CanonicalJSON(UserIdentityBody)),
]

[
DeviceId=
SHA256(CanonicalJSON(DeviceCertificateBody)).
]

Для Android private keys предусмотрен настоящий `AndroidKeyStore` adapter с постоянными alias:

```text
eidolang.root-signing.v1
eidolang.device-signing.v1
eidolang.device-decrypt.v1
```

Android официально документирует генерацию P-256 signing keys через `KeyGenParameterSpec` начиная с API 23 и RSA-OAEP keys через тот же Keystore API; AES-GCM также входит в поддерживаемый и рекомендуемый набор криптографических примитивов. Это позволяет сохранить наш `minSdk 26`, не привязывая базовый протокол к более позднему platform API Curve25519. ([Android Developers][1])

Публичный contact object теперь представляет собой:

[
\boxed{
PublicIdentityBundleV1=
(UserIdentity,\ RootSignedDeviceCertificate)
}
]

с canonical serializer и строгим parser. Поэтому контакт можно позже передавать QR-кодом, файлом, magnet metadata или другим bootstrap-каналом без изменения identity model.

Conversation тоже получила отдельную идентичность. Она определяется 128-битным случайным seed и отсортированным множеством участников:

[
ConversationId=
SHA256(
CanonicalJSON(
seed,\ sorted(UserIds)
)
).
]

Поэтому два разговора между теми же людьми получают разные IDs.

Само сообщение шифруется гибридно. Для каждого сообщения генерируются новый:

[
K_m\in{0,1}^{256}
]

и 96-битный GCM nonce.

Canonical bytes `EidogramDocumentV1` шифруются:

[
C=
AES256_GCM(K_m,N_m,AAD,D).
]

Android рекомендует AES-GCM и требует не переиспользовать IV/nonce; R15 создаёт новый nonce для каждого сообщения через `SecureRandom`. ([Android Developers][2])

Одноразовый (K_m) отдельно оборачивается для каждого разрешённого устройства:

[
W_i=
RSA_OAEP_{PK_i}(K_m).
]

Используемая suite зафиксирована буквально как:

```text
RSA_2048_OAEP_SHA256_MGF1_SHA1
```

То есть основной OAEP digest — SHA-256, а MGF1 — SHA-1. Последнее не случайность: Android Keystore до API 35 использует SHA-1 как default MGF1 digest для OAEP; начиная с API 35 MGF1 digest можно задавать отдельно. В Android adapter я на API 35+ фиксирую SHA-1 явно, чтобы wire-suite не менялась с версией ОС. ([Android Developers][3])

Получателей может быть несколько. Более того, sender device автоматически добавляется в список recipients, поэтому отправитель способен расшифровать собственное сообщение после восстановления истории:

[
Recipients=
{recipient\ devices}\cup{sender\ device}.
]

Это уже подготовка к будущему multi-device.

AAD содержит не plaintext eidogram, а только неизменяемую структуру сообщения:

```text
conversation_id
sender_user_id
sender_device_id
sender_signing_key_id
sender_seq
created_at_ms
parent_message_ids
recipient_encryption_key_ids
message protocol/version
production grammar/binding/catalog lineage
```

То есть изменение получателя, conversation, parent, sequence или lineage ломает authentication.

После шифрования строится canonical encrypted body (B), затем:

[
\boxed{
MessageId=SHA256(B)
}
]

и только потом device signing key подписывает тот же (B):

[
Sig=
ECDSA_{P256,SHA256}(SK_{device},B).
]

Это важное разделение: случайность ECDSA не входит в `MessageId`. Сам `MessageId` зависит от ciphertext и wrapped keys, поэтому повторная отправка одной и той же эйдограммы создаёт другой публичный объект.

При этом plaintext `content_hash` эйдограммы вообще не публикуется в envelope. Следовательно два одинаковых изображения в разных разговорах нельзя связать простым сравнением открытого document hash.

Сообщение уже является DAG-node. В подписанном AAD находятся:

[
parent_message_ids
]

и sender-local:

[
sender_seq.
]

Поэтому R16 сможет строить torrent archive поверх уже существующего immutable message graph, а не придумывать вторую структуру истории.

`created_at_ms` тоже подписан, но я специально не делаю из wall-clock time источник причинного порядка. Время — presentation metadata; структура истории задаётся parents + sequence.

Строгие parsers теперь имеются для трёх новых объектов:

```text
EidoPublicIdentityBundleV1
EidoConversationV1
EidogramMessageV1
```

Они, как и документ эйдограммы, принимают только canonical bytes. Duplicate fields, лишние поля, неправильная lineage, неправильная sequence structure и просто другой JSON formatting блокируются.

Получатель выполняет цепочку:

[
DeviceCertificateVerify
\rightarrow
MessageIdVerify
\rightarrow
ECDSAVerify
\rightarrow
RSAOAEPUnwrap
\rightarrow
AESGCMDecrypt
\rightarrow
CanonicalEidogramParse.
]

То есть получить «расшифрованные произвольные bytes» и передать их дальше нельзя: plaintext обязан снова оказаться корректным `EidogramDocumentV1` нашей production lineage.

Я прогнал динамический pure-Kotlin/JCA end-to-end test. Получено 11 независимых `PASS`:

```text
PASS identity root/device certificate verification
PASS canonical public identity bundle round-trip
PASS canonical conversation descriptor round-trip
PASS signed encrypted message authentication
PASS canonical message envelope round-trip
PASS recipient and sender decrypt exact canonical eidogram
PASS ciphertext tamper rejected
PASS metadata tamper rejected
PASS cross-conversation replay binding
PASS non-canonical message rejected
PASS wrong private key cannot unwrap recipient key
```

Кроме этого создан **фиксированный cryptographic golden vector** с test-only key material. Он не просто создаётся заново случайным образом: сохранён конкретный подписанный/зашифрованный envelope, конкретные тестовые private/public keys и ожидаемые hashes.

Независимый replay этого фиксированного vector дал ещё:

```text
PASS R15 fixed golden public identity validation
PASS R15 fixed golden message signature verification
PASS R15 fixed golden RSA-OAEP/AES-GCM decryption for Bob
PASS R15 fixed golden sender self-decryption
PASS R15 fixed golden document hash/snapshot recovery
PASS R15 fixed golden signature tamper rejection
PASS R15 fixed golden conversation-binding rejection
```

То есть:

[
\boxed{7/7\ fixed\ crypto\ golden\ checks\ PASS}.
]

И я повторно прогнал всю затронутую R14.1 pure-Kotlin границу после изменения JSON integer parser с `Int` на `Long`, необходимого для `created_at_ms`.

Результат:

[
\boxed{13/13\ R14.1\ regression\ checks\ PASS}.
]

Все старые content/snapshot hashes эйдограмм сохранились буквально. Это принципиально: R15 не изменил формат уже созданных документов.

Итого текущая проверенная граница:

```text
R15 dynamic crypto       11 PASS
R15 fixed golden          7 PASS
R14.1 regression         13 PASS
--------------------------------
                         31 PASS
```

Есть важное ограничение, которое я не скрываю: **R15 пока не обладает forward secrecy**. Долгоживущий RSA device decryption key способен расшифровать исторические wrapped keys, если злоумышленник когда-либо получит этот private key и архив старых сообщений. Поэтому R15 — authenticated end-to-end encryption, но ещё не Signal-подобный Double Ratchet. Это зафиксировано отдельным обязательством `R20.1 — Forward-Secrecy Session Layer`, а не замаскировано словом «E2EE».

Также открыты metadata:

```text
conversation_id
sender/device IDs
recipient encryption-key IDs
parent IDs
sender_seq
created_at_ms
ciphertext length
```

Содержание эйдограммы при этом закрыто.

На Android private key material должен храниться через `AndroidKeyStore`; официальная документация прямо рекомендует Keystore, когда требуется усиленная защита ключей, а приватный material Keystore keys приложению не выдаётся. ([Android Developers][2])

Android runtime этого слоя я пока не объявляю проверенным: в рабочем контейнере по-прежнему нет Android SDK/emulator. Поэтому реально выполнены JVM/JCA crypto tests, а `AndroidKeyStore` adapter проверен на уровне исходного кода и официального API contract, но ещё не на Android device.

SHA-256 архива R15:

```text
064e5547ed1cf547dfa663a0fda321cd23f10e508776d5321cd4f8a1fc091c30
```

Roadmap теперь следующий:

1. **R16 — Torrent Archive Model.** Создаём `ConversationSegmentV1`: immutable bundle из canonical `EidogramMessageV1`, deterministic manifest, file-tree и segment hash. Поверх него — `ConversationHeadV1`, который указывает на актуальные segment/DAG heads. На этой стадии ещё без сетевого движка.
2. **R17 — Android Torrent/DHT Engine.** BitTorrent-v2, DHT, download/verify/seed, mutable conversation heads, NDK/JNI, storage quotas и Android background policy.
3. **R18 — Messenger UX + local repository.** Контакты, conversation list, timeline, отправка/приём, persistent replay/sequence enforcement и sync state.
4. **R19 — Archive/Recovery.** Полное восстановление истории после удаления локальной базы.
5. **R20 — Multi-device.** Root-authorized дополнительные устройства, revocation, concurrent heads и deterministic DAG merge.
6. **R20.1 — Forward-Secrecy Session Layer.** Ratcheted session secrets и удаление старых message keys.
7. **R21 — Security/Fuzzing.**
8. **R22 — Android 1.0 acceptance.**

Следующий шаг теперь однозначен:

[
\boxed{
R16=\text{Torrent Archive Model}
}
]

То есть в следующей итерации я свяжу уже готовые зашифрованные `EidogramMessageV1` в immutable torrent-addressable conversation segments и формально определю mutable head разговора.

[1]: https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec "https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec"
[2]: https://developer.android.com/privacy-and-security/cryptography?authuser=19&hl=en "https://developer.android.com/privacy-and-security/cryptography?authuser=19&hl=en"
[3]: https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec?authuser=2 "https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec?authuser=2"

Продолжил до `R16 — Torrent Archive Model`. Теперь у нас замкнута вся цепочка от эйдограммы до транспортно-адресуемого, но ещё не сетевого объекта:

[
EidogramDocumentV1
\rightarrow
EidogramMessageV1
\rightarrow
ConversationSegmentV1
\rightarrow
BitTorrent\ v2
\rightarrow
BEP44\ ConversationHead.
]

[Скачать полный проект R16](sandbox:/mnt/data/EidoLang-Android-R16.zip)

Основные артефакты: [методология R16](sandbox:/mnt/data/eidolang_android_r16/R16_METHODOLOGY.md), [checkpoint](sandbox:/mnt/data/eidolang_android_r16/R16_CHECKPOINT.md), [BEP52 mapping](sandbox:/mnt/data/eidolang_android_r16/R16_BEP52_MAPPING.md), [BEP44 head specification](sandbox:/mnt/data/eidolang_android_r16/R16_BEP44_HEAD.md), [validation report](sandbox:/mnt/data/eidolang_android_r16/EXAMPLE_R16_VALIDATION_REPORT.json), [test summary](sandbox:/mnt/data/eidolang_android_r16/TEST_LOG_R16_SUMMARY.txt), [обновлённый roadmap](sandbox:/mnt/data/eidolang_android_r16/ANDROID_TORRENT_MESSENGER_ROADMAP.md).

### Что получилось

Добавлен новый модуль:

```text
core-archive/
```

`ConversationSegmentV1` — это immutable архивный пакет. В нём лежат не открытые эйдограммы, а уже зашифрованные и подписанные `EidogramMessageV1`:

```text
conversation.json
identities/<user>/<device>.json
messages/<message_id>.json
segment-manifest.json
```

Manifest фиксирует message DAG, необходимые identity bundles, размеры и SHA-256 файлов, внешние parents и текущие message heads.

При этом введены два разных идентификатора:

[
SegmentId =
SHA256(CanonicalJSON(SegmentManifestBody)),
]

и

[
TorrentInfoHash_{v2}
====================

SHA256(Bencode(info)).
]

Это существенное разделение. Первый идентифицирует логический объект EidoLang, второй — его конкретное BitTorrent-v2 transport representation.

Полученный сегмент после скачивания **не считается достоверным только потому, что BitTorrent проверил куски**. Loader снова выполняет:

[
manifest
\rightarrow identities
\rightarrow message\ signatures
\rightarrow DAG
\rightarrow torrent\ metadata.
]

То есть проверяются canonical encoding, `SegmentId`, file hashes, R15 device certificates, подписи сообщений, parents, DAG heads и затем заново строится torrent infohash.

Повреждение одного байта message envelope даёт отказ.

### BitTorrent v2

Я сознательно не стал вводить эвристический выбор размера piece. R16 замораживает профиль:

[
\boxed{piece\ length=16384\ bytes}.
]

BEP52 определяет v2 `file tree`, `meta version = 2`, SHA-256 Merkle roots и допускает piece length как степень двойки не меньше 16 KiB; R16 выбирает ровно минимальное значение 16 KiB как часть нашей версии протокола. ([BitTorrent][1])

Для файла больше одного piece создаётся `piece layers`. В fixed golden специально имеется зашифрованный message-файл размером `62,765` байт, поэтому тестируется не только простой single-piece случай.

Получен настоящий `.torrent`:

[fixed `segment.torrent`](sandbox:/mnt/data/eidolang_android_r16/golden/r16/segment.torrent)

и magnet:

[fixed magnet](sandbox:/mnt/data/eidolang_android_r16/golden/r16/magnet.txt)

V2 magnet использует `urn:btmh:<tagged-info-hash>`; это именно формат, определённый для v2 magnet URI. ([BitTorrent][2])

Fixed golden:

```text
SegmentId
78aa678b7004e0acb800bcc0732d39c41028db776da51cbc0018e22e8af0d25b

Torrent v2 infohash
d53c6a8c2d521374d8cf38d025b5dfa273c59b3df75d44f26d7a495340d45129
```

Magnet:

```text
magnet:?xt=urn:btmh:1220d53c6a8c2d521374d8cf38d025b5dfa273c59b3df75d44f26d7a495340d45129&dn=...
```

Я дополнительно написал **независимую Python-реализацию** BEP52 hashing/bencoding, не использующую Kotlin builder. Она заново построила весь file tree и получила буквально те же `.torrent` bytes и тот же infohash:

```text
PASS independent Python BEP52 infohash
PASS independent Python BEP52 metainfo byte equality
```

Соответствующий verifier находится в:

[verify_r16_bep52.py](sandbox:/mnt/data/eidolang_android_r16/tools/python/verify_r16_bep52.py)

### Mutable head

Важнее архитектура изменяемого указателя.

Я отказался от идеи единого shared private key разговора. Иначе два участника должны были бы делить секретный ключ DHT, что плохо стыкуется и с identity model, и с будущим multi-device.

Вместо этого:

[
\boxed{\text{каждое устройство публикует собственный mutable conversation head}}
]

а позднее клиент объединяет известные heads в единый message DAG.

BEP44 mutable items используют 32-байтовый Ed25519 public key, sequence number и подпись; при salt lookup-target вычисляется из public key вместе с salt. Salt ограничен 64 байтами, а рассчитывать на хранение `v` больше 1000 байт нельзя. ([BitTorrent][3])

У нас:

[
salt = raw32(ConversationId)
]

и

[
\boxed{
DHTTarget=SHA1(k\Vert salt).
}
]

Но R15 device certificate не содержал Ed25519 DHT key. Я не стал задним числом менять R15 и ломать identity hashes.

Вместо этого введён:

[
\boxed{ArchivePublisherCertificateV1}
]

который связывает новый Ed25519 DHT public key с уже авторизованным R15 device и подписывается существующим P-256 device signing key:

[
Root
\rightarrow DeviceCertificate
\rightarrow ArchivePublisherCertificate
\rightarrow BEP44Head.
]

Это даёт чистую цепочку доверия без shared DHT secret.

Сам DHT value очень мал. Он содержит только:

```text
conversation id
publisher device id
publisher archive-key id
segment id
torrent-v2 infohash
message DAG heads
format version
```

Никаких messages или эйдограмм в DHT.

Golden value:

[
\boxed{310\ bytes}
]

при нашем обязательстве:

[
|v|\le1000.
]

Fixed BEP44 target:

```text
06ed00b9aa03a6f26a30069a823c115d1f1792ca
```

Файлы: [head-put.bencode](sandbox:/mnt/data/eidolang_android_r16/golden/r16/head-put.bencode) и [publisher certificate](sandbox:/mnt/data/eidolang_android_r16/golden/r16/publisher-certificate.json).

Здесь тоже сделан независимый cross-check: Python `cryptography` отдельно проверил Ed25519 signature, независимо вычислил

[
SHA1(k\Vert salt)
]

и подтвердил 1000-byte limit:

```text
PASS independent Python BEP44 Ed25519 signature
PASS independent Python BEP44 target SHA1(k||salt)
PASS independent Python BEP44 value <=1000 bytes
```

Verifier: [verify_r16_bep44.py](sandbox:/mnt/data/eidolang_android_r16/tools/python/verify_r16_bep44.py).

### Итоговая проверка

На финальном дереве:

```text
R16 dynamic final                 10 PASS
R16 fixed golden                   5 PASS
Independent Python BEP52           2 PASS
Independent Python BEP44           3 PASS
R15 fixed crypto regression        7 PASS
R14.1 regression                  13 PASS
-----------------------------------------
TOTAL                             40 PASS
```

То есть R16 не нарушил ни криптографический R15 envelope, ни R14.1 canonical eidogram representation.

Есть один нюанс: IDs из `RUN_LOG_R16_FINAL.txt` отличаются от fixed golden IDs. Это ожидаемо. Dynamic control каждый раз создаёт новые AES keys/nonces, RSA-OAEP ciphertext и signatures, поэтому новый encrypted message порождает новый `MessageId`, а следом новые `SegmentId` и torrent infohash. Каталог `golden/r16` является отдельным **замороженным reproducible vector**.

SHA-256 архива:

```text
a7c3c55cccd395b45e77b75509cdab345591eded28aeb24b88a82e7aad0d97db
```

### Что теперь действительно осталось до работающего P2P

Следующий этап — уже качественно другой:

[
\boxed{R17=\text{Android P2P / Torrent Engine}}
]

R16 определил все bytes, hashes и verification rules, но **не делает вид, что сеть уже работает**. Реальных DHT `get/put`, swarm download и seeding пока не было.

В R17 нужно поставить под уже готовый R16 чистый transport adapter:

[
\begin{aligned}
&publishSegment(segment.torrent,\ files),\
&fetchSegment(infohash),\
&putHead(BEP44MutableHead),\
&getHead(target),\
&seed/unseed,\
&pin/unpin.
\end{aligned}
]

Критическое правило admission будет:

[
NetworkDownloaded
\not\Rightarrow
RepositoryAccepted.
]

Только

[
NetworkDownloaded
\land R16Verify=PASS
\Rightarrow
RepositoryAccepted.
]

И ещё один operational момент уже задан самим BEP44: mutable DHT items могут истекать без re-announcement; спецификация рекомендует периодическое повторное объявление, поэтому это станет отдельным R17 scheduling obligation, а не скрытым поведением background service. ([BitTorrent][3])

После R17 у нас впервые должен появиться реальный двухузловой тест:

[
Phone_A
\rightarrow DHT/swarm
\rightarrow Phone_B
]

без application server, где B получает R16 segment по magnet/infohash, проверяет его до последнего message signature и восстанавливает исходную эйдограмму. Это теперь ближайший содержательный рубеж проекта.

[1]: https://www.bittorrent.org/beps/bep_0052.html "https://www.bittorrent.org/beps/bep_0052.html"
[2]: https://www.bittorrent.org/beps/bep_0009.html?from=20423&from_column=20423 "https://www.bittorrent.org/beps/bep_0009.html?from=20423&from_column=20423"
[3]: https://www.bittorrent.org/beps/bep_0044.html "https://www.bittorrent.org/beps/bep_0044.html"

Продолжил до `R17 — Android P2P / Torrent Engine`, но статус зафиксировал строго: **transport/source layer завершён, двухузловой admission пройден, реальный Android/libtorrent network gate остаётся `UNRESOLVED`**. Я не стал выдавать reference transport за настоящий BitTorrent.

[Скачать полный проект R17](sandbox:/mnt/data/EidoLang-Android-R17.zip)

Основные артефакты: [методология](sandbox:/mnt/data/eidolang_android_r17/R17_METHODOLOGY.md), [checkpoint](sandbox:/mnt/data/eidolang_android_r17/R17_CHECKPOINT.md), [validation](sandbox:/mnt/data/eidolang_android_r17/R17_VALIDATION.md), [native admission protocol](sandbox:/mnt/data/eidolang_android_r17/R17_NATIVE_ADMISSION.md), [dependency pin](sandbox:/mnt/data/eidolang_android_r17/R17_DEPENDENCY_PIN.md), [machine-readable report](sandbox:/mnt/data/eidolang_android_r17/EXAMPLE_R17_VALIDATION_REPORT.json), [test summary](sandbox:/mnt/data/eidolang_android_r17/TEST_LOG_R17_SUMMARY.txt) и [обновлённый roadmap](sandbox:/mnt/data/eidolang_android_r17/ANDROID_TORRENT_MESSENGER_ROADMAP.md).

Главный архитектурный gate теперь реализован буквально:

[
\boxed{
NetworkDownloaded\not\Rightarrow RepositoryAccepted
}
]

и только

[
\boxed{
NetworkDownloaded\land R16Verify=PASS
\Rightarrow RepositoryAccepted.
}
]

То есть даже если torrent engine сообщает `download complete`, приложение всё равно заново проверяет BEP44 head, publisher certificate, conversation/segment binding, весь R16 manifest, SHA-256 файлов, R15 device certificates, signatures всех messages, DAG heads и torrent-v2 infohash.

Добавлены три модуля:

```text
core-transport/
feature-sync/
native-torrent/
```

В `core-transport` есть backend-independent `TorrentTransport` и `NetworkAdmissionGate`. Поэтому дальнейший libtorrent/JNI слой не получает права сам решать, какие downloaded bytes считать сообщениями.

Я также добавил детерминированную двухузловую reference network. Это специально маркированный **test double, не BitTorrent**. В ней:

```text
Node A
  → seed R16 segment
  → publish mutable head

Node B
  → resolve head
  → fetch segment
  → NetworkAdmissionGate
  → exact R16 verification
```

Результат:

```text
PASS node A seeds immutable segment and publishes mutable head
PASS node B fetch -> R16 cryptographic admission
PASS mutable head rejects non-monotonic sequence
PASS transport success does not bypass corrupted-segment rejection
PASS malformed mutable-head bytes rejected
```

Последний содержательный control особенно важен. Я сначала позволил transport-слою успешно «скачать» объект, затем испортил байт внутри message-файла. Transport по-прежнему считал fetch успешным, но:

[
R16Verify=FAIL
]

и объект не был admitted.

Это нужное поведение для реальной враждебной P2P-сети.

Для native backend я зафиксировал **libtorrent 2.1.1**. На 11 августа 2026 это последний upstream release; он вышел 10 августа и, среди прочего, исправляет Merkle-tree issue и усиливает проверку ограничения BEP44 salt. ([GitHub][1])

Кроме того, release 2.1.x прямо рекомендует отключать WebTorrent, если он не нужен, поскольку он расширяет attack surface. Для нашего приложения он не нужен, поэтому CMake profile содержит:

```text
webtorrent=OFF
```

Нам нужны обычный BitTorrent v2, Mainline DHT и mutable DHT items, а не WebRTC/WebTorrent. ([GitHub][1])

Libtorrent подходит к R16/R17 без обходных протоколов: его `session` управляет torrent-сессиями и DHT, API поддерживает `async_add_torrent`, сохранение/восстановление session state, mutable `dht_get_item()` и `dht_put_item()` с Ed25519 key, salt и monotonic sequence. ([libtorrent][2])

Подготовлен JNI surface:

```text
createSession
destroySession
addTorrentFile
addMagnet
pollEvent
dhtGetMutable
dhtPutPreSigned
saveSessionState
```

и C++ scaffold:

[native JNI source](sandbox:/mnt/data/eidolang_android_r17/native-torrent/src/main/cpp/eidolang_libtorrent_jni.cpp) · [CMakeLists](sandbox:/mnt/data/eidolang_android_r17/native-torrent/src/main/cpp/CMakeLists.txt) · [инструкция включения backend](sandbox:/mnt/data/eidolang_android_r17/native-torrent/ENABLE_NATIVE_LIBTORRENT.md).

При этом `dhtPutPreSigned()` сейчас **намеренно fail-closed**. Я не стал писать неподтверждённую конверсию R16 BEP44 bencode → libtorrent callback и объявлять её готовой без NDK compilation/runtime test.

Android NDK официально поддерживает CMake и интеграцию через Gradle `externalNativeBuild`; NDK toolchain автоматически применяется в этом workflow. ([Android Developers][3])

Сам upstream libtorrent source в архив не включён. Для native build нужно положить точно `v2.1.1` в:

```text
native-torrent/third_party/libtorrent
```

Это сознательное решение: пока native backend не прошёл admission, сторонний исходник или случайно собранная `.so` не должны выглядеть как уже сертифицированная часть приложения.

### Android background policy

В `feature-sync` добавлен WorkManager-слой и stable pin:

```text
androidx.work = 2.11.2
```

Это текущая стабильная версия WorkManager; Android рекомендует WorkManager для надёжной persistent background work, переживающей выход приложения и перезапуск устройства. ([Android Developers][4])

Я не проектирую messenger как бесконечный скрытый foreground `dataSync` service. Современный Android ограничивает запуск foreground services из background, а Android 15+ дополнительно ограничивает время `dataSync` foreground services. ([Android Developers][5])

Поэтому модель сейчас:

[
\text{periodic head refresh / opportunistic sync}
\rightarrow WorkManager
]

а постоянный seeding должен быть отдельным явно пользовательским режимом и пройти Android runtime admission, а не возникнуть как скрытый daemon.

### Regression

После R17 повторно реально выполнены:

```text
R17 reference transport/admission      5 PASS
R16 dynamic archive                   10 PASS
R16 fixed golden                       5 PASS
independent Python BEP52               2 PASS
independent Python BEP44               3 PASS
R15 fixed crypto                       7 PASS
R14.1 editor/canonical                13 PASS
---------------------------------------------
TOTAL                                 45 PASS
```

То есть изменения transport-слоя не разрушили:

[
R14.1\ Document
\rightarrow
R15\ Message
\rightarrow
R16\ Segment/Torrent/Head.
]

SHA-256 архива R17:

```text
7324bf5655259a5e342f263b3cd875a32cacdfbbfca00a85960cae30930cefc4
```

Текущая граница проекта теперь очень чёткая:

[
\boxed{
R17.1=
\text{Native Android/libtorrent Network Admission}
}
]

Это уже чисто инженерный environmental gate. Нужно на машине с Android SDK/NDK:

1. собрать libtorrent 2.1.1 под `arm64-v8a` и `x86_64`;
2. закончить pre-signed BEP44 `dhtPut`;
3. поднять две независимые libtorrent sessions;
4. A seed'ит фиксированный R16 torrent;
5. B получает его через настоящий swarm;
6. B проходит `NetworkAdmissionGate`;
7. A публикует BEP44 head, B получает его через настоящий DHT;
8. после process restart восстановить session/DHT state;
9. повторить всё на двух Android devices.

После этого уже имеет смысл идти в `R18`: локальную БД диалогов, контакты, timeline и обычный messenger UX. То есть фундаментальные форматы документа, сообщения, архива и transport admission к этому моменту уже определены; следующий незакрытый риск — именно **реальное сетевое исполнение на Android**.

[1]: https://github.com/arvidn/libtorrent/releases "https://github.com/arvidn/libtorrent/releases"
[2]: https://www.libtorrent.org/tutorial-ref.html "https://www.libtorrent.org/tutorial-ref.html"
[3]: https://developer.android.com/ndk/guides/cmake "https://developer.android.com/ndk/guides/cmake"
[4]: https://developer.android.com/jetpack/androidx/releases/work?authuser=2 "https://developer.android.com/jetpack/androidx/releases/work?authuser=2"
[5]: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start?hl=en "https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start?hl=en"

Продолжил до `R18 — Messenger UX + Local Repository`, при этом реальное сетевое исполнение R17 больше не использовал как блокирующий gate.

[Скачать полный проект R18](sandbox:/mnt/data/EidoLang-Android-R18.zip)

Основные артефакты: [методология R18](sandbox:/mnt/data/eidolang_android_r18/R18_METHODOLOGY.md), [checkpoint](sandbox:/mnt/data/eidolang_android_r18/R18_CHECKPOINT.md), [validation](sandbox:/mnt/data/eidolang_android_r18/R18_VALIDATION.md), [схема локальной БД](sandbox:/mnt/data/eidolang_android_r18/R18_DATABASE_SCHEMA.md), [ручной двухустройственный протокол обмена](sandbox:/mnt/data/eidolang_android_r18/R18_MANUAL_EXCHANGE.md), [machine-readable report](sandbox:/mnt/data/eidolang_android_r18/EXAMPLE_R18_VALIDATION_REPORT.json), [test summary](sandbox:/mnt/data/eidolang_android_r18/TEST_LOG_R18_SUMMARY.txt), [обновлённый roadmap](sandbox:/mnt/data/eidolang_android_r18/ANDROID_TORRENT_MESSENGER_ROADMAP.md).

Теперь приложение при старте открывает уже не standalone-редактор, а:

[
\boxed{\texttt{EidoMessengerApp}}
]

Добавлены три модуля:

```text
core-repository/
feature-viewer/
feature-messenger/
```

То есть текущая цепочка уже выглядит как реальный мессенджер:

[
Editor
\rightarrow EidogramDocumentV1
\rightarrow EidogramMessageV1
\rightarrow LocalRepository
\rightarrow ConversationTimeline.
]

### Что реализовано в R18

Есть список контактов, список диалогов, timeline эйдограмм, переход из диалога непосредственно в редактор и обратный путь через кнопку `Отправить`.

Пользователь может:

* экспортировать собственный публичный identity bundle;
* импортировать контакт;
* создать диалог;
* экспортировать и импортировать descriptor диалога;
* открыть редактор из диалога;
* собрать эйдограмму из наших 113 глифов;
* нажать `Отправить`;
* получить подписанный и зашифрованный `EidogramMessageV1`;
* увидеть эйдограмму в timeline;
* экспортировать последнее исходящее сообщение;
* импортировать входящее сообщение.

Таким образом уже можно проверить **реальный обмен между двумя телефонами вообще без сети**, просто передавая три типа файлов:

```text
public identity
conversation descriptor
encrypted message envelope
```

Пошаговая процедура находится здесь:

[Manual two-device exchange](sandbox:/mnt/data/eidolang_android_r18/R18_MANUAL_EXCHANGE.md)

### Локальная база

Добавлен `AndroidSqliteMessengerRepository`, без новой внешней DB-зависимости.

Три таблицы:

```text
contacts
conversations
messages
```

Критично, что `messages` хранит не расшифрованную эйдограмму, а только canonical encrypted/signed R15 envelope.

То есть:

[
\boxed{
Plaintext(EidogramDocument)\notin MessagesDB
}
]

Timeline расшифровывает документ только при чтении через локальный device key.

Для сообщений действуют два ограничения идентичности:

[
message_id=\text{unique}
]

и

[
\boxed{
(conversationId,deviceId,senderSeq)=\text{unique}.
}
]

Точная повторная доставка одного `message_id` является идемпотентной:

[
Duplicate(M)=NOOP.
]

Но попытка того же устройства использовать тот же `senderSeq` для другого сообщения считается equivocation и отвергается.

При этом я **не** ввёл ошибочное условие:

[
seq_{new}>seq_{max}.
]

В P2P сообщения вполне могут приходить как:

[
5,3,4,2,\ldots
]

Поэтому последовательности разрешено прибывать не по порядку; запрещено только повторное использование одной пары `(device, seq)` для разных сообщений.

### Message DAG

Timeline теперь строится не сортировкой только по timestamp.

Используется настоящий DAG:

[
M_i\rightarrow M_j
\quad\Longleftrightarrow\quad
M_i\in Parents(M_j).
]

Новый локальный message автоматически получает в parents **все текущие heads**:

[
\boxed{
Parents(M_{new})=Heads(G_{local}).
}
]

Если пришли две независимые ветви:

[
A,\qquad B,
]

следующее локальное сообщение создаёт:

[
A\rightarrow C\leftarrow B.
]

То есть ветви детерминированно сливаются.

Timeline — topological sort. Parent всегда показывается раньше child независимо от часов отправителей.

Для одновременных независимых узлов tie-break детерминирован:

```text
created_at_ms
sender_user_id
sender_seq
message_id
```

### Incoming admission

Импорт JSON-файла не означает автоматическое появление сообщения.

Путь теперь такой:

[
CanonicalParse
\rightarrow ConversationBinding
\rightarrow SenderIdentity
\rightarrow SignatureVerify
\rightarrow LocalRecipientKeyBox
\rightarrow AESGCMDecrypt
\rightarrow EidogramCanonicalParse
\rightarrow DAGCheck
\rightarrow SequenceCheck
\rightarrow DB.
]

То есть даже ручной файловый импорт проходит практически ту же границу, которую позднее будет проходить torrent download.

Это сохраняет основной принцип R17:

[
TransportSuccess\not\Rightarrow MessageAccepted.
]

### Нашлась и исправлена ещё одна проблема R15

Обнаружился нетривиальный дефект identity semantics.

`DeviceId` зависит от тела device certificate, но root ECDSA signature над этим телом рандомизирована. Поэтому одно и то же устройство после повторной подписи может иметь:

[
DeviceId_1=DeviceId_2,
]

но:

[
Signature_1\neq Signature_2.
]

Следовательно нельзя было считать все canonical bytes public bundle частью identity.

Исправлено:

[
\boxed{
Identity(Device)=(UserId,DeviceId)
}
]

а root signature — только proof.

Теперь повторно подписанный, но полностью валидный certificate proof обновляет тот же контакт, а не создаёт конфликт.

Кроме того, `AndroidKeystoreIdentityStore` теперь кеширует валидную root signature для неизменившегося certificate body. При обычных рестартах приложения exported contact JSON поэтому остаётся стабильным. Кеш перед использованием снова криптографически проверяется.

Подробно:

[R15 identity stability correction](sandbox:/mnt/data/eidolang_android_r18/R15_IDENTITY_STABILITY_CORRECTION.md)

### Реально выполненные тесты

На финальном дереве R18:

```text
R18 messenger/repository              14 PASS
R17 reference admission                5 PASS
R16 fixed archive golden               5 PASS
R16 independent Python BEP52           2 PASS
R16 independent Python BEP44           3 PASS
R15 fixed crypto golden                7 PASS
R14.1 editor/canonical regression     13 PASS
---------------------------------------------
TOTAL                                 49 PASS
```

Новые R18 checks включают:

```text
PASS contact identity import and local alias
PASS refreshed ECDSA certificate proof does not fork contact identity
PASS shared canonical conversation descriptor
PASS first outgoing message committed as DAG head
PASS manual incoming admission decrypts exact eidogram
PASS exact message replay is idempotent
PASS reply parent edge produces deterministic causal timeline
PASS sender sequence increments and current head advances
PASS same sender/device sequence with different message is rejected
PASS incoming message without local recipient key box is rejected
PASS conversation import requires known participant identities
PASS out-of-order arrival retained and deterministically reordered
PASS new local message merges all current DAG branches
PASS conversation summary derives message count and heads
```

Android SDK/emulator всё ещё отсутствует в текущей среде, поэтому `assembleDebug`, Android SQLite instrumentation, Keystore runtime и Compose instrumentation я не объявляю выполненными. Но, как ты потребовал, это больше не тормозит разработку остальных уровней.

SHA-256 архива R18:

```text
924e63a3fa1bfbb4ca2e8b5c71a8caeb20d14c5df0ec634340ba53524472ba45
```

Следующий шаг теперь:

[
\boxed{
R19=\text{Archive / Recovery}
}
]

В нём уже можно связать R18 с готовым R16 без всякой реальной сети: автоматически собирать `ConversationSegmentV1` из локальных сообщений, вести индекс сегментов, сделать archive browser, `pin/unpin`, экспорт/импорт полного архива и — главное — провести end-to-end тест:

[
\text{удалить локальную message DB}
\rightarrow
\text{загрузить сохранённые R16 segments}
\rightarrow
\text{полностью восстановить contacts/conversations/messages/timeline}.
]

После R19 перейду к multi-device, затем forward secrecy и security hardening; реальный torrent networking можно подключить в самом конце как transport adapter поверх уже законченного приложения.

Продолжил до `R19 — Archive / Recovery`. Сетевое исполнение при этом вообще не использовалось как блокирующее условие.

[Скачать полный проект R19](sandbox:/mnt/data/EidoLang-Android-R19.zip)

Основные документы: [методология](sandbox:/mnt/data/eidolang_android_r19/R19_METHODOLOGY.md), [checkpoint](sandbox:/mnt/data/eidolang_android_r19/R19_CHECKPOINT.md), [протокол восстановления](sandbox:/mnt/data/eidolang_android_r19/R19_RECOVERY_PROTOCOL.md), [спецификация ArchivePackage](sandbox:/mnt/data/eidolang_android_r19/R19_ARCHIVE_PACKAGE.md), [validation](sandbox:/mnt/data/eidolang_android_r19/R19_VALIDATION.md), [machine-readable report](sandbox:/mnt/data/eidolang_android_r19/EXAMPLE_R19_VALIDATION_REPORT.json), [test summary](sandbox:/mnt/data/eidolang_android_r19/TEST_LOG_R19_SUMMARY.txt) и [обновлённый roadmap](sandbox:/mnt/data/eidolang_android_r19/ANDROID_TORRENT_MESSENGER_ROADMAP.md).

Главный результат R19 теперь формулируется жёстко:

[
\boxed{
Delete(LocalMessageState)
\land Keep(DeviceKeys)
\land Verified(R16Segments)
\Rightarrow Recover(MessageDAG,Eidograms)
}
]

То есть архив — уже не просто экспорт. Я реально смоделировал уничтожение локального repository, создал новый пустой repository и восстановил туда контакты, диалоги, encrypted envelopes, DAG, heads и исходные эйдограммы.

Добавлены:

```text
core-recovery/
feature-archive/
```

В приложении появился отдельный экран `Архив` с архивацией текущей истории, списком сегментов, `pin/unpin`, экспортом полного recovery package, импортом и проверкой/восстановлением.

Архивация теперь инкрементальная. Для локальных сообщений (M_L) и уже покрытых архивом сообщений (M_A):

[
M_{\text{new}}=M_L\setminus M_A.
]

Новый R16 segment содержит только ещё не архивированные сообщения. Если новых сообщений нет, новый сегмент не создаётся. Последующий segment ссылается на heads предыдущего segment DAG.

Я также закрыл важную проблему понятия «полный архив». Одних R16 segments недостаточно, чтобы восстановить, например, контакт, которому ещё не отправляли сообщения, имя контакта или пустой диалог: этих данных в transport/archive layer принципиально нет.

Поэтому введён отдельный локальный контейнер:

[
\boxed{\text{EidoArchivePackageV1}}
]

Он содержит public identity bundles контактов, aliases, canonical conversation descriptors, titles, creation metadata, все R16 segments и pin state. При этом приватных ключей там нет.

Fixed golden package: [archive-package.json](sandbox:/mnt/data/eidolang_android_r19/golden/r19/archive-package.json).

Его фиксированный:

```text
PackageId =
4c2e86bc96783a8f49f23f5dbd660b29b38cc2cc07f9ee7c6ea0f3973158e36b
```

Для package действует отдельная идентичность:

[
PackageId =
SHA256(CanonicalJSON(PackageBody)).
]

При этом сохраняется разделение:

[
MessageId\neq SegmentId\neq TorrentInfoHash_{v2}\neq PackageId.
]

Экспорт полного package теперь fail-closed. Если в messenger repository существует хотя бы одно committed сообщение, которого ещё нет ни в одном локальном archive segment:

[
M_L\not\subseteq M_A,
]

экспорт запрещается. Android UI перед экспортом сначала автоматически архивирует pending messages.

Import тоже transactional по смыслу. Сначала проверяются все segment blobs, R16 manifests, identity bundles, signatures, message DAG и torrent reconstruction. Затем полный recovery проигрывается в отдельном in-memory mirror текущего repository. Только если вся совокупность проходит проверку, начинается запись в настоящий repository/archive store.

Поэтому:

[
CorruptArchive
\Rightarrow
\boxed{\text{zero repository mutations}}.
]

Отдельно проверено перекрытие сегментов. Допустим:

[
S_1={M_1,M_2},
\qquad
S_2={M_3},
\qquad
S_3={M_2,M_3}.
]

После восстановления получается ровно:

[
{M_1,M_2,M_3},
]

без дубликатов. DAG и timeline остаются теми же.

Нашлась ещё одна формальная тонкость, аналогичная предыдущей проблеме device certificate. `MessageId` в R15 определяется encrypted body:

[
MessageId=H(EncryptedBody),
]

а ECDSA signature находится вне этого hash. Поэтому одно и то же тело можно корректно подписать дважды:

[
Body_1=Body_2,
\qquad
Sig_1\ne Sig_2,
\qquad
MessageId_1=MessageId_2.
]

Следовательно требование полного совпадения envelope bytes для duplicate `MessageId` было слишком сильным.

Я исправил repository semantics:

[
\boxed{\text{Message identity = canonical encrypted body}}
]

а ECDSA signature — proof material. Две разные корректные подписи одного и того же body теперь дают идемпотентный duplicate, а не конфликт.

Подробно: [R19_MESSAGE_PROOF_STABILITY_CORRECTION.md](sandbox:/mnt/data/eidolang_android_r19/R19_MESSAGE_PROOF_STABILITY_CORRECTION.md).

Есть важная граница восстановления. R19 намеренно **не кладёт private keys в backup**. Package связывается с:

```text
owner_user_id
recovery_device_id
recovery_encryption_key_id
```

и восстановление разрешается только если на устройстве сохранился тот же исторический decryption key.

То есть R19 решает:

[
\text{потеря DB/app state}
]

но не:

[
\text{потеря устройства/private keys}.
]

Попытка восстановить тот же package на втором корректно авторизованном устройстве того же пользователя сейчас машинно отвергается. Это уже задача R20.

Проверки на финальном дереве:

```text
R19 dynamic archive/recovery       12 PASS
R19 fixed recovery package          3 PASS
R18 messenger/repository           14 PASS
R17 reference admission             5 PASS
R16 fixed archive golden            5 PASS
R16 independent Python BEP52        2 PASS
R16 independent Python BEP44        3 PASS
R15 fixed crypto golden             7 PASS
R14.1 editor/canonical             13 PASS
------------------------------------------
TOTAL                              64 PASS
```

Среди новых R19 controls:

```text
PASS first R18 history chunk archived as verified R16 segment
PASS complete archive export refuses locally committed but unarchived messages
PASS incremental archive emits only unarchived messages and advances segment lineage
PASS archive pin state is independent of immutable R16 segment identity
PASS full archive package canonical round-trip excludes private key material
PASS destructive recovery including empty conversations and unused contacts
PASS exact archive package replay is idempotent
PASS verified R16 segments alone reconstruct exact cryptographic DAG/eidograms
PASS overlapping-segment recovery deduplicates repeated messages
PASS refreshed valid ECDSA proof is an idempotent duplicate
PASS corrupt package rejected before any mutation
PASS package refuses restore without the historical device decryption key
```

SHA-256 полного R19-архива:

```text
2e93a3686fa153e2ddf50a802593c5b445cef3d49e346c5182695fe3a4bbfbec
```

Roadmap теперь такой:

1. `R20 — Multi-device`. Несколько root-authorized устройств одного пользователя, device roster, authorization/revocation, несколько устройств одного контакта, recipient key boxes для всех active devices, несколько собственных устройств как recipients, concurrent local heads и deterministic DAG convergence. Это же должно снять ограничение R19 на same-device recovery.
2. `R20.1 — Forward Secrecy`. Ratcheted session keys, historical key erasure и уход от постоянных RSA key boxes для старой переписки без изменения `EidogramDocumentV1`.
3. `R21 — Security/Fuzzing/Local Hardening`. At-rest protection DB/drafts/archive, malicious certificates, replay/equivocation corpus, archive poisoning, parser/JNI fuzzing.
4. `R22 — Product Completion`. Profile/settings, notifications, окончательный Android build/device admission и только после этого подключение реального R17 BitTorrent/DHT backend.

Следующий содержательный шаг поэтому:

[
\boxed{R20=\text{Multi-device identity, revocation and DAG convergence}}
]

И это уже напрямую приблизит приложение к нормальному мессенджеру: один пользователь сможет иметь несколько телефонов/планшетов, каждый из которых получит ту же переписку и сможет независимо продолжать общий message DAG.

Продолжил до `R20 — Multi-device identity, revocation and DAG convergence`.

[Скачать полный проект R20](sandbox:/mnt/data/EidoLang-Android-R20.zip)

Основные материалы: [методология](sandbox:/mnt/data/eidolang_android_r20/R20_METHODOLOGY.md), [checkpoint](sandbox:/mnt/data/eidolang_android_r20/R20_CHECKPOINT.md), [device authorization](sandbox:/mnt/data/eidolang_android_r20/R20_DEVICE_AUTHORIZATION.md), [historical key grants](sandbox:/mnt/data/eidolang_android_r20/R20_KEY_GRANTS.md), [cross-device recovery](sandbox:/mnt/data/eidolang_android_r20/R20_RECOVERY_MIGRATION.md), [Android onboarding](sandbox:/mnt/data/eidolang_android_r20/R20_ANDROID_ONBOARDING.md), [validation](sandbox:/mnt/data/eidolang_android_r20/R20_VALIDATION.md), [machine-readable report](sandbox:/mnt/data/eidolang_android_r20/EXAMPLE_R20_VALIDATION_REPORT.json), [test summary](sandbox:/mnt/data/eidolang_android_r20/TEST_LOG_R20_SUMMARY.txt) и [fixed golden](sandbox:/mnt/data/eidolang_android_r20/golden/r20/r20_multidevice_golden_v1.json).

Главный инвариант R20 теперь действительно выполнен:

[
SameUser(D_1,D_2)\land Authorized(D_1,D_2)
\Rightarrow
DecryptSameHistory\land ConvergeSameDAG.
]

Добавлен новый модуль:

```text
core-multidevice/
```

Второе устройство больше не создаёт новую пользовательскую identity и не получает root private key. Оно локально генерирует собственные:

[
SK_{\rm sign}^{(2)},\qquad SK_{\rm decrypt}^{(2)}
]

и формирует `EidoDeviceEnrollmentRequestV1`. Request подписывается новым device signing key, поэтому root-authority получает proof-of-possession, а не просто произвольные публичные ключи.

После проверки root выдаёт обычный уже существующий `DeviceCertificateV1`:

[
UserRoot
\rightarrow
DeviceCertificate(D_2).
]

То есть R15 identity model не пришлось ломать.

Для Android подготовлены две реальные KeyStore-границы:

```text
AndroidRootAuthority
AndroidSecondaryDeviceIdentityStore
```

Secondary device не создаёт второй root. Его private keys остаются в собственном `AndroidKeyStore`.

Введён root-signed:

[
\boxed{EidoUserDeviceRosterV1}
]

со структурой:

[
R_n=(UserId,n,R_{n-1},Active_n,Revoked_n).
]

После bootstrap действует строгая цепочка:

[
epoch_{n+1}=epoch_n+1,
]

[
previousRosterId_{n+1}=RosterId_n.
]

Revocation set монотонен:

[
Revoked_n\subseteq Revoked_{n+1},
]

и отозванное устройство нельзя незаметно вернуть в `active`.

При этом revocation сделана именно prospective:

[
AcceptedBeforeRevocation(M)\Rightarrow M\ remains\ valid,
]

но

[
NewMessage(RevokedDevice)\Rightarrow Reject.
]

То есть отзыв устройства не переписывает историю.

Новые сообщения теперь получают recipient key boxes для **всех активных устройств всех участников**, включая другие устройства самого отправителя:

[
Recipients(M)=
\bigcup_{u\in Participants}
ActiveDevices(u).
]

Например, при:

```text
Alice: phone + tablet
Bob: phone + tablet
```

одно новое сообщение содержит четыре device key boxes. После revocation конкретный device исчезает из будущих recipient boxes.

Per-device sequence остаётся:

[
(conversationId,deviceId,senderSeq).
]

Поэтому два устройства Alice могут независимо создать:

[
H\rightarrow A
]

и

[
H\rightarrow B.
]

После обмена обе стороны вычисляют:

[
Heads={A,B},
]

а следующее сообщение автоматически делает:

[
A\rightarrow C\leftarrow B.
]

В тесте четыре независимых repository — два устройства Alice и два Bob — пришли к буквально одинаковому causal timeline и одному head после merge.

### Старые сообщения и новое устройство

Здесь обнаруживается принципиальная проблема: старое R15-сообщение не содержит key box нового устройства. Просто добавить его нельзя:

[
Body'\neq Body
\Rightarrow MessageId'\neq MessageId.
]

Поэтому введён отдельный объект:

[
\boxed{EidoMessageKeyGrantV1}.
]

Старое устройство, которое умеет расшифровать (M):

1. получает старый (K_M);
2. заново оборачивает его публичным ключом нового устройства;
3. связывает grant с `MessageId`, grantor и target device;
4. подписывает grant своим device signing key.

Сам `EidogramMessageV1` остаётся неизменным:

[
\boxed{MessageId_{before}=MessageId_{after}}.
]

Это существенно: multi-device migration не переписывает историю и не разрушает R16 archive identities.

### R19 теперь восстанавливается на другое устройство

Ограничение R19 «только тот же decryption key» снято дополнительным объектом:

[
\boxed{EidoMultiDeviceRecoveryCapsuleV1}.
]

R19 package при этом не изменён.

Capsule содержит:

* исходный `PackageId`;
* target device;
* все public bundles устройств владельца;
* актуальные root-signed rosters участников;
* необходимые historical key grants.

Private keys отсутствуют.

Recovery второго устройства выполняет:

[
R19Package
+
R20Capsule
+
TargetPrivateKeys
\rightarrow
SameMessageDAG.
]

Причём сначала восстанавливается историческая переписка, и только после этого применяется текущий roster/revocation state. Поэтому нынешний revocation не ретроактивно уничтожает старые сообщения.

Проверено также более неприятное продолжение: если уже мигрированное второе устройство затем создаёт новый backup, его старые сообщения всё ещё доступны только через grants. Поэтому последующие backups сохраняют R20 companion capsule. Голый R19 package для этой части истории намеренно недостаточен.

Fixed R20 vector имеет:

```text
legacy MessageId:
392fed55f1a63a2a33a412accabb834ec3b3996092d20a8ae2cb30d20d68e7a6

document content hash:
39e58a73d1ecb6e2578e1ad4b119418d8685aefab1833f7d192d6a0edd7a261a

R19 PackageId:
4972e806729ba7ef355aded211771633a92f211f0a1305e157585175b71cb409

R20 CapsuleId:
f671341a847b10b23a8ac864843e1607048c3d39ff919a76667d213e562862eb
```

SHA-256 fixed golden:

```text
2c87c46a8c3cf21ef4c85b971d33bcb17294a6bd125629ccf8e7db6e332e0eb3
```

Финальная выполненная regression boundary:

```text
R20 multi-device                    11 PASS
R20 cross-device recovery            9 PASS
R20 fixed golden                     6 PASS
R19 dynamic                         12 PASS
R19 fixed package                    3 PASS
R18                                 14 PASS
R17 reference admission              5 PASS
R16 fixed golden                     5 PASS
Python BEP52                         2 PASS
Python BEP44                         3 PASS
R15 fixed crypto                     7 PASS
R14.1                               13 PASS
--------------------------------------------
TOTAL                               90 PASS
```

Отдельно проверены:

* possession-proof enrollment;
* четыре active recipient devices;
* concurrent same-user branches;
* deterministic convergence;
* revocation;
* roster rollback;
* historical key migration;
* second-device R19 recovery;
* incomplete capsule → zero mutations;
* revoked recovery target → rejection;
* subsequent backup from migrated device.

SHA-256 архива R20:

```text
191c34735ef3efc65013512772892933224ac3e08a671e942602ee663278c06a
```

Остаётся фундаментальный недостаток, который R20 специально не маскирует: RSA recipient boxes и historical grants сохраняют долгосрочную расшифровываемость. Следовательно:

[
\boxed{R20\neq ForwardSecrecy}.
]

Следующий этап теперь `R20.1 — Forward-Secrecy Session Layer`. Там уже нельзя ограничиться удалением старых RSA keys: нужно ввести настоящий session bootstrap с ephemeral DH/prekeys, ratcheted chain keys, bounded skipped-message keys и их физическое удаление. При этом `EidogramDocumentV1` останется неизменным, а изменение произойдёт только в криптографическом message/session layer.

Продолжил до `R20.1 — Forward-Secrecy Session Layer`.

[Скачать полный проект R20.1](sandbox:/mnt/data/EidoLang-Android-R20.1.zip)

Основные артефакты:

* [методология](sandbox:/mnt/data/eidolang_android_r20_1/R20_1_METHODOLOGY.md)
* [checkpoint](sandbox:/mnt/data/eidolang_android_r20_1/R20_1_CHECKPOINT.md)
* [security model](sandbox:/mnt/data/eidolang_android_r20_1/R20_1_SECURITY_MODEL.md)
* [архивный no-go результат](sandbox:/mnt/data/eidolang_android_r20_1/R20_1_ARCHIVE_NO_GO.md)
* [Android secret-state storage](sandbox:/mnt/data/eidolang_android_r20_1/R20_1_ANDROID_STORAGE.md)
* [validation](sandbox:/mnt/data/eidolang_android_r20_1/R20_1_VALIDATION.md)
* [machine-readable report](sandbox:/mnt/data/eidolang_android_r20_1/EXAMPLE_R20_1_VALIDATION_REPORT.json)
* [test summary](sandbox:/mnt/data/eidolang_android_r20_1/TEST_LOG_R20_1_SUMMARY.txt)
* [fixed cryptographic golden](sandbox:/mnt/data/eidolang_android_r20_1/golden/r20_1/r20_1_forward_session_golden_v1.json)
* [обновлённый roadmap](sandbox:/mnt/data/eidolang_android_r20_1/ANDROID_TORRENT_MESSENGER_ROADMAP.md)

Главное изменение — введён отдельный модуль:

```text
core-session/
```

Теперь поверх уже существующего immutable `EidogramMessageV1` работает внешний forward-secure session layer:

[
EidogramMessageV1
\rightarrow
EidoForwardSessionV1
\rightarrow
RatchetPacket.
]

Я специально не менял `EidogramDocumentV1`, `MessageId` и causal DAG. То есть session encryption является новой криптографической оболочкой, а не переписыванием уже зафиксированной структуры сообщений.

Криптографический профиль:

[
\boxed{
P!-!256\ ECDH
+
HKDF!-!SHA256
+
HMAC!-!SHA256
+
AES!-!256!-!GCM
}
]

HKDF реализован в соответствии с RFC 5869; отдельно прогнан официальный Appendix A.1 test vector. ([RFC Editor][1])

Для asynchronous bootstrap введён:

```text
EidoDevicePreKeyBundleV1
```

с двумя DH-ключами получателя:

[
SPK_B
]

— signed prekey, и

[
OPK_B
]

— one-time prekey.

Инициатор создаёт свежий ephemeral:

[
E_A
]

и начальный secret строится из:

[
IKM=
ECDH(E_A,SPK_B)
\parallel
ECDH(E_A,OPK_B).
]

Prekey bundle подписан уже существующим R20 device signing key. Initial session packet также подписан sender device key, поэтому сетевое изменение bootstrap ciphertext или ключевых ссылок не проходит authentication.

Это не называется X3DH: X3DH использует свою identity-DH конструкцию и X25519/X448-параметры; наш bootstrap — отдельный EidoLang protocol, хотя использует ту же общую asynchronous-prekey идею. ([Signal Messenger][2])

One-time prekey после первого использования удаляется из persistent protocol state:

[
Use(OPK_i)\Rightarrow OPK_i\notin PreKeyStore.
]

Повторная инициализация через тот же OPK машинно отвергается.

После bootstrap работает symmetric ratchet:

[
MK_n=HMAC(CK_n,01),
]

[
CK_{n+1}=HMAC(CK_n,02).
]

Каждый `MK_n` применяется один раз и затем удаляется.

Добавлен и настоящий DH/root ratchet:

[
(RK',CK_r)
==========

KDF_{RK}
\left(
RK,
ECDH(DH_s,DH_r')
\right),
]

затем генерируется новый локальный DH keypair:

[
(RK'',CK_s)
===========

KDF_{RK}
\left(
RK',
ECDH(DH_s',DH_r')
\right).
]

По структуре это тот же принцип сочетания symmetric-key ratchet и DH ratchet, который лежит в основе Double Ratchet, но я не объявляю реализацию wire-compatible или формально эквивалентной Signal Double Ratchet. ([Signal Messenger][3])

Появилась поддержка out-of-order доставки. Если приходят:

[
M_2,\ M_0,\ M_1,
]

receiver вычисляет и временно сохраняет skipped keys для (M_0,M_1), принимает (M_2), а затем употребляет сохранённые ключи ровно по одному разу.

После их использования:

[
SkippedKey(M_i)\rightarrow\varnothing.
]

Текущий `maxSkip=256` — только локальная DoS/resource policy, а не часть wire identity.

Особенно важен тест forward secrecy. После продвижения цепочки я сделал snapshot **текущего скомпрометированного session state** и попытался расшифровать уже употреблённый старый packet:

[
Compromise(State_{current})
+
Ciphertext(M_{old})
\nRightarrow
Plaintext(M_{old}).
]

Тест проходит: старого message key в текущем состоянии больше нет.

Нашлась и исправлена ещё одна серьёзная ошибка: первоначально failed AES-GCM authentication могла бы произойти после изменения receiving chain state. Тогда один испорченный packet мог десинхронизировать сеанс.

Теперь receive транзакционный:

[
S_0
\xrightarrow{\text{tentative receive}}
S_1.
]

Если AEAD authentication не проходит:

[
S_1\rightarrow S_0.
]

В control test сначала подаётся корректно сформированный, но криптографически испорченный packet, он отвергается; после этого оригинальный packet с тем же ratchet position успешно принимается.

Secret session state теперь persistable. Добавлены:

```text
EidoForwardSessionSecretStateV1
EidoPreKeySecretStateV1
```

Prekeys тоже сохраняются до использования, поэтому asynchronous bootstrap больше не зависит от того, остался ли Android process жив.

Для Android реализован:

```text
AndroidSecretBlobStore
AndroidSessionStateStore
AndroidPreKeyStateStore
```

Session/prekey P-256 private state шифруется AES-256-GCM ключом из `AndroidKeyStore`.

Это решение необходимо из-за нашего `minSdk 26`: обычный Android/JCA `ECDH` поддерживается с API 11, но `AndroidKeyStore.PURPOSE_AGREE_KEY`, позволяющий непосредственно держать ECDH private key в Keystore, появился только в API 31. ([Android Developers][4])

Поэтому compatibility path:

[
P256_{JCA}\ secret
\rightarrow
AES!-!GCM
\rightarrow
AndroidKeyStore.
]

На API 31+ backend позднее можно заменить на direct Keystore ECDH, не меняя публичный R20.1 wire protocol.

Дополнительно local encrypted blobs теперь аутентифицируют своё logical имя через GCM AAD:

```text
session-<session_id>
prekeys-v1
```

Поэтому нельзя переставить два корректных encrypted state files местами и добиться их успешной загрузки. `SessionStateStore` также сверяет внутренний `session_id` после decrypt.

Полученный новый dynamic suite:

```text
PASS RFC5869 HKDF-SHA256 vector A.1
PASS signed P-256 prekey bundle authentication
PASS persistent prekey secret state round-trip
PASS asynchronous one-time-prekey consumption
PASS responder DH/root ratchet
PASS out-of-order skipped-message recovery
PASS current-state compromise cannot recover erased old key
PASS canonical secret-session state round-trip
PASS restored ratchet state continues live session
PASS failed AEAD receive rolls state back
PASS initial ciphertext tamper rejected
```

То есть:

[
\boxed{11\ PASS}.
]

Fixed golden дал ещё:

[
\boxed{6\ PASS}.
]

И я сделал независимый verifier на Python `cryptography`, который независимо от Kotlin-кода воспроизвёл:

[
P256\ ECDH
\rightarrow
HKDF
\rightarrow
root/chain
\rightarrow
AESGCM,
]

включая out-of-order `2,0,1`.

Результат:

```text
PASS independent Python P-256 ECDH/root-ratchet/AES-GCM response
PASS independent Python out-of-order chain derivation
PASS independent Python RFC5869 vector A.1
```

Ещё:

[
\boxed{3\ PASS}.
]

После этого весь старый pure-core tree R14.1–R20 был перекомпилирован с новым `core-session` и повторно прогнан:

```text
R20.1 dynamic                       11
R20.1 fixed golden                   6
R20.1 independent Python             3

R20 multi-device                    11
R20 cross-device recovery            9
R20 fixed golden                     6
R19 dynamic                         12
R19 fixed package                    3
R18                                 14
R17 reference admission              5
R16 fixed golden                     5
Python BEP52                         2
Python BEP44                         3
R15 fixed crypto                     7
R14.1                               13
---------------------------------------
TOTAL                              110 PASS
```

Fixed R20.1 golden SHA-256:

```text
796efb216481dec0b5913e4a2ae8d7ff922893471129a073c4ed255f3483b703
```

SHA-256 полного архива:

```text
f6bdbf63cc0d1f980e0b693fe7ebe32e9e014d3e7e7fa29503f548fa897006e2
```

Но наиболее важный результат этой стадии оказался отрицательным и структурным.

Forward-secure session **не делает существующий R16/R19 archive forward-secret**.

Причина строгая. Пусть старый packet зашифрован ключом:

[
C_i=AEAD_{K_i}(P_i)
]

и forward secrecy требует:

[
K_i\notin State_{current}.
]

Если после полного удаления локального состояния мы хотим восстановить (P_i) только из:

[
C_i+State_{current},
]

то текущему состоянию каким-то образом снова надо предоставить информацию, достаточную для получения (P_i).

Это противоречит исходному требованию уничтожения исторического ключа.

Получается no-go:

[
\boxed{
ForwardSecrecy
\land
CiphertextOnlyArchive
\land
DestructiveRecovery
}
]

невозможны одновременно без **отдельного recovery-secret domain**.

Более того, нынешний R16 archive хранит внутренние R15 envelopes. Поэтому даже если в сети они были защищены outer ratchet layer, архив отбрасывает эту защиту. При последующей компрометации старого RSA device key:

[
RSA_{private}
+
R16Archive
\Rightarrow
HistoricalPlaintext.
]

Это не баг R20.1, а доказанная несовместимость старой архивной архитектуры с новым требованием.

Поэтому следующий этап теперь не произвольный:

[
\boxed{
R20.2=
Forward!-!Secure\ History\ Vault
+
Archive\ Migration
}
]

Нужен независимый:

[
HistoryVaultKey
\neq
ActiveSessionState,
]

чтобы отдельно обеспечить две разные гарантии:

[
Compromise(SessionState)
\nRightarrow
PastHistory,
]

но одновременно сохранить сознательно выбранный механизм destructive recovery через отдельный recovery key domain.

Именно это теперь является следующей необходимой сущностью проекта, а не ещё одним эвристическим расширением.

[1]: https://www.rfc-editor.org/info/rfc5869/ "https://www.rfc-editor.org/info/rfc5869/"
[2]: https://signal.org/docs/specifications/x3dh/ "https://signal.org/docs/specifications/x3dh/"
[3]: https://signal.org/docs/specifications/doubleratchet/ "https://signal.org/docs/specifications/doubleratchet/"
[4]: https://developer.android.com/reference/kotlin/javax/crypto/KeyAgreement "https://developer.android.com/reference/kotlin/javax/crypto/KeyAgreement"

Продолжил до `R20.2 — Forward-Secure History Vault / Archive Migration`.

[Скачать полный проект R20.2](sandbox:/mnt/data/EidoLang-Android-R20.2.zip)

Основные документы: [методология](sandbox:/mnt/data/eidolang_android_r20_2/R20_2_METHODOLOGY.md), [security model](sandbox:/mnt/data/eidolang_android_r20_2/R20_2_SECURITY_MODEL.md), [archive migration](sandbox:/mnt/data/eidolang_android_r20_2/R20_2_ARCHIVE_MIGRATION.md), [recovery domain](sandbox:/mnt/data/eidolang_android_r20_2/R20_2_RECOVERY_DOMAIN.md), [Android storage](sandbox:/mnt/data/eidolang_android_r20_2/R20_2_ANDROID_STORAGE.md), [checkpoint](sandbox:/mnt/data/eidolang_android_r20_2/R20_2_CHECKPOINT.md), [validation](sandbox:/mnt/data/eidolang_android_r20_2/R20_2_VALIDATION.md), [machine-readable report](sandbox:/mnt/data/eidolang_android_r20_2/EXAMPLE_R20_2_VALIDATION_REPORT.json), [test summary](sandbox:/mnt/data/eidolang_android_r20_2/TEST_LOG_R20_2_SUMMARY.txt) и [fixed golden](sandbox:/mnt/data/eidolang_android_r20_2/golden/r20_2/r20_2_history_vault_golden_v1.json).

Ключевой результат: no-go R20.1 закрыт не ослаблением forward secrecy, а введением действительно независимого домена:

[
\boxed{
ActiveSessionState \perp HistoryVaultRecoverySecret
}
]

Добавлен `core-vault`. У каждого vault теперь есть отдельный случайный 256-битный recovery secret, а у каждой эпохи — новый независимый случайный 256-битный `VaultEpochKey`.

Историческая запись теперь имеет структуру:

[
EidogramMessageV1 + EidogramDocumentV1
\rightarrow
AEAD_{VaultEpochKey}
\rightarrow
HistoryVaultEntry.
]

То есть старые R15 envelopes вместе с их RSA key boxes больше не лежат на поверхности нового архива. Они находятся внутри authenticated ciphertext.

Это дало нужную новую границу:

[
Compromise(CurrentR20.1RatchetState)
\nRightarrow
HistoricalVaultPlaintext.
]

Я проверил это буквально: текущий ratchet root был подставлен вместо recovery secret — vault не открылся, repository остался пустым.

Ещё сильнее проверен главный recovery-критерий. История была создана до появления второго устройства Alice. Затем она мигрирована в R20.2 и восстановлена на Alice₂ при одновременном отсутствии:

[
SK^{RSA}_{Alice_1}
]

и всех R20 historical `MessageKeyGrant`.

Тем не менее восстановились те же:

[
MessageId,
\quad DAG,
\quad EidogramDocument.
]

После восстановления Alice₂ смогла продолжить тот же causal DAG новым сообщением.

Для этого в repository введён `HistoricalDocumentProvider`. Старый immutable R15 envelope остаётся носителем `MessageId`, sender signature, sequence и parent edges, а документ для старой истории может быть получен из authenticated history-vault record, когда прежнего R15 decryption path уже намеренно нет.

Важно, что это не подмена доказательства: при восстановлении оригинальная подпись исторического R15 envelope проверяется отдельно. Восстановленный документ защищён vault AEAD, его canonical content hash и подписью exporter device над vault package.

Revocation теперь распространяется и на архив. После отзыва Alice₁ создаётся совершенно новый случайный epoch key:

[
VEK_{e+1}\overset{$}{\leftarrow}{0,1}^{256},
]

и Alice₁ исключается из нового active set. Она не может получить grant на новый epoch. Alice₂ — может.

Это именно prospective revocation: уже известный старый epoch key математически «отозвать» невозможно, но будущие записи старому устройству недоступны.

R20.2 также реализует миграцию старого R19:

[
R19
\rightarrow
VerifiedRestore
\rightarrow
VerifiedTimeline
\rightarrow
R20.2\ Vault.
]

Контроль подтвердил сначала противоположное свойство legacy-формата: из R19 действительно извлекаются canonical R15 envelope bytes. После миграции новый vault package уже не содержит ни raw envelope, ни его RSA wrapped-key material в открытом archive surface.

Но здесь есть принципиальное условие:

[
\boxed{
ForwardSecureCutover
====================

VerifiedMigration
\land
Delete(LegacyR16/R19Copies)
}
]

Если старую копию R19 оставить где-нибудь на диске, последующая компрометация старого RSA key всё ещё сможет атаковать именно эту старую копию. Никакой новый формат не способен ретроактивно удалить существующий ciphertext.

Введён отдельный active-device механизм `EidoHistoryVaultDeviceGrantV1`. Он позволяет текущему активному устройству получить конкретный `VaultEpochKey`, но не раскрывает общий recovery secret. Поэтому:

[
DeviceEpochAccess \neq UniversalRecoveryAuthority.
]

Сам recovery secret локально хранится под отдельным AndroidKeyStore AES-GCM alias:

```text
eidolang.history-vault-secret-wrap.v1
```

Он намеренно отличается от R20.1:

```text
eidolang.forward-session-state-wrap.v1
```

То есть session-state и archive-recovery state теперь разделены не только логически, но и в локальном key domain.

Выполненные новые проверки:

```text
R20.2 dynamic history-vault       12 PASS
R20.2 fixed golden                 6 PASS
R20.2 independent Python           3 PASS
-----------------------------------------
R20.2 additions                   21 PASS
```

После последней правки `core-vault` я заново собрал pure core и повторно прогнал всю предыдущую границу R14.1–R20.1:

```text
R20.1 dynamic                     11
R20.1 fixed                        6
R20.1 independent Python           3
R20 multi-device                  11
R20 cross-device recovery          9
R20 fixed                          6
R19 dynamic                       12
R19 fixed                          3
R18                               14
R17 reference admission            5
R16 fixed                          5
Python BEP52                       2
Python BEP44                       3
R15 fixed                          7
R14.1                             13
------------------------------------
prior subtotal                   110 PASS
```

Итого:

[
\boxed{131\ PASS}.
]

Independent Python verifier отдельно воспроизвёл package SHA-256/ECDSA authentication, HKDF recovery wrapping, AES-GCM epoch/entry decryption и RSA-OAEP active-device epoch grant.

Fixed R20.2 golden:

```text
MessageId:
2aab690c34dbfaaa8e08760f3eb04884e57eaed7b79975482db5ed2e780af09d

VaultId:
6e44acc0af3b2b56ac4a5e89914f6cb69b6855a4d597bf02c4eca8715aaed03e

EpochId:
94b4278a33f9e68a066e876653d2fe9c39fb5761006d16240ac5f0d1c059f34c

EntryId:
bf166a5ea85a786b323533a9f7bddd0c244f5d64a4304320d8e3ecb3d6fa08c4

PackageId:
b013b6f44897f8253b6360df88ba252cdec897f40351aad1802f82a824d0d3d5
```

Golden SHA-256:

```text
b1ce569e770200806a003ae867d90096d9720f0240aed015db96ae49951fb0b8
```

SHA-256 полного архива:

```text
b2f44f574b1f36019c82e2059233241a9dfa84193c72b7429ca6213cfac3f305
```

Есть теперь одна сознательно выделенная цена destructive recovery:

[
Compromise(HistoryVaultRecoverySecret)
\Rightarrow
Compromise(VaultHistory).
]

Это не дефект, который можно убрать ещё одним KDF: R20.1 уже доказал, что возможность восстановления после уничтожения session keys требует отдельной recovery authority. Мы теперь просто сделали эту authority явной и независимой.

Следующая стадия поэтому уже не требует новой фундаментальной криптографической сущности:

[
\boxed{R21=\text{Security / Fuzzing / Local Hardening}}
]

Там следующий рост будет выводиться из конкретных оставшихся обязательств: безопасное хранение/экспорт recovery secret вместо raw JSON, rollback/fork/downgrade attacks на vault epochs, cross-vault substitution, malicious grants/rosters, ratchet skipped-key DoS, локальная защита drafts/DB/temp plaintext и проверяемый workflow `migrate → verify → delete legacy`. Реальный torrent networking по-прежнему можно оставить до самого конца.

Продолжил с **Stage 84** в той же theory-first / no-DSL линии.

### Stage 84 — EXACT_NOMINAL_VALUE_QUOTIENT

После full-power carrier `Y = X^k` остаётся более узкая проблема: relation `F : V^k ⇀ V` хранится по буквальным значениям цветов. Это делает результат зависимым от имён элементов палитры и заставляет abstain на новом имени даже тогда, когда сама обучающая relation доказывает взаимозаменяемость значений.

Stage 84 не вводит ни новый carrier, ни канал наблюдения, ни transform family. Вместо этого строится точная конечная группа

[
\mathrm{Aut}(R)={\sigma\in\mathrm{Sym}(V):\sigma R=R},
]

а `V^k → V` факторизуется по её орбитам. Свежие значения разрешаются только там, где quotient однозначно вынуждает extension; иначе остаётся exact abstention.

На официальном `007bbfb7` исходные пять train-пар действительно имеют геометрию `3×3 → 9×9`; полный fixture использован непосредственно для проверки.

Получилось:

* `k = 2`, `405` train output coordinates;
* `13` различных literal value keys, `0` конфликтов;
* точная `Aut(R)` содержит `24` автоморфизма;
* орбиты значений: `{0}` и `{2,4,6,7}`;
* quotient сжимает relation **13 → 4** правил, то есть `3.25×`;
* официальный test: **81/81 exact, 0 wrong**;
* test foreground заменён на новые, отсутствующие в training значения `1,3,5,8,9`: для каждого **81/81 exact**;
* **100/100** полных глобальных permutation-of-palette controls проходят exact;
* tuple из двух различных generic names, которого training не покрывает, даёт abstention;
* при двух независимых non-singleton value-orbits свежий цвет также даёт abstention: никакого nearest-orbit или heuristic fallback.

Это даёт новый точный результат: **literal-name dependence full-power ветки устранена на всех покрытых nominal orbits**. Одновременно получен no-go: если свежему значению соответствуют несколько структурно допустимых generic orbits, дополнительное различение действительно необходимо; система не имеет права выбрать одно из них по эвристике.

Локальная проверка: **10/10 tests PASS**, `compileall` clean. `ruff` и `mypy` в этом runtime отсутствуют, поэтому их прохождение я не заявляю. Полный Stage-83 working tree здесь не смонтирован, поэтому я также не стал выдумывать повторный результат всего 27-task regression: Stage 84 сделан как чистый add-only слой с явно описанным integration contract.

Следующий **Stage 85** теперь определяется достаточно жёстко: не расширять DSL, а провести `UNcovered nominal orbit census` на оставшихся changed-carrier residuals Stage 83. Если один и тот же uncovered nominal orbit требует разных outputs при одинаковом уже доступном source structure, это будет формальным necessity-certificate для следующего наблюдаемого отношения. Если противоречия нет, новый канал вводить нельзя.

Архивы:

* [USYS Stage 84 — AddOnly](sandbox:/mnt/data/USYS_Stage84_AddOnly.zip)
* [USYS Stage 84 — Theory](sandbox:/mnt/data/USYS_Stage84_Theory.zip)
* [USYS Stage 84 — Research Checkpoint](sandbox:/mnt/data/USYS_Stage84_Research_Checkpoint.zip)
* [SHA-256 архивов](sandbox:/mnt/data/USYS_Stage84_ARCHIVES.sha256)

В checkpoint входят исходники, exact automorphism engine, nominal quotient, full-power extractor/solver, официальный `007bbfb7` fixture, negative controls, тесты, `THEORY.md`, `REPORT.md` и машинный `stage84_report.json`.

Продолжил до `R21 — Security / Fuzzing / Local Hardening`.

[Скачать полный проект R21](sandbox:/mnt/data/EidoLang-Android-R21.zip)

Основные документы: [методология](sandbox:/mnt/data/eidolang_android_r21/R21_METHODOLOGY.md), [freshness no-go](sandbox:/mnt/data/eidolang_android_r21/R21_FRESHNESS_NO_GO.md), [recovery backup](sandbox:/mnt/data/eidolang_android_r21/R21_RECOVERY_BACKUP.md), [verified cutover](sandbox:/mnt/data/eidolang_android_r21/R21_CUTOVER.md), [local hardening](sandbox:/mnt/data/eidolang_android_r21/R21_LOCAL_HARDENING.md), [checkpoint](sandbox:/mnt/data/eidolang_android_r21/R21_CHECKPOINT.md), [validation](sandbox:/mnt/data/eidolang_android_r21/R21_VALIDATION.md), [machine-readable report](sandbox:/mnt/data/eidolang_android_r21/EXAMPLE_R21_VALIDATION_REPORT.json), [test summary](sandbox:/mnt/data/eidolang_android_r21/TEST_LOG_R21_SUMMARY.txt) и [fixed golden](sandbox:/mnt/data/eidolang_android_r21/golden/r21/r21_hardening_golden_v1.json).

Добавлен отдельный модуль:

```text
core-hardening/
```

Первый закрытый дефект — raw recovery secret. В R20.2 destructive recovery объективно требует отдельный высокоценный секрет, но хранить его в одном открытом JSON-файле было лишним single-point compromise.

Теперь используется:

[
\boxed{
EidoHistoryVaultRecoveryBackupV1
+
RecoveryBackupCode
}
]

где `RecoveryBackupCode` — отдельный случайный 256-битный ключ. Backup-файл содержит только AES-256-GCM ciphertext recovery secret.

То есть:

[
BackupFile\nRightarrow RecoverySecret,
]

и отдельно:

[
RecoveryCode\nRightarrow RecoverySecret.
]

Для восстановления нужны оба канала.

Я намеренно не ввёл произвольный password-KDF с выбранным «из головы» количеством итераций. Сейчас low-level recovery code предназначен для последующего QR/offline UX; парольная оболочка может быть добавлена уже после выбора измеренной Android policy.

Второй результат оказался фундаментальным. Формально доказано:

[
\boxed{
FreshOfflineDevice
+
ValidSignedArchives
+
NoTrustedAnchor
+
NoFreshnessOracle
}
]

не позволяет определить, какой из нескольких валидных подписанных архивов является последним.

Старая подписанная копия и новая подписанная копия обе удовлетворяют:

[
VerifySignature(P)=true.
]

Если атакующий просто скрывает новую копию, свежий offline device не имеет наблюдения, различающего:

[
P_{old}\text{ is latest}
]

и

[
P_{new}\text{ exists but is withheld}.
]

Поэтому R21 не маскирует это ложным security claim. Первый restore получает статус:

```text
BOOTSTRAP_FRESHNESS_UNPROVEN
```

После появления локальной непрерывности уже работает строгая защита rollback.

Введён:

[
\boxed{EidoVaultRollbackAnchorV1}.
]

Anchor фиксирует принятый prefix vault epochs, неизменяемые связи

[
MessageId\rightarrow EntryId,
]

известные device identities и последний roster epoch/ID каждого пользователя.

После этого новая версия обязана быть монотонным расширением. Машинно отвергаются удаление истории, изменение ранее принятого `EntryId`, rollback эпох, fork vault epoch, исчезновение ранее известного устройства, rollback roster epoch и fork roster на уже зафиксированной эпохе.

В тесте сначала был принят новый архив, затем ему предъявили совершенно корректный криптографически старый архив. Он отвергнут именно как rollback, а не как «невалидная подпись».

Отдельно построены две валидные ветви:

[
E_0\rightarrow E_{1a}
]

и

[
E_0\rightarrow E_{1b}.
]

До появления anchor обе криптографически допустимы. После anchoring (E_{1a}) попытка подставить (E_{1b}) отвергается как fork.

Закрыт и cutover старых R16/R19 архивов. Теперь переход:

[
Legacy
\rightarrow R20.2
\rightarrow DeleteLegacy
]

имеет проверяемое промежуточное доказательство.

Введён:

[
\boxed{EidoLegacyArchiveCutoverV1}.
]

До выпуска receipt вычисляется детерминированный history digest по строкам:

[
(
ConversationId,
MessageId,
Parents,
DocumentContentHash
).
]

Cutover разрешён только если source и recovered histories буквально совпадают.

Receipt подписывает:

[
VaultId,\quad
VaultPackageId,\quad
HistoryDigest,\quad
SHA256(LegacyArtifacts).
]

После локально committed cutover точная старая R19-копия по её SHA-256 уже не может быть повторно импортирована через legacy path.

Граница здесь сохранена: программа может доказать, что миграция проверена и конкретная локальная legacy-копия объявлена retired. Она не может доказать, что где-нибудь вне её контроля не существует ещё одной копии.

Нашлись и закрылись ещё два resource-DoS класса.

Для ratchet уже существовал `maxSkip`. Теперь явно проверено, что packet за пределом лимита:

[
messageNumber>maxSkip
]

не вызывает неконтролируемой генерации skipped keys, а receive state после отказа буквально возвращается к прежнему snapshot.

Кроме того, в R20.1 оставался неограниченный пул опубликованных, но ещё не использованных one-time prekeys. Теперь `JvmPreKeyStore` имеет:

```text
maxPendingOneTimePreKeys
```

и fail-closed отказывает при достижении лимита.

Само требование конечности — security invariant. Конкретное значение default остаётся operational policy, а не частью wire semantics.

Закрыта и одна простая, но неприятная локальная утечка: `DraftStore` раньше писал canonical `EidogramDocumentV1` в plaintext `.json`.

Теперь он использует:

```text
AndroidLocalSecretBox
AES-256-GCM
AndroidKeyStore
```

с alias:

```text
eidolang.local-sensitive-wrap.v1
```

и AAD:

[
(namespace,logicalId).
]

Старый plaintext draft мигрируется только после успешного canonical parse; затем пишется encrypted replacement, и удаление старого plaintext-файла обязательно для успешной миграции.

Этим же authenticated local storage защищаются rollback anchors и cutover receipts.

Я также прогнал adversarial corpus. В частности, проверены cross-vault substitution, изменение `MessageId` в entry header с пересчитанным `EntryId`, правильно подписанный exporter'ом, но усечённый архив с отсутствующим DAG parent, wrong recovery code и backup ciphertext tamper с пересчитанным object ID.

Кроме таргетированных атак сделано:

```text
128 single-character vault-package mutations
 96 single-character recovery-backup mutations
```

Все 224 были отвергнуты.

Новые результаты:

```text
R21 dynamic hardening          11 PASS
R21 fixed golden                6 PASS
R21 independent Python          3 PASS
---------------------------------------
R21 additions                  20 PASS
```

Independent Python отдельно воспроизвёл canonical SHA-256 backup identity, AES-GCM recovery-secret decryption, recovery-key identity, rollback-anchor SHA-256 и AEAD tamper rejection.

После изменений в `PreKeyStore`, repository boundary и новом hardening module я заново прогнал всю предыдущую pure-core границу:

```text
R20.2 dynamic                  12
R20.2 fixed                     6
Python R20.2                    3
R20.1 dynamic                  11
R20.1 fixed                     6
Python R20.1                    3
R20 multi-device               11
R20 recovery                    9
R20 fixed                       6
R19 dynamic                    12
R19 fixed                       3
R18                            14
R17                             5
R16 fixed                       5
Python BEP52                    2
Python BEP44                    3
R15 fixed                       7
R14.1                          13
--------------------------------
PRIOR                         131 PASS
```

Итого на финальном дереве:

[
\boxed{151\ PASS}.
]

Fixed R21 golden SHA-256:

```text
8088b67350694162f6851b3f0e8a19a382804fb3148ad6ef8d8f25f9d0735350
```

SHA-256 полного архива R21:

```text
3108878a98f52851fa673c903e64c558fb66b01a728868f6bcdc211e65f61ffc
```

Остаются две сознательно не замаскированные границы. Первая — свежий offline device не может доказать latestness без внешнего checkpoint/другого доверенного устройства. Вторая — SQLite metadata вроде aliases/titles пока не имеет page-level encryption; message contents при этом уже лежат как encrypted envelopes. Я не стал молча подключать SQLCipher или придумывать field-encryption migration без Android build/runtime проверки.

Поэтому следующий этап теперь:

[
\boxed{R22=\text{Product Completion / Android Admission}}
]

То есть core-протокол больше не надо расширять без нового контрпримера. Следующая работа — реальная Android сборка и KeyStore admission, onboarding второго устройства, recovery-code/QR UX, явный `FRESHNESS UNPROVEN` при fresh restore, решение оставшейся SQLite metadata boundary, `migrate → recover → compare → retire legacy` в UI и только после этого подключение реального R17 torrent/DHT transport. Сетевое исполнение по-прежнему не является блокирующим для остальных частей.

Продолжил до `R22 — Product Completion / Android Admission`.

[Скачать полный проект R22](sandbox:/mnt/data/EidoLang-Android-R22.zip)

Ключевые материалы: [checkpoint](sandbox:/mnt/data/eidolang_android_r22/R22_CHECKPOINT.md), [методология](sandbox:/mnt/data/eidolang_android_r22/R22_METHODOLOGY.md), [onboarding](sandbox:/mnt/data/eidolang_android_r22/R22_ONBOARDING.md), [SQLite v3](sandbox:/mnt/data/eidolang_android_r22/R22_SQLITE_V3.md), [Android admission](sandbox:/mnt/data/eidolang_android_r22/R22_ANDROID_ADMISSION.md), [product workflow](sandbox:/mnt/data/eidolang_android_r22/R22_PRODUCT_WORKFLOW.md), [validation](sandbox:/mnt/data/eidolang_android_r22/R22_VALIDATION.md), [machine-readable report](sandbox:/mnt/data/eidolang_android_r22/EXAMPLE_R22_VALIDATION_REPORT.json), [test summary](sandbox:/mnt/data/eidolang_android_r22/TEST_LOG_R22_SUMMARY.txt), [R22 acceptance manifest](sandbox:/mnt/data/eidolang_android_r22/golden/r22/r22_product_acceptance_v1.json) и [локальный Android admission runner](sandbox:/mnt/data/eidolang_android_r22/tools/r22-android-admission.sh).

Важное изменение: в R22 я **не ввёл нового wire-протокола**. Рост теперь действительно остановлен на уровне криптографической архитектуры:

[
\boxed{NewWireEntity=0}
]

R22 собирает уже доказанные R14–R21 слои в работающий Android product flow.

Главный дефект первого запуска закрыт. Раньше приложение сразу выполняло `AndroidKeystoreIdentityStore.ensure()`, то есть на любой новой установке автоматически создавало новый user root. Это делало R20 secondary-device enrollment фактически недоступным из продукта.

Теперь первый запуск явно предлагает:

```text
Создать основной профиль
```

или:

```text
Подключить как дополнительное устройство
```

Добавлен `loadIfPresent()`, который не создаёт root key.

Secondary-device flow теперь реально проведён через UI:

[
OwnerPublicIdentity
\rightarrow
EnrollmentRequest
\rightarrow
RootAuthorization
\rightarrow
DeviceBundle
\rightarrow
RootSignedRoster
\rightarrow
Messenger.
]

Root private key на secondary device не переносится.

На primary появился экран `Устройства`, который позволяет импортировать `EidoDeviceEnrollmentRequestV1`, проверить possession proof, выдать существующий R20 `DeviceCertificateV1`, выпустить следующий roster, экспортировать authorized device bundle и roster, а также отзывать другое устройство.

То есть:

[
Enrollment,\ Authorization,\ Revocation
]

теперь являются не просто core API, а частью приложения.

Отдельно исправлена обнаруженная Android-source ошибка: в `AndroidKeystoreIdentityStore.ensure()` было повторное объявление `rootPublic`, которое pure-Kotlin тесты естественно не могли увидеть, поскольку Android source раньше не компилировался в этом окружении.

Архивный UI также переведён на основную R20.2/R21 модель. Теперь доступны:

```text
Secure export
Recovery backup
Secure restore
Мигрировать R19 → secure vault
```

Secure restore требует три независимые вещи:

[
VaultPackage
+
EncryptedRecoveryBackup
+
RecoveryCode.
]

При fresh-offline restore UI больше не делает ложного заявления о том, что архив «последний». Если отсутствует trusted rollback checkpoint, пользователь получает буквально:

```text
FRESHNESS UNPROVEN
```

и должен явно подтвердить восстановление.

То есть no-go R21 теперь дошёл до product UX:

[
SignatureValid
\not\Rightarrow
LatestKnown.
]

После первого принятия сохраняется rollback anchor, и последующая подстановка старого или forked архива уже отвергается.

Legacy migration теперь также интегрирована:

[
R19
\rightarrow
restore
\rightarrow
R20.2
\rightarrow
recover
\rightarrow
compare
\rightarrow
signed\ cutover.
]

После сохранения cutover receipt точные bytes R19-файла, объявленного retired, блокируются legacy-import path по SHA-256.

Остаётся честная граница: программа не способна доказать, что пользователь не оставил ещё одну копию этого R19 где-нибудь вне её контроля.

Закрыта и SQLite presentation-data boundary. Схема поднята:

[
v2\rightarrow v3.
]

Теперь `contacts.alias` и `conversations.title` не лежат plaintext.

Добавлен:

```text
RepositoryTextProtector
AndroidRepositoryTextProtector
```

на базе существующего AndroidKeyStore/AES-GCM `AndroidLocalSecretBox`.

AAD привязан к конкретной строке:

[
Alias:
(userId,deviceId)
]

[
Title:
conversationId.
]

При миграции существующие v2 значения шифруются in-place. v3 reader fail-closed отклоняет строку, если alias/title неожиданно остались plaintext.

Полного page-level SQLite encryption я не объявляю: IDs, timestamps, sender sequence и DAG metadata всё ещё являются индексируемыми полями базы.

Ещё два Android hardening изменения:

```text
android:allowBackup="false"
android:usesCleartextTraffic="false"
```

Автоматический platform backup отключён, чтобы Android сам не восстанавливал половину состояния, отделённую от наших KeyStore/recovery domains.

Добавлен отдельный:

```text
core-admission/
```

и экран:

```text
Диагностика → Android admission
```

Runtime probe на реальном устройстве проверяет:

```text
identity.bundle
identity.sign
identity.rsa_oaep
local.secret_box
vault.secret_store
repository.sensitive_text
```

То есть после установки на Android мы получаем не просто «приложение открылось», а конкретный admission predicate:

[
\boxed{AndroidAdmissionReport.passed=true}.
]

Pure product journey R22 дал:

```text
11 PASS
```

В нём единым сценарием проходят:

[
Primary
\rightarrow
Message
\rightarrow
SecondaryEnrollment
\rightarrow
Vault
\rightarrow
RecoveryBackup
\rightarrow
SecondaryRestore
\rightarrow
ContinueDAG
\rightarrow
Revocation
\rightarrow
NewVaultEpoch
\rightarrow
Cutover.
]

Fixed cross-stage composition R20.2 + R21:

```text
5 PASS
```

Проверено, что старые fixed vectors действительно образуют один product recovery chain: тот же `VaultId`, тот же recovery-key identity, тот же `MessageId`, document hash, `EntryId`, `PackageId` и rollback anchor.

Android source audit:

```text
13 PASS
```

Он проверяет onboarding, secondary authorization, revocation, secure archive UI, freshness warning, cutover enforcement, SQLite v3, encrypted drafts, admission probe, отключённый auto-backup и запрет cleartext.

После изменений я также заново прогнал всю предыдущую pure границу R14.1–R21:

```text
PRIOR R14.1–R21   151 PASS
R22 additions      29 PASS
---------------------------
TOTAL              180 PASS
```

Это именно выполненные проверки. Android runtime сюда не приписан.

R22 acceptance manifest SHA-256:

```text
509b23904b580b0e03b229a043bfaf7ef8bd92b15ac4eb6b4aae71a339492dda
```

SHA-256 полного архива:

```text
50c9c8e8fe686905e2423231e2c6619049cfd9a7929f42dd110c78d6b9d0518d
```

Точная стадия сейчас:

[
\boxed{
SOURCE\ PRODUCT\ INTEGRATION\ COMPLETE
}
]

но не:

[
AndroidRuntimeAdmission=PASS.
]

В текущем контейнере нет Android SDK, `ANDROID_HOME`, `adb`, system Gradle и `gradle-wrapper.jar`, поэтому заявлять APK build или AndroidKeyStore runtime pass было бы некорректно.

Подготовлен [r22-android-admission.sh](sandbox:/mnt/data/eidolang_android_r22/tools/r22-android-admission.sh), который в нормальном Android окружении выполняет `assembleDebug`, instrumentation tests, установку APK и после этого оставляет только in-app `ADMISSION PASS`.

Следующая стадия теперь очень чёткая:

[
\boxed{R22.1=\text{Android Device Admission}}
]

Это уже не этап изобретения архитектуры. Нужно собрать приложение на реальном Android toolchain, прогнать KeyStore/SQLite migration, первичную и secondary установку, secure recovery, revocation и cutover на двух инсталляциях. Реальный torrent/DHT по-прежнему можно не трогать до завершения этого admission.

Продолжил до `R22.1 — Reproducible Android Device Admission Pack`.

[Скачать полный проект R22.1](sandbox:/mnt/data/EidoLang-Android-R22.1.zip)

SHA-256 архива:

```text
0a0cb5e9fdc45ec2b5a8a5d8e9d81f844bb0b936f1b9bc480907e5978472adb2
```

На этой стадии я не добавлял новый криптографический или сетевой протокол. Задача R22.1 — сделать последний Android-gate воспроизводимым и получить переносимое доказательство результата реального device admission.

Основные артефакты:

* [R22.1 methodology](sandbox:/mnt/data/eidolang_android_r22_1/R22_1_METHODOLOGY.md)
* [checkpoint](sandbox:/mnt/data/eidolang_android_r22_1/R22_1_CHECKPOINT.md)
* [validation](sandbox:/mnt/data/eidolang_android_r22_1/R22_1_VALIDATION.md)
* [toolchain manifest](sandbox:/mnt/data/eidolang_android_r22_1/R22_1_TOOLCHAIN_MANIFEST.json)
* [toolchain bootstrap](sandbox:/mnt/data/eidolang_android_r22_1/tools/r22_1-bootstrap-android-toolchain.py)
* [Android admission runner](sandbox:/mnt/data/eidolang_android_r22_1/tools/r22_1-android-admission.sh)
* [admission evidence spec](sandbox:/mnt/data/eidolang_android_r22_1/R22_1_ADMISSION_EVIDENCE.md)
* [evidence verifier](sandbox:/mnt/data/eidolang_android_r22_1/tools/verify-r22_1-admission-evidence.py)
* [machine-readable report](sandbox:/mnt/data/eidolang_android_r22_1/EXAMPLE_R22_1_VALIDATION_REPORT.json)
* [test summary](sandbox:/mnt/data/eidolang_android_r22_1/TEST_LOG_R22_1_SUMMARY.txt)
* [measured host environment](sandbox:/mnt/data/eidolang_android_r22_1/R22_1_HOST_ENVIRONMENT.json)

Я заново проверил toolchain pins по текущей официальной документации. Для AGP 9.3.0 официальный compatibility table задаёт maximum API 37, Gradle 9.5.0, default SDK Build Tools 36.0.0 и JDK 17. ([Android Developers][1]) Compose documentation по-прежнему соответствует нашим pin'ам Kotlin/Compose compiler `2.3.21` и Compose BOM `2026.06.00`. ([Android Developers][2]) Android 17 использует `compileSdk = 37`, а официальный Linux command-line tools package сейчас `commandlinetools-linux-15859902_latest.zip` с SHA-256 `4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583`. ([Android Developers][3])

Bootstrap теперь не просто «скачай SDK». Он детерминированно устанавливает:

```text
platform-tools
platforms;android-37
build-tools;36.0.0
```

и отдельно загружает Gradle `9.5.0`. Наличие этой pinned distribution подтверждается официальным Gradle distributions index. ([Gradle Services][4]) Скрипт получает официальный sibling `.sha256`, проверяет ZIP, генерирует `gradle-wrapper.jar` и записывает тот же hash в `distributionSha256Sum`.

SDK licenses намеренно не принимаются скрыто. Автоматическое `sdkmanager --licenses` разрешается только через явный:

```bash
--accept-sdk-licenses
```

После bootstrap основной execution gate стал:

```bash
./tools/r22_1-android-admission.sh --bootstrap
```

Он должен выполнить:

```text
:app:assembleDebug
:app:lintDebug
:app:testDebugUnitTest
```

а при наличии `adb`-device:

```text
:app:connectedDebugAndroidTest
adb install -r app-debug.apk
```

Messenger/torrent network для этого по-прежнему не нужен.

В R22 существовал runtime probe, но его результат оставался только на экране. Я закрыл этот разрыв новой **локальной диагностической** сущностью:

```text
EidoAndroidAdmissionEvidenceV1
```

Это не wire-протокол и не часть message/archive semantics.

Теперь после:

```text
Эйдограммы
→ Диагностика
→ Запустить admission probe
```

можно нажать:

```text
Экспортировать evidence
```

Evidence содержит SDK level, device ID, полный отсортированный набор admission checks и aggregate PASS. Его identity:

[
EvidenceId=
SHA256(CanonicalJSON(EvidenceBody)).
]

Отдельный Python verifier требует присутствия и успешности именно:

```text
identity.bundle
identity.sign
identity.rsa_oaep
local.secret_box
vault.secret_store
repository.sensitive_text
```

и независимо пересчитывает `EvidenceId`.

Для самого verifier сделан явно маркированный test-only golden. Он дал:

```text
PASS canonical evidence SHA-256 identity
PASS all Android admission checks passed
PASS required R22 runtime checks present
```

Это специально **не** объявляется Android runtime evidence.

Финальный R22.1 validation в текущем окружении:

```text
R22.1 preflight                         13 PASS
R22.1 modified-tree Android source     14 PASS
R22.1 evidence verifier                 3 PASS
R22.1 utility syntax                     2 PASS
R22.1 bootstrap dry-run                 DRY_RUN_OK
--------------------------------------------------
R22.1 executed PASS lines              32
+ bootstrap dry-run gate                1
```

Предыдущий R22 сохраняет свои `180 PASS`, но я не стал нечестно объявлять их заново прогнанным Android runtime regression.

Есть и точный измеренный blocker. В текущем контейнере:

```text
ANDROID_HOME          unset
ANDROID_SDK_ROOT      unset
adb                   missing
gradle                missing
gradle-wrapper.jar    missing
Java                  available
```

Кроме того, попытка непосредственно скачать Gradle binary из контейнера упёрлась в DNS resolution. Поэтому:

[
\boxed{AndroidBuildExecuted=false}
]

не из-за исходников проекта, а потому что здесь фактически отсутствует Android execution toolchain.

Текущий статус теперь сформулирован точнее:

[
\boxed{
SOURCE_ADMISSION_READY
}
]

а закрывающий predicate:

[
\boxed{
assembleDebug
\land lintDebug
\land unitTests
\land instrumentation
\land AndroidAdmissionEvidence.passed
}
]

Следующий шаг уже не требует новой стадии проектирования. Это чистое исполнение `R22.1` на Android-capable машине и получение реального экспортированного `AndroidAdmissionEvidenceV1`. После него локальный Android stack можно считать допущенным; реальный R17 torrent/DHT transport всё ещё можно оставить последним независимым gate.

[1]: https://developer.android.com/build/releases/agp-9-3-0-release-notes "https://developer.android.com/build/releases/agp-9-3-0-release-notes"
[2]: https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler?hl=en "https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler?hl=en"
[3]: https://developer.android.com/studio "https://developer.android.com/studio"
[4]: https://services.gradle.org/distributions/ "https://services.gradle.org/distributions/"
