Тогда фиксирую первую инженерную спецификацию как `EidoLang-Mobile v0.1`. Я сознательно сделаю её минимальной: мы пока не кодируем «значение треугольника», «смысл красного» и т. п. Язык должен быть выведен из наблюдаемых повторяющихся преобразований состояния, а не придуман нами заранее.

В статье для этого есть три опорных требования. Эйдограмма определяется как развертка смысла, а не обычный знак.  Её форма возникает последовательным размещением элементов с постоянной сверкой возникающего целого с переживаемым смыслом.  И принципиальна сама история построения: порядок внесения элементов и их взаимодействие со средой являются частью операции над смыслами. 

Отсюда архитектура получается почти вынужденно.

## 1. Что является настоящим первичным объектом

Не изображение PNG.

Не «символ».

Не распознанная фигура.

Первичным является событие взаимодействия:

[
e_t=(t,;p_t,;a_t,;c_t),
]

где

* (t) — время;
* (p_t) — состояние указателя/пальца;
* (a_t) — действие;
* (c_t) — состояние мультимедийной среды приложения.

Полная эйдограмма — это history:

[
\boxed{
\Gamma=e_1e_2\ldots e_n.
}
]

Из (\Gamma) можно получить финальную картинку:

[
Render(\Gamma)=E_n,
]

но обратное преобразование невозможно:

[
E_n\nrightarrow\Gamma.
]

Поэтому bitmap — только представление. Истинный объект языка — траектория построения.

Это совпадает с нашим RRK:

[
d_1\rightarrow d_2\rightarrow\cdots\rightarrow d_n.
]

---

## 2. Система должна быть event-sourced

На смартфоне сохраняем неизменяемый поток сырых событий.

Минимальный тип:

```text
PointerEvent :=
    START
  | MOVE
  | END
  | CANCEL
```

Запись:

```json
{
  "type": "MOVE",
  "t_us": 1843201,
  "pointer_id": 0,

  "x": 0.4173,
  "y": 0.6831,

  "pressure": 0.62,
  "contact_major": 0.031,
  "contact_minor": 0.019,

  "tilt_x": null,
  "tilt_y": null
}
```

Координаты сразу нормируем:

[
x,y\in[0,1]^2,
]

чтобы язык не зависел от размера экрана.

`pressure`, tilt и размеры контакта — optional: разные смартфоны дают разные сенсорные данные.

Сырое событие после записи **никогда не изменяется**.

Все последующие:

* сегментация,
* распознавание,
* embedding,
* классификация,
* поиск операторов

являются производными объектами с указанием версии алгоритма.

Это критически важно: иначе ИИ со временем перепишет нам собственные исходные данные.

---

# 3. Мультимедиа тоже входит в history

Второй поток:

```text
MediaEvent :=
    COLOR
  | AUDIO
  | HAPTIC
  | ANIMATION
  | BACKGROUND
  | SILENCE
```

Например:

```json
{
  "type": "HAPTIC",
  "t_us": 1843210,
  "pattern_id": "haptic.v0.weak_pulse",
  "duration_ms": 35
}
```

или:

```json
{
  "type": "AUDIO",
  "t_us": 2013488,
  "generator": "tone",
  "frequency_hz": 218.4,
  "amplitude": 0.18,
  "duration_ms": 190
}
```

Таким образом, session history:

[
\Gamma=
\Gamma_{\rm gesture}
\cup
\Gamma_{\rm visual}
\cup
\Gamma_{\rm audio}
\cup
\Gamma_{\rm haptic}.
]

С самого начала получаем не графический, а мультимодальный язык.

---

# 4. Не фиксируем алфавит вручную

Это первая существенная поправка к моей предыдущей версии.

Я предлагал заранее:

[
{\text{точка, линия, дуга, контур},\ldots}.
]

Для UI это допустимо, но для **языка** — слишком рано.

Правильнее:

[
\Gamma_{\rm raw}
\xrightarrow{Segmenter}
(s_1,\ldots,s_k).
]

Каждый (s_i) — автоматически выделенный кусок траектории.

Для него можно вычислить чисто геометрические характеристики:

[
f(s)=
(
L,,
\Delta\theta,,
\kappa,,
v,,
a,,
A,,
closure,\ldots
).
]

Но пока не давать имени вроде «треугольник».

Затем кластеризуем:

[
s_i\sim s_j
]

по устойчивой структурной близости.

И только если кластер воспроизводится:

[
\boxed{
\alpha_k=[s]_{\sim}
}
]

становится элементом персонального алфавита.

То есть алфавит:

[
\mathcal A_1
============

{\alpha_1,\ldots,\alpha_K}
]

**обнаруживается**, а не проектируется.

---

# 5. Между атомами нужен второй язык — отношения

Сам элемент недостаточен.

Нужно хранить:

[
R(\alpha_i,\alpha_j).
]

Но и здесь лучше не заводить руками список вроде «внутри/напротив».

Первичный relation descriptor вычисляем из геометрии:

[
r_{ij}=
(
\Delta x,
\Delta y,
d,
overlap,
intersection,
containment,
angle,
temporal_gap,\ldots).
]

После накопления корпуса снова факторизуем:

[
r_{ij}\sim r_{kl}.
]

Получаем открываемые системой relation-atoms:

[
\mathcal R=
{\rho_1,\rho_2,\ldots}.
]

Тогда состояние эйдограммы — уже граф:

[
\boxed{
E_t=(V_t,R_t)
}
]

с элементами (V_t) и отношениями (R_t).

---

# 6. Но настоящая фраза языка — это преобразование графа

Пусть:

[
E_t=(V_t,R_t).
]

Один эйдографический акт задаёт:

[
E_t\xrightarrow{a_t}E_{t+1}.
]

То есть «слово» языка лучше определить не через статическую форму, а как operator:

[
\boxed{
a:E\rightarrow E'.
}
]

Последовательность:

[
w=a_1a_2\ldots a_n
]

становится программой:

[
T_w=
T_{a_n}\circ\cdots\circ T_{a_1}.
]

И это уже действительно напоминает `tzeruf`, но без заранее принятой сакральной семантики.

---

# 7. Субъектное состояние хранится отдельным потоком

Нам нужен:

```text
StateObservation
```

а не один «настроение до/после».

Минимальная структура:

```json
{
  "t_us": 0,

  "self_report": {
    "activation": 0.31,
    "valence": 0.12,
    "clarity": 0.58,
    "inner_noise": 0.71,
    "imagery": 0.44,
    "focus": 0.37
  },

  "confidence": 0.78,

  "physiology": null
}
```

Но эти шесть шкал не являются онтологией сознания. Это временный измерительный интерфейс.

Сохраняем отдельно:

[
m_t
]

— неизвестное реальное состояние,

и

[
o_t
]

— наблюдаемый proxy.

То есть:

[
o_t=R(m_t)+\epsilon.
]

Позднее self-report можно дополнить:

* динамикой жестов;
* голосом;
* дыханием;
* HRV;
* другими доступными сигналами.

И строить:

[
\hat m_t=\Psi(o_{0:t}).
]

---

# 8. Центральный тип данных — `EidoSession`

Я бы зафиксировал его так:

```text
EidoSession {
    session_id
    protocol_version

    started_at
    duration

    intent
    target_state?          // optional

    raw_events[]
    media_events[]
    state_observations[]

    context_ref

    derived {
        segmentation_version
        segments[]
        relation_graphs[]
        embeddings[]
    }

    outcome
}
```

Важно наличие:

```text
target_state?
```

с `?`.

В режиме `Free Unfold` цели нет вообще.

В режиме целевого самопреобразования она присутствует.

---

# 9. Вводим четыре режима сессии

```text
FREE
CAPTURE
TRANSFORM
RECALL
```

`FREE`:

[
m_0\rightarrow\Gamma\rightarrow m_1.
]

Никакой цели.

`CAPTURE`:

попытка как можно точнее развернуть текущее (m_0).

`TRANSFORM`:

[
m_0\xrightarrow{\Gamma}G.
]

Есть целевой класс состояния (G).

`RECALL`:

запускается ранее обнаруженный macro-operator.

Пятый режим `LATENT_SEED` я пока **не включал бы в ядро**, а добавил как источник seed позже.

Это убережёт v0.1 от зависимости от конкретной нейросети.

---

# 10. Теперь главный объект: `EidoOperator`

После корпуса сессий система обнаруживает последовательность:

[
w=(a_i,\ldots,a_j)
]

и проверяет, имеется ли устойчивый переход:

[
m_{\rm in}\rightarrow m_{\rm out}.
]

Тип:

```text
EidoOperator {
    operator_id

    pattern
    pattern_version

    domain
    codomain

    support_count

    transition_model

    effect_estimate
    uncertainty

    contexts[]
    counterexamples[]

    evidence_refs[]
}
```

Это принципиально: обязательно храним **контрпримеры**.

Если оператор пять раз помог и трижды не помог, система не имеет права превратить его в «знак спокойствия».

---

# 11. Формальное условие принятия macro-operator

Пусть:

[
B\subseteq M
]

— класс начальных состояний.

[
G\subseteq M
]

— целевой класс.

Для слова (w):

[
p_w(B,G)
========

P(m_{\rm out}\in G
\mid
m_{\rm in}\in B,w).
]

Нужен baseline:

[
p_0(B,G).
]

Тогда:

[
\boxed{
\Delta_w(B,G)
=============

p_w(B,G)-p_0(B,G).
}
]

Но одной (\Delta>0) мало.

Принимаем оператор только при:

[
support(w)\ge N_{\min},
]

[
\Delta_w>\delta_{\min},
]

и достаточной устойчивости при изменении контекста.

И обязательно:

[
Counterexamples(w)
]

не пустой служебный объект.

Так язык будет proof/evidence-carrying.

---

# 12. «Имя» появляется только на следующем уровне

Разные последовательности:

[
w_1,w_2,w_3
]

могут давать один устойчивый переход.

Если:

[
T_{w_1}
\simeq
T_{w_2}
\simeq
T_{w_3}
]

на заданном domain (B), то определяем:

[
\boxed{
N=[w]_{\rm op}.
}
]

Вот это уже `Name`.

Тип:

```text
MacroName {
    name_id

    operator_equivalence_class[]

    domain
    effect

    invariants
    known_failures

    evidence
}
```

Не пользователь придумывает:

> «вот этот символ означает X».

Система обнаруживает:

> эти разные физические последовательности обладают одним и тем же устойчивым операторным эффектом.

Это гораздо сильнее.

---

# 13. UTC не надо сейчас переписывать

Я бы сознательно не создавал новую версию `AddressTuple`, потому что у UTC уже есть собственная адресная модель.

Добавляем адаптер:

```text
UTCBinding {
    mem_object_id
    utc_address
    source
    confidence
}
```

А сам `EidoOperator` может содержать:

```text
utc_bindings[]
```

То есть:

[
EidoOperator
\leftrightarrow
MemObject.
]

В терминах мем-машины это естественно:

[
\boxed{
\text{устойчивый оператор состояния}
====================================

\text{особый тип mem-object}.
}
]

Причём вместо хранения только content мы теперь знаем ещё и его transition semantics:

[
m_{\rm in}\rightarrow m_{\rm out}.
]

Это очень важное обогащение UTC.

---

# 14. Минимальный DSL

DSL нужен прежде всего как переносимый машинный формат, а не язык, который человек должен печатать.

Предлагаю:

```text
eidolang 0.1

session <id> mode <MODE> {
    state.in  <state-ref>

    event <event-ref>*
    
    state.out <state-ref>

    derive <derivation-ref>*
}
```

Например:

```text
eidolang 0.1

session s:8f01 mode TRANSFORM {
    state.in  m:410
    target    g:focus

    event stroke:104
    event pause:105
    event stroke:106
    event haptic:107
    event stroke:108

    state.out m:411
}
```

На следующем уровне:

```text
operator op:27 {
    domain  state.cluster:12
    effect  state.cluster:4

    pattern [
        gesture.cluster:8
        relation.cluster:3
        gesture.cluster:15
    ]

    support 43
    confidence 0.91

    evidence [
        session:s1
        session:s8
        session:s31
    ]

    counterexamples [
        session:s19
        session:s42
    ]
}
```

И далее:

```text
name name:7 {
    equivalent [
        operator:27
        operator:31
        operator:88
    ]

    effect state.transition:focus
}
```

Получается довольно чистая иерархия:

[
\boxed{
raw\ event
\rightarrow
gesture
\rightarrow
relation
\rightarrow
operator
\rightarrow
Name.
}
]

---

# 15. Рекомендательная система не должна генерировать рисунок

Это принципиально.

Её интерфейс:

[
R:
(m_t,E_t,G)
\rightarrow
{a_1,\ldots,a_k}.
]

Она возвращает несколько кандидатов **следующего действия**.

Например:

```json
{
  "session": "s:8f01",
  "state": "m:410",

  "suggestions": [
    {
      "operator_ref": "op:27",
      "next_action_class": "gesture.cluster:8",
      "expected_gain": 0.31,
      "uncertainty": 0.08
    },
    {
      "operator_ref": "op:88",
      "next_action_class": "relation.cluster:3",
      "expected_gain": 0.22,
      "uncertainty": 0.11
    }
  ]
}
```

Человек выбирает либо игнорирует предложение.

Система не рисует за него.

Иначе замыкается:

[
AI\rightarrow E\rightarrow O
]

вместо нужного:

[
O\rightarrow E\rightarrow O'.
]

---

# 16. Очень важен режим `NO_RECOMMENDATION`

Каждая сессия должна содержать:

```text
recommendation_mode :=
    NONE
  | PASSIVE
  | ACTIVE
```

`NONE` — никаких подсказок.

`PASSIVE` — предложения доступны только по запросу.

`ACTIVE` — система сама показывает следующий возможный ход.

Без `NONE` мы очень быстро построим язык самой рекомендательной модели, а не пользователя.

---

# 17. Латентное пространство LLM подключаем через отдельный мост

Не:

[
LLM\ latent=meaning.
]

А:

[
\Phi_{\rm MM}(x)=z.
]

После этого отдельный обучаемый адаптер:

[
A:z\rightarrow Z_E.
]

Где (Z_E) — латентное пространство **реального корпуса эйдограмм пользователя**.

Нужны пары:

[
(x_i,\Gamma_i).
]

Например пользователь:

1. говорит состояние голосом;
2. пишет короткий текст;
3. рисует эйдограмму.

Получаем:

[
z_i^{LLM}
=========

\Phi(x_i),
]

[
z_i^E
=====

\Psi(\Gamma_i).
]

И учим отображение:

[
A(z_i^{LLM})\approx z_i^E.
]

Тогда мультимодальная модель становится не авторитетом значения, а **переводчиком между двумя представлениями**.

---

# 18. `LatentSeed` должен быть отдельным типом

```text
LatentSeed {
    seed_id

    source_modalities[]
    source_embedding

    target_eido_embedding

    proposed_constraints {
        region?
        rhythm?
        density?
        action_family?
    }

    model_id
    adapter_version
    uncertainty
}
```

Ключевое слово — `constraints`.

LLM не возвращает:

> нарисуй вот эту картинку.

Она говорит:

> ближайшая область вашего собственного эйдографического пространства имеет такие структурные характеристики.

И пользователь сам производит (\Gamma).

---

# 19. Пространство эйдограмм надо обучать отдельно

Нужен энкодер:

[
\Psi_E:
\Gamma\rightarrow z_E.
]

Причём ему недостаточно финального bitmap.

Он должен видеть временную последовательность:

[
(x_t,y_t,v_t,p_t,\ldots).
]

То есть естественная архитектура — sequence encoder плюс graph encoder текущего изображения:

[
z_E
===

Fuse(
Encoder_{\rm trajectory}(\Gamma),
Encoder_{\rm graph}(E_n)
).
]

Так модель различит две визуально одинаковые картинки, созданные разными процессами.

И это непосредственно следует из статьи: история и способ помещения смысла в контекст являются частью операции. 

---

# 20. Первый recommender вообще можно сделать без нейросети

Это методологически полезно.

Для текущего состояния (m) и цели (G):

1. находим похожие исторические состояния;

[
N_k(m)={m_i};
]

2. смотрим, какие действия следовали;

3. оцениваем их empirical transition gain;

[
\Delta(a|m,G);
]

4. выдаём top-(k).

То есть:

[
\boxed{
a^*=
\arg\max_a
\widehat{\Delta}(a|m,G).
}
]

Так первая система будет полностью интерпретируема.

Нейросеть можно добавить, когда станет ясно, что nearest-neighbor модель действительно недостаточна.

---

# 21. Нам нужен встроенный экспериментальный режим

Каждая рекомендация должна логировать:

```text
RecommendationTrace {
    current_state
    current_eidogram

    candidate_actions[]

    selected_action?
    ignored?

    expected_transition

    actual_transition
}
```

Так мы сможем вычислять:

[
Calibration:
P(\text{success}\mid\hat p)\approx\hat p.
]

Если recommender говорит (0.8), а успешны только 30% случаев, он не понимает язык.

---

# 22. Групповой язык появляется факторизацией личных языков

Пусть:

[
L_i
]

— язык пользователя (i).

Не надо сливать формы напрямую.

Ищем операторные соответствия:

[
F_{ij}:L_i\rightarrow L_j
]

такие, что:

[
Effect_i(w)
\simeq
Effect_j(F_{ij}(w)).
]

Тогда общий элемент определяется не одинаковой картинкой, а одинаковой **функцией**.

Это чрезвычайно важное отличие.

Два человека могут рисовать совершенно разные эйдограммы, но если они реализуют один переход:

[
B\rightarrow G,
]

то на более глубоком уровне это один оператор.

Так можно построить:

[
\boxed{
L_{\rm common}
==============

\operatorname{factor}
{L_1,\ldots,L_n}.
}
]

Это уже напрямую связано и с пространством смыслов S, и с UTC.

---

# 23. Минимальная схема хранения

На диске я бы сейчас заморозил:

```text
/eidolang/
    manifest.json

    /sessions/
        <session_id>.json

    /events/
        <session_id>.events

    /media/
        ...

    /states/
        <state_id>.json

    /derived/
        /segments/
        /graphs/
        /embeddings/

    /operators/
        <operator_id>.json

    /names/
        <name_id>.json

    /utc/
        <binding_id>.json

    /models/
        registry.json
```

Сырые события append-only.

`derived/` можно полностью удалить и пересчитать.

Это хороший критерий архитектуры:

[
\boxed{
Raw + algorithms
\Rightarrow
all\ derived\ artifacts.
}
]

---

# 24. Что именно войдёт в v0.1

Я бы жёстко заморозил scope.

`v0.1` умеет:

1. рисовать пальцем;
2. писать timestamped raw trajectory;
3. записывать визуальные/аудио/haptic events;
4. иметь `FREE/CAPTURE/TRANSFORM/RECALL`;
5. фиксировать state до/после;
6. автоматически сегментировать trajectory;
7. строить session embedding;
8. находить похожие сессии;
9. искать кандидаты macro-operators;
10. сохранять доказательства и контрпримеры;
11. выдавать один следующий кандидатный ход;
12. иметь UTC adapter.

Не входит пока:

* коллективный язык;
* генеративная LLM;
* автоматические мистические интерпретации;
* внешний-world control;
* физиологические вмешательства;
* утверждение, что найденный оператор универсален.

---

## 25. И главный критерий успеха v0.1

Не количество нарисованных картинок.

Не красота.

Не субъективная необычность опыта.

Проверяем:

[
\boxed{
\exists w,;B,;G:
P(m_{out}\in G\mid m_{in}\in B,w)

>

P(m_{out}\in G\mid m_{in}\in B)
}
]

причём эффект должен повторяться.

Если таких (w) нет — гипотеза языка устойчивых операторов на данном уровне не подтверждается.

Если они появляются, следующий вопрос:

[
w_1\sim_{\rm op}w_2?
]

Если да — впервые возникает настоящий операторный словарь.

Если затем одни и те же operator classes обнаружатся у разных людей при разных изображениях, это будет ещё более сильный результат:

[
\boxed{
\text{форма вариативна,
операторный смысл инвариантен}.
}
]

И именно в этой точке `EidoLang` начинает очень естественно сходиться с UTC, мем-машиной и формальным пространством смыслов S.

Следующую итерацию я бы уже сделал не концептуальной: **`EidoLang-Mobile v0.1-R1` — конкретная архитектура приложения и Python-ядра**, с классами `EidoSession`, `RawEvent`, `StateObservation`, `EidoOperator`, `MacroName`, автоматическим сегментатором и первым детерминированным operator-miner/recommender. Это будет уже практически реализуемая спецификация, после которой можно писать код.

Готово. Я собрал `EidoLang-Mobile v0.1-R1` как рабочий минимальный прототип, а не только спецификацию.

Архитектура следует ключевому свойству статьи: эйдограмма строится как последовательная развёртка, где новое размещение элемента согласуется с уже возникшим целым и текущим смысловым состоянием.  Поэтому первичным объектом в R1 является не финальная картинка, а вся траектория её построения. Это также сохраняет идею статьи, что история и способ помещения смыслов в новый контекст являются частью самой операции. 

[Скачать EidoLang-Mobile v0.1-R1](sandbox:/mnt/data/EidoLang-Mobile_v0.1-R1.zip)

В архиве уже есть Python-ядро, минимальный смартфонный web-capture и тесты. Основная цепочка реализована так:

[
RawEvent
\rightarrow
StrokeSegment
\rightarrow
RelationDescriptor
\rightarrow
Token
\rightarrow
EidoOperator
\rightarrow
Recommendation.
]

Ключевое решение R1: я **не ввёл заранее алфавит эйдограмм**. В ядре нет «круг = X», «красный = Y» или даже семантической сущности «треугольник». Физический `START…END` stroke является первичным сегментом; его форма получает только каноническую геометрическую сигнатуру. Более высокий алфавит должен возникнуть позже как выведенный quotient, если разные физические штрихи окажутся эквивалентны по операторному эффекту.

Сырые данные сделаны event-sourced и append-only. Записываются нормированные координаты пальца, monotonic timestamps, pressure/contact/tilt при наличии, а архитектура уже предусматривает `AUDIO`, `HAPTIC`, `COLOR`, `ANIMATION` и другие мультимедийные события. Поэтому статическая картинка принципиально не является полным представлением эйдограммы:

[
Render(\Gamma)=E,
\qquad
E\nRightarrow\Gamma .
]

Реализованы `EidoSession`, `RawEvent`, `StateObservation`, `StrokeSegment`, `RelationDescriptor`, `EidoOperator`, `MacroName`, `UTCBinding`, `LatentSeed` и `Recommendation`.

Состояние субъекта хранится независимо от рисунка:

[
m_t \longrightarrow o_t,
]

где реальное (m_t) неизвестно, а `StateObservation` — лишь измерительный proxy. Это важно, потому что сама статья прямо рассматривает эйдограмму как носитель текущего состояния и особенностей психической организации оператора. 

`OperatorMiner` уже ищет повторяющиеся структурные n-граммы и связывает их с переходами:

[
\Delta m=m_{\rm out}-m_{\rm in}.
]

Для каждого кандидата сохраняются:

[
support,
\quad
\overline{\Delta m},
\quad
stderr,
\quad
baseline,
\quad
evidence,
\quad
counterexamples.
]

То есть оператор нельзя превратить в «букву языка» только потому, что он несколько раз красиво совпал с желаемым результатом.

Первый recommender тоже уже реализован. Для текущего состояния (m) и целевого (G) он вычисляет направление:

[
d=
\frac{G-m}{|G-m|}
]

и оценивает оператор через его дополнительный переход относительно baseline:

[
score(w)
========

\left\langle
\overline{\Delta m}_w-
\overline{\Delta m}_0,
d
\right\rangle.
]

Он возвращает не готовую картинку, а **следующий структурный token и ссылку на реальный prototype stroke** из прошлой сессии.

Это сохраняет нужную петлю:

[
O\rightarrow E\rightarrow O',
]

вместо опасной подмены:

[
AI\rightarrow E\rightarrow O'.
]

Также предусмотрены режимы рекомендаций:

[
NONE,\quad PASSIVE,\quad ACTIVE.
]

`NONE` здесь методологически критичен: иначе рекомендательная система постепенно начнёт формировать собственный язык и мы не сможем отличить его от языка пользователя.

UTC подключён тонким adapter-слоем. Я намеренно не стал заново придумывать `AddressTuple`: `EidoOperator` или будущий `MacroName` связывается с существующим mem-object внешним `UTCBinding`.

Для латентных пространств уже есть интерфейсный слой:

[
\text{multimodal input}
\xrightarrow{\Phi}
z_{\rm MM}
\xrightarrow{A}
z_E.
]

Но никакая конкретная LLM в ядро пока не встроена, и её embedding не объявляется «самим смыслом». Это правильная граница для R1.

В `web/` находится минимальный смартфонный canvas. Он использует Pointer Events, пишет реальную временную траекторию пальца и экспортирует session JSON. Таким образом можно уже сейчас открыть интерфейс на смартфоне, нарисовать сессию и прогнать её через Python-ядро.

В архив также включены `ARCHITECTURE.md`, пример сессии и машинный пример анализа.

Я прогнал тестовый suite:

```text
test_mining_and_recommendation ... ok
test_one_stroke               ... ok
test_translation_scale_invariance ... ok

Ran 3 tests
OK
```

Особенно важен архитектурный gate для следующей версии. Сейчас shape signature — ещё не буква. Следующий уровень (\alpha) разрешается ввести только если будет установлено:

[
s_1\sim s_2
\Rightarrow
T_{s_1}\approx T_{s_2}
]

на переходах состояния. Аналогично `MacroName` возникает только после:

[
T_{w_1}\approx T_{w_2}\approx\ldots
]

на общей области начальных состояний и на отложенных сессиях.

То есть мы впервые получили технический путь:

[
\boxed{
\text{жест}
\rightarrow
\text{структурный паттерн}
\rightarrow
\text{оператор состояния}
\rightarrow
\text{класс эквивалентных операторов}
\rightarrow
\text{«Имя»}.
}
]

Следующая версия `R2` теперь должна заниматься не расширением UI, а самым содержательным вопросом: **автоматически выводить персональный алфавит и грамматику из operator-equivalence**, причём с train/holdout разделением, чтобы не принять случайную автокорреляцию за элемент языка. После этого уже имеет смысл присоединять мультимодальный latent bridge и смотреть, можно ли визуализировать области латентного пространства LLM через найденный человеком эйдографический язык.

Продолжил и реализовал `EidoLang-Mobile v0.1-R2`.

[Скачать EidoLang-Mobile v0.1-R2](sandbox:/mnt/data/EidoLang-Mobile_v0.1-R2.zip)

В этой версии я не расширял интерфейс и не добавлял новые эйдографические примитивы. `R2` решает ровно следующий обязательный вопрос: **можно ли из корпуса автоматически вывести персональный алфавит и грамматику, не назначая семантику форм вручную**. Это соответствует самой статье: там существенны история и порядок внесения элементов, а операции меняются в зависимости от среды и последовательности развёртки. 

Основная цепочка теперь такая:

[
RawStroke
\rightarrow
TechnicalToken
\rightarrow
\boxed{effect\ equivalence}
\rightarrow
AlphabetSymbol
\rightarrow
\boxed{context\ effect}
\rightarrow
GrammarRule.
]

Ключевое изменение — `AlphabetInducer`. Геометрическая похожесть сама по себе больше ничего не означает. Два разных жеста (a,b) могут стать одной буквой

[
a\sim_{\rm op}b
]

только если их объединение в один predictor **не ухудшает прогноз перехода состояния на holdout** относительно двух отдельных predictors.

То есть проверяется:

[
L_{\rm merge}^{holdout}
\leq
L_{\rm separate}^{holdout}.
]

После этого сам полученный symbol должен ещё побить глобальную модель:

[
L_{\rm symbol}^{holdout}
<
L_{\rm baseline}^{holdout}.
]

Таким образом, «буква» теперь определяется функционально, а не визуально.

Во время реализации обнаружился ещё более важный no-go. Если жест (A) всегда встречается вместе с (B), одинаковый session outcome вообще не позволяет утверждать

[
A\sim B.
]

Возможно, работает только (A), только (B), их сочетание или вообще третий фактор.

Поэтому я добавил **exclusive-support identifiability gate**. Merge разрешён только если и train, и holdout содержат обе области:

[
A\land\neg B,
]

[
B\land\neg A.
]

То есть должна существовать фактическая возможность проверить заменяемость. Это довольно существенное методологическое усиление по сравнению с R1.

Для грамматики реализован `EffectGrammarInducer`. После построения alphabet сессия превращается в чередующуюся последовательность:

[
A_{\alpha_1},
R_1,
A_{\alpha_2},
R_2,
A_{\alpha_3},\ldots
]

где (A_\alpha) — открытая системой буква, а (R_i) — структурное отношение между последовательными stroke.

Теперь паттерн

[
p=(x_1,x_2,\ldots,x_n)
]

считается настоящим грамматическим правилом только тогда, когда дополнительный контекст (x_1) повышает predictive power относительно собственного suffix:

[
s=(x_2,\ldots,x_n).
]

Gate:

[
\boxed{
L_{p}^{holdout}<L_s^{holdout}.
}
]

Поэтому правило вида

[
\alpha_7,R_3,\alpha_{12}
]

не означает просто «так часто рисуют». Оно означает:

> этот контекст содержит дополнительную информацию о переходе состояния, которой нет в его более коротком продолжении.

Это уже начало именно **операторной грамматики**.

Ещё одно существенное изменение: весь discovery pipeline теперь прогоняется против отрицательных контролей. Я детерминированно переставляю соответствие

[
Session\longleftrightarrow\Delta m,
]

сохраняя сами рисунки, распределение состояний, количество сессий и train/holdout, но уничтожая связь между структурой эйдограммы и state transition.

Gate консервативный:

[
\boxed{
Gain_{\rm real}>
\max_i Gain_{\rm permuted,i}.
}
]

То есть язык принимается не потому, что некоторый pattern имеет положительную статистику, а только если весь discovery превосходит структуры, которые тот же алгоритм способен «найти» после уничтожения реальной связи.

В synthetic positive-control результаты получились чистыми.

Для алфавита был корпус из 54 сессий: две совершенно разные формы имели один и тот же transition law, третья — противоположный.

Получено:

```text
Alphabet:
symbols                         2
observed holdout gain           21.5488
max(16 negative controls)        2.469244602549109
gate                            PASS
```

При этом система действительно объединила две геометрически разные формы в один symbol:

[
\boxed{
shape_A\neq shape_B,
\qquad
[shape_A]*{\rm op}=[shape_B]*{\rm op}.
}
]

То есть впервые технически реализован принцип:

[
\boxed{
\text{форма различна, операторный смысл одинаков}.
}
]

Для грамматики использован второй корпус из 60 сессий. Финальный stroke был одинаков, но первый элемент определял знак перехода состояния.

Результат:

```text
Grammar:
rules                           4
observed holdout gain           48.768
max(16 negative controls)        8.768933868674715
gate                            PASS
```

То есть система восстановила именно зависимость:

[
\text{context}
+
\text{same continuation}
\rightarrow
\text{different transition}.
]

В архиве теперь есть новые модули `alphabet.py`, `grammar.py`, `language.py`, `validation.py`, `discovery.py`, CLI-команда `discover`, `R2_METHODOLOGY.md`, `R2_CHECKPOINT.md`, воспроизводимый synthetic generator и полный measured report `EXAMPLE_R2_REPORT.json`.

Все тесты проходят:

```text
Ran 5 tests
OK
```

При этом я сознательно не называю найденную структуру причинным языком. Пока состояние измеряется только до и после полной сессии:

[
m_0
\xrightarrow{w_1w_2\ldots w_n}
m_n.
]

Следовательно, если (w_i) коррелирует с (\Delta m), мы ещё не знаем, вызвал ли изменение именно (w_i).

И это теперь задаёт практически вынужденный `R3`.

Нужен переход от

[
\boxed{\text{predictive language}}
]

к

[
\boxed{\text{causal operator language}}.
]

Для этого `R3` должен ввести промежуточные state probes внутри эйдограммы и, главное, **replay найденных операторов** с matched-control последовательностями. Тогда можно будет впервые проверить:

[
do(w)
\Rightarrow
\Delta m
]

и только после этого разрешить конструкцию

[
\boxed{
MacroName=[w]_{\rm causal-op}.
}
]

То есть мы уже дошли до довольно интересной точки: `R1` научился записывать язык, `R2` — обнаруживать его буквы и грамматические конструкции, а `R3` должен установить, являются ли они действительно программами изменения субъекта, а не только его следами.

Продолжил до `EidoLang-Mobile v0.1-R3`. Теперь система перешла от обнаружения предиктивного языка к первой причинной проверке операторов.

[Скачать EidoLang-Mobile v0.1-R3](sandbox:/mnt/data/EidoLang-Mobile_v0.1-R3.zip)

Отдельно: [R3 methodology](sandbox:/mnt/data/eidolang_mobile_v0_1_r3/R3_METHODOLOGY.md) · [R3 replay protocol](sandbox:/mnt/data/eidolang_mobile_v0_1_r3/R3_REPLAY_PROTOCOL.md) · [R3 checkpoint](sandbox:/mnt/data/eidolang_mobile_v0_1_r3/R3_CHECKPOINT.md)

Главное изменение:

[
R2:\qquad w\rightarrow\text{predicts }\Delta m
]

теперь недостаточно. `R3` проверяет:

[
\boxed{
do(\operatorname{replay}(w))
\rightarrow
\Delta m
}
]

относительно заранее построенного matched control.

Реализованы `ReplayTemplate`, `MatchedControl`, `ReplayTrial`, промежуточные `StateProbe`, balanced randomized assignment, `CausalAnalyzer` и `CausalOperatorCertificate`.

Matched control специально выбирается **без использования outcome/state-delta**. Он подбирается только по низкоуровневой моторной структуре: числу strokes, длине траектории, длительности, скорости, pressure при наличии, площади охвата и числу отношений. При этом control-session не должна содержать проверяемое грамматическое правило.

Получается схема:

[
\text{R2 rule}
\rightarrow
\begin{cases}
w_{\rm replay}\
w_{\rm matched}
\end{cases}
\xrightarrow{\rm randomized}
m_0\rightarrow m_1.
]

И оценивается:

[
ATE_y=
E[\Delta y\mid w_{\rm replay}]
------------------------------

E[\Delta y\mid w_{\rm matched}].
]

Важное решение: `R3` использует **точный randomization test**. Никакой статистический approximation автоматически не подставляется. Если число возможных allocations

[
{n\choose n_T}
]

превышает вычислительный лимит, результат возвращается как `UNRESOLVED`, а не как приблизительно значимый.

Это соответствует общей методологии проекта: вычислительная неудобность не превращается в доказательство.

Промежуточные probes тоже реализованы:

[
m_0
\to p_1
\to p_2
\to\ldots
\to m_f.
]

Но здесь обнаружился принципиальный нюанс: активный self-report сам является воздействием на состояние. Поэтому treatment/control обязаны иметь один `probe_schedule_id`. Иначе causal certificate автоматически невалиден. Probe trajectory используется для локализации момента изменения, но основной causal endpoint остаётся `pre → post`.

На synthetic positive control:

```text
16 trials
8 operator / 8 matched control

focus:
  operator Δ = +0.453
  control  Δ = +0.020
  ATE        = +0.433
  exact p    = 7.77000777000777e-05

noise:
  operator Δ = -0.453
  control  Δ = -0.020
  ATE        = -0.433
  exact p    = 7.77000777000777e-05

certificate: PASS
```

Промежуточный contrast для `focus`:

[
+0.141\rightarrow+0.307,
]

то есть код способен обнаруживать и динамику формирования эффекта.

Null-control с одинаковым эффектом treatment/control сертификат **не проходит**.

Есть ещё несколько отрицательных тестов: несовпадающие probe schedules делают дизайн невалидным; наличие non-randomized trial блокирует сертификат; превышение лимита exact enumeration возвращает `UNRESOLVED`, а не approximate p-value.

Всего сейчас:

```text
Ran 12 tests
OK
```

Но важнее всего новый no-go, появившийся уже из самой структуры R3.

Раньше мы предполагали:

[
T_{w_1}\simeq T_{w_2}
]

и хотели назвать такой класс `MacroName`.

После causalization это оказалось преждевременно.

Даже если установлено:

[
do(w_1)\rightarrow G
]

и

[
do(w_2)\rightarrow G,
]

отсюда **не следует**

[
w_1\equiv w_2.
]

Они могут отличаться:

[
\text{domain},
\quad
\text{trajectory},
\quad
\text{latency},
\quad
\text{side effects},
\quad
\text{mechanism}.
]

Поэтому сейчас имеется строгая лестница:

[
\boxed{
RawPattern
\rightarrow
PredictiveOperator
\rightarrow
CausalOperator
}
]

но ещё не:

[
CausalOperator
\rightarrow MacroName.
]

И это фактически вынуждает `R4`.

`R4` должен быть **Causal Substitution Calculus**: брать два уже независимо сертифицированных оператора (w_1,w_2), применять их рандомизированно к одному и тому же классу начальных состояний и проверять не отличие, а заранее определённую **эквивалентность**:

[
do(w_1)\simeq do(w_2).
]

Причём сравнивать придётся не только конечный эффект:

[
m_f,
]

но всю state trajectory:

[
m_0\to m_1\to\cdots\to m_f.
]

Только если различия укладываются в заранее фиксированную equivalence margin, станет допустимо факторизовать:

[
\boxed{
Name=[w]_{\rm causal}.
}
]

И вот тогда мы впервые действительно получим «буквы/Имена» не как интерпретированные символы, а как **классы различных физически исполняемых графических программ, реализующих один и тот же причинный оператор над состоянием субъекта**.

SHA-256 архива:

```text
be060112df688802196aab6d0e03fb04cac88915261c219bae9ac03841dcd91c
```

Следующая итерация теперь определена однозначно: `R4 — randomized causal substitution → causal MacroName`.

Продолжил до `EidoLang-Mobile v0.1-R4 — Causal Substitution Calculus`.

[Скачать EidoLang-Mobile v0.1-R4](sandbox:/mnt/data/EidoLang-Mobile_v0.1-R4.zip)

Также отдельно доступны [методология R4](sandbox:/mnt/data/eidolang_mobile_v0_1_r4/R4_METHODOLOGY.md), [checkpoint](sandbox:/mnt/data/eidolang_mobile_v0_1_r4/R4_CHECKPOINT.md) и [измеренный synthetic-report](sandbox:/mnt/data/eidolang_mobile_v0_1_r4/EXAMPLE_R4_REPORT.json).

Главный переход теперь такой:

[
R3:
\qquad
do(w_1)\to G,
\quad
do(w_2)\to G
]

ещё не означает

[
w_1\equiv w_2.
]

`R4` непосредственно проверяет:

[
\boxed{
do(w_1)\simeq_\varepsilon do(w_2)
}
]

при рандомизированной подстановке.

Критически важно: отсутствие статистически значимого различия не считается эквивалентностью. Для каждой измеряемой координаты заранее задаётся margin

[
\varepsilon_y>0,
]

и проверяются обе границы:

[
H^-_0:\delta\le-\varepsilon,
\qquad
H^+_0:\delta\ge+\varepsilon,
]

где

[
\delta=Effect(w_1)-Effect(w_2).
]

Эквивалентность принимается только после отклонения обеих гипотез.

В реализации используется exact randomization enumeration по реально допустимым назначениям treatment внутри заранее определённых страт. Если число allocations превышает лимит:

[
\boxed{\texttt{UNRESOLVED}}
]

вместо автоматической замены asymptotic approximation.

Есть важная оговорка, зафиксированная непосредственно в сертификате: точность такого boundary-test относится к sharp constant-additive differential-effect model внутри каждой страты. Поэтому `R4` не объявляет универсальную индивидуальную эквивалентность. Для уменьшения этого зазора equivalence должна отдельно пройти во всех предварительно заданных state-strata.

Добавлен строгий `DomainSpec`:

[
B\subset M.
]

Каждый pre-state substitution trial обязан удовлетворять

[
m_0\in B.
]

Поэтому результат имеет форму

[
\boxed{
w_1\simeq w_2\quad\text{на }B,
}
]

а не глобально.

Ещё сильнее изменена проверка trajectory. Одинаковый конечный эффект недостаточен:

[
m_f(w_1)\approx m_f(w_2)
]

не позволяет объединять операторы, если пути отличаются.

Проверяется каждый заранее зарегистрированный probe:

[
\Delta m_j(w_1)
\simeq_{\varepsilon^{traj}}
\Delta m_j(w_2).
]

То есть сертификат теперь относится к целой дискретизированной траектории:

[
m_0
\rightarrow m_1
\rightarrow\cdots
\rightarrow m_k
\rightarrow m_f.
]

Это всё ещё equivalence только на временном разрешении probes; происходящее между ними не идентифицировано.

Особенно важен новый structural no-go:

[
w_1\simeq_\varepsilon w_2,
\qquad
w_2\simeq_\varepsilon w_3
]

не влечёт

[
w_1\simeq_\varepsilon w_3.
]

Причина проста: приблизительная эквивалентность с ненулевым tolerance не транзитивна.

Поэтому я **запретил union-find/connected-components для построения `MacroName`**.

Теперь:

[
\boxed{
Name={w_1,\ldots,w_n}
}
]

может существовать только как **all-pairs causal substitution clique**:

[
\forall i<j:
\quad
Cert_{\rm subst}(w_i,w_j)=PASS.
]

При добавлении четвёртого оператора недостаточно проверить его с одним представителем Имени. Нужны:

[
w_4\simeq w_1,
\quad
w_4\simeq w_2,
\quad
w_4\simeq w_3.
]

Кроме того, все pair-certificates обязаны использовать одинаковые:

[
Domain,
\quad
ProbeSchedule,
\quad
Margins,
\quad
Policy,
\quad
ProtocolVersion.
]

Только тогда создаётся новый объект:

```text
CausalMacroName
```

со строгой семантикой:

```text
operational_substitution_at_protocol_resolution
```

То есть это уже действительно не название картинки.

Это класс разных физических графических программ, которые экспериментально взаимозаменяемы как причинные операторы над состоянием субъекта — в пределах измеренного домена и разрешения.

Добавлен также `assignment-integrity gate`. Самого поля

```text
randomized = true
```

недостаточно.

R4 заново вычисляет assignment из:

[
TrialIDs + Strata + Seed
]

и проверяет его против записанных arms. Любое изменение назначения делает эксперимент:

[
\boxed{\texttt{INVALID}}.
]

Это закрывает достаточно неприятную дыру R3.

Измеренный positive control:

```text
16 substitution trials
8 LEFT / 8 RIGHT

endpoint margin   = 0.08
trajectory margin = 0.08

focus endpoint difference = -0.012500
noise endpoint difference = +0.012500

focus phase 1 difference  = -0.010833
focus phase 2 difference  = +0.008750
noise phase 1 difference  = +0.010833
noise phase 2 difference  = -0.008750
```

Все шесть equivalence checks:

```text
PASS
```

Для каждого boundary-test:

```text
exact p_lower = 7.77000777000777e-05
exact p_upper = 7.77000777000777e-05
```

и после familywise Bonferroni gate весь certificate:

[
\boxed{PASS}.
]

Первый отрицательный контроль:

[
Effect(w_A)=0.45,
\qquad
Effect(w_B)=0.22
]

при margin (0.08):

```text
endpoint substitution: FAIL
```

Второй контроль значительно важнее. Финалы почти одинаковы:

[
0.45\quad\text{vs}\quad0.46,
]

но промежуточные trajectories намеренно различны.

Результат:

```text
endpoint equivalence: compatible
trajectory equivalence: FAIL
whole certificate: FAIL
```

То есть `R4` действительно не объединяет программы только потому, что они пришли примерно в одну конечную точку.

Проверен и трёхоператорный случай:

[
A\simeq B,
\quad
B\simeq C.
]

При отсутствии (A\simeq C) `MacroNameBuilder` отказывается создавать Name.

После добавления третьего независимого сертификата:

[
A\simeq C
]

строится:

```text
CausalMacroName
members = (op:A, op:B, op:C)
pair certificates = 3
```

Именно это я считаю главным содержательным результатом R4.

Полный suite:

```text
Ran 20 tests
OK
```

Включая все inherited R1–R3 tests.

SHA-256 архива:

```text
9296685c78d704947c697184fdb439e50adb3c6fdca11406ba81e050adda7673
```

Теперь лестница стала строгой:

[
\boxed{
RawGesture
\rightarrow
PredictivePattern
\rightarrow
CausalOperator
\rightarrow
SubstitutionCertificate
\rightarrow
CausalMacroName.
}
]

И одновременно проявилась следующая настоящая граница.

Даже доказав для одного человека:

[
[w_1]_{\rm causal}
==================

[w_2]_{\rm causal},
]

мы совершенно ничего ещё не доказали о другом человеке.

Причём требовать одинаковой физической эйдограммы у двух людей, вероятно, неправильно. Если статья права относительно индивидуальности развертки смысла, то может существовать:

[
w^{(A)}\neq w^{(B)},
]

но

[
Effect_A(w^{(A)})
\simeq
Effect_B(w^{(B)}).
]

Поэтому следующий необходимый этап теперь уже определяется не эвристически:

[
\boxed{
R5 =
\text{Cross-Observer Transport Calculus}.
}
]

Его задача — выяснить, существует ли инвариантное `Name`, которое переносится между субъектами **через разные индивидуальные эйдографические реализации**:

[
Name
\rightarrow
\begin{cases}
w^{(1)}\
w^{(2)}\
\vdots\
w^{(n)}
\end{cases}
]

с одним операторным эффектом.

Если такой transport существует, это будет первая техническая точка, где персональные эйдографические диалекты начинают факторизоваться в общий язык.

Да. После внимательного просмотра именно иллюстраций я бы изменил следующий шаг проекта. Переходить прямо к `R5 Cross-Observer Transport` рано: между индивидуальной геометрией рисунка и операторной эквивалентностью, похоже, действительно есть промежуточный слой — **топологическая организация эйдограммы**.

И это видно в картинках лучше, чем в тексте.

На стр. 4 один и тот же «красный треугольник» разворачивается в трёх очень разных эйдо-средах. Пиксельного или геометрического сходства между тремя нижними изображениями почти нет: в одном это несколько открытых линий с точкой, в другом — связная плотная фигура с острыми выступами, в третьем — система вложенных/перекрывающихся округлых областей. При этом сама статья именно эти три изображения рассматривает как различные развёртки одного предмета через разные среды. 

На стр. 6 ситуация ещё показательнее: одна исходная эйдо-среда — набор простых элементов — порождает две довольно разные композиции. Но в обеих сохраняется не столько конкретная форма, сколько сеть отношений: перекрытие, включение, пересечение границ, выделение центральных/периферийных областей. Автор прямо говорит, что форма возникает из взаимодействия смыслового переживания предмета со средой, а размещение каждого элемента согласуется с уже возникающим целым. 

А самая интересная иллюстрация — стр. 7 с «целостностью» и «дискретностью» трёх разных авторов. Статья специально подчёркивает, что один предмет получает индивидуальные развёртки у разных операторов.  Визуально пары действительно очень разные. Но у изображений «дискретности» заметен инвариант более высокого уровня: разрыв единой массы на несколько слабо связанных или вообще несвязанных элементов. У «целостности», напротив, гораздо чаще появляется одна доминирующая связная конструкция, центральное соединение или охватывающая композиция.

Это уже подозрительно похоже не на общий `shape`, а на общий **topological motif**.

Поэтому я предлагаю вставить перед `R5` новую стадию:

[
\boxed{\textbf{R4.5 — Topological Eidogram Quotient}}
]

с вопросом:

[
\text{существует ли фактор }
\Gamma
\longrightarrow
\mathcal T(\Gamma)
]

такой, что очень разные индивидуальные изображения одного операторного класса становятся близкими после перехода к топологической структуре?

И здесь обычный CNN embedding я бы пока поставил на второе место. Сначала можно построить гораздо более интерпретируемую конструкцию.

Первый уровень — считать эйдограмму планарным комплексом. Не «картинкой», а:

[
K=(V,E,F),
]

где (V) — существенные узлы, (E) — линии/границы/связи, (F) — образованные области.

Тогда появляются базовые инварианты:

[
\beta_0=\text{число связных компонент},
]

[
\beta_1=\text{число независимых циклов/дыр}.
]

Например, самый грубый кандидат для пары

[
\text{целостность}\leftrightarrow\text{дискретность}
]

уже можно искать через изменение

[
\beta_0.
]

Но я почти уверен, что одних Betti numbers будет недостаточно. Две совершенно разные структуры могут иметь

[
(\beta_0,\beta_1)=(1,1).
]

Поэтому нужен более богатый объект.

Я бы использовал одновременно четыре топологических представления.

Первое — **persistent homology**. Строим filtration изображения, например по расстоянию от нанесённых линий/областей:

[
K_{\epsilon_1}
\subseteq
K_{\epsilon_2}
\subseteq
\cdots.
]

И получаем persistence diagrams:

[
D_0(\Gamma),D_1(\Gamma).
]

Они различают:

* устойчивые компоненты;
* временные/мелкие фрагменты;
* устойчивые отверстия;
* слияния элементов.

Это уже гораздо устойчивее к небольшим изменениям размера, положения и моторного исполнения.

Второе — **дерево вложенности областей**.

В эйдограммах статьи очень много отношений вида:

[
A\subset B,
]

[
A\cap B\neq\varnothing,
]

[
A\text{ охватывает }B.
]

Обычная persistent homology это кодирует лишь частично. Поэтому нужен отдельный `containment/overlap graph`:

[
G_C=(F,R_C),
]

где ребро сообщает:

* вложение;
* пересечение;
* касание;
* разделённость.

Например, круглые эйдограммы на стр. 4–5 визуально сильно меняются, но сохраняют мотив **внешняя область → внутренняя область → дополнительная локальная область/перекрытие**. Это уже может быть гораздо ближе к инварианту, чем форма конкретных окружностей.

Третье — **относительная топология относительно поля эйдограммы**.

Это особенно важно и напрямую следует из статьи: композиция определяется не только отношениями элементов друг с другом, но и относительно границ поля. 

Поэтому прямоугольник экрана смартфона нельзя считать нейтральным canvas.

Пусть

[
F=\text{поле эйдограммы},
\qquad
\partial F=\text{его граница}.
]

Тогда нас интересуют не только

[
H_k(K),
]

но и относительные группы

[
\boxed{
H_k(K,\partial F).
}
]

Они смогут отличить, например:

* объект, свободно лежащий внутри поля;
* структуру, «упирающуюся» в границу;
* линию, соединяющую противоположные стороны;
* область, отделяющую одну часть поля от другой.

А на картинках статьи отношение к рамке действительно выглядит систематически значимым.

Четвёртое — и, возможно, самое важное — **топология процесса рисования**, а не конечного рисунка.

Мы уже правильно сделали в `R1`, что сохранили:

[
\Gamma=(e_1,\ldots,e_n).
]

Теперь это даёт преимущество, которого сама PDF-иллюстрация уже не содержит.

Определим:

[
K_t=Render(e_1,\ldots,e_t).
]

Получается динамическая последовательность:

[
K_1\rightarrow K_2\rightarrow\cdots\rightarrow K_n.
]

И можно отслеживать события:

[
\text{birth(component)},
]

[
\text{merge},
]

[
\text{split},
]

[
\text{birth(loop)},
]

[
\text{closure},
]

[
\text{enclosure},
]

[
\text{boundary-contact}.
]

То есть эйдограмма превращается в **топологическую программу**.

Это очень хорошо совпадает с самой статьёй, потому что там прямо сказано, что существенны история и способ помещения смысла в контекст, а последовательность размещения фигур отображает операции над смыслами. 

Для удаления элементов или стирания обычной persistence уже недостаточно. Здесь естественна **zigzag persistence**:

[
K_1
\leftrightarrow
K_2
\leftrightarrow
\cdots
\leftrightarrow
K_n,
]

поскольку структура может не только нарастать, но и упрощаться/расщепляться. И статья сама описывает процессуальный уровень именно как рост, усложнение, последующее расщепление и выделение согласованно изменяющихся сценариев. 

Это почти прямое указание на динамическую топологию.

Есть ещё один очень интересный объект — `Reeb graph` или близкий к нему `Mapper`.

Можно выбрать скалярную функцию на изображении:

[
f(x)=
\text{drawing time},
]

или

[
f(x)=\text{distance from centre},
]

или даже

[
f(x)=\text{local intensity}.
]

Тогда Reeb graph сохраняет то, как связные компоненты level sets рождаются, соединяются и разделяются. Для эйдограмм типа «эйдо-улитки» это может оказаться существенно информативнее обычной геометрии.

На стр. 11 это особенно заметно: 16 состояний «улитки» имеют разные конкретные элементы, но глазами довольно легко увидеть повторяющиеся операции:

[
\text{concentration}
\rightarrow
\text{extension}
\rightarrow
\text{intersection}
\rightarrow
\text{enclosure}
\rightarrow
\text{fragmentation}
\rightarrow
\text{reconnection}.
]

При этом сама статья интерпретирует «эйдо-улитку» именно как сценарий постепенного усложнения и перехода от исходного материала к возникающему смысловому содержанию. 

То есть я бы искал общий мотив **не в отдельных кадрах улитки, а в последовательности топологических событий между кадрами**.

Это приводит к довольно сильной новой гипотезе:

[
\boxed{
\text{эйдографический инвариант может быть не формой,
а топологической программой преобразований формы}.
}
]

Тогда два человека могут рисовать совершенно разные изображения:

[
\Gamma_A\neq\Gamma_B,
]

но иметь одну sequence signature:

[
\mathcal T(\Gamma_A)
\simeq
\mathcal T(\Gamma_B).
]

А если при этом:

[
Effect_A(\Gamma_A)
\simeq
Effect_B(\Gamma_B),
]

получаем гораздо более серьёзного кандидата на общий элемент языка:

[
\boxed{
Name
====

[\mathcal T(\Gamma)]_{\rm causal}.
}
]

Это уже заметно лучше прежнего `R5`, где мы собирались искать cross-observer correspondence почти непосредственно между индивидуальными операторами.

Я бы теперь построил иерархию так:

[
\Gamma
\rightarrow
\underbrace{Geometry(\Gamma)}*{\text{индивидуально}}
\rightarrow
\underbrace{Topology(\Gamma)}*{\text{кандидат на инвариант}}
\rightarrow
\underbrace{CausalEffect(\Gamma)}_{\text{оператор}}
]

и затем проверял:

[
\boxed{
Topology(\Gamma_1)\approx Topology(\Gamma_2)
\quad\Longleftrightarrow?\quad
Effect(\Gamma_1)\approx Effect(\Gamma_2).
}
]

Причём направление `⇒` нельзя предполагать — его надо измерять.

Есть и хороший отрицательный контроль: генерировать surrogate-эйдограммы, сохраняющие количество элементов, площадь, длину линий, цветовой состав и даже ((\beta_0,\beta_1)), но разрушающие более высокий nesting/process structure. Если causal effect переживает только поверхностную геометрию, TDA нам ничего не даёт. Если же predictive/causal signal концентрируется на более высоком топологическом quotient, это будет уже очень интересный результат.

Поэтому я бы теперь **не продолжал R5 в прежнем виде**. Следующей реализуемой стадией должен стать:

[
\boxed{\textbf{R4.5 — Topological Motif Discovery}}
]

с тремя уровнями сигнатуры:

[
T_0=
(\beta_0,\beta_1,\chi),
]

[
T_1=
(\text{persistence diagrams},
\text{containment graph},
\text{relative-boundary topology}),
]

[
T_2=
\text{temporal/zigzag topology of }\Gamma.
]

А затем строгий gate:

[
\boxed{
\text{вводить TopologicalSymbol только если }
T
\text{ улучшает holdout prediction causal class}
}
]

относительно нашей нынешней геометрической сигнатуры.

Это полностью сохраняет прежний принцип проекта: мы не объявляем топологию «языком» потому, что она красиво выглядит. Она становится новым уровнем представления только если измеренный контрпример показывает, что геометрический R4-quotient недостаточен, а топологический quotient сохраняет операторный инвариант.

И по иллюстрациям статьи я бы сказал, что это сейчас наиболее правдоподобный кандидат на такой следующий уровень.

Продолжил. Получилась `EidoLang-Mobile v0.1-R4.5 — Topological Motif Discovery`.

[Скачать полный архив R4.5](sandbox:/mnt/data/EidoLang-Mobile_v0.1-R4.5.zip)

Главный результат оказался содержательным: ваша оговорка, что общие мотивы на иллюстрациях есть, но выделить их напрямую трудно, подтверждается уже простейшим измерением. В статье действительно показаны «целостность» и «дискретность» у трёх разных авторов, причём изображения одного понятия геометрически очень различаются.  Одновременно автор подчёркивает, что важны история и способ помещения смысла в контекст, а не только финальная форма. 

Я поэтому вставил перед прежним `R5` отдельный обязательный слой:

[
\boxed{
R4.5:\quad
Geometry
\rightarrow
Topology
\rightarrow
CausalClass
}
]

и сделал три последовательных уровня.

[
T_0=(\beta_0,\beta_1,\chi)
]

— только компоненты связности, циклы и характеристика Эйлера.

Затем

[
T_1=T_0+
\text{boundary/containment/intersection structure},
]

то есть дополнительно учитываются:

* касание границ поля;
* соединение противоположных границ;
* число замкнутых strokes;
* граф вложенности;
* глубина вложенности;
* пересечения;
* раздельные замкнутые компоненты.

И наконец

[
T_2=T_1+\text{temporal topological program}.
]

Для каждого префикса рисования:

[
K_1\to K_2\to\ldots\to K_n
]

фиксируются рождения/слияния компонент, циклы, возникновение вложения, пересечений и отношения к границе.

Особенно важно, что `T2` пришлось усилить уже по найденному контрпримеру. Просто последовательности Betti numbers оказалось недостаточно: если сначала нарисовать внешний контур, а потом внутренний, и наоборот, изменение

[
(\beta_0,\beta_1)
]

одинаково.

Поэтому `T2` теперь различает:

[
\boxed{\text{new stroke contains existing}}
]

и

[
\boxed{\text{new stroke is contained by existing}}.
]

Это уже настоящий топологический оператор процесса, а не геометрическая характеристика.

### Первый строгий no-go

Я построил две синтетические эйдограммы:

* два вложенных замкнутых элемента;
* два разнесённых замкнутых элемента.

Для обеих:

```text
beta0 = 2
beta1 = 2
chi   = 0
```

то есть

[
T_0(A)=T_0(B).
]

Но у первой есть containment edge, у второй нет:

[
T_1(A)\ne T_1(B).
]

Следовательно:

[
\boxed{T_0\text{ недостаточен}.}
]

Далее построены две эйдограммы с абсолютно одинаковой финальной топологией:

[
T_1(A)=T_1(B),
]

но с противоположным порядком построения — `outer→inner` и `inner→outer`.

Получено:

[
\boxed{T_2(A)\ne T_2(B).}
]

То есть процессуальность статьи действительно требует отдельного топологического временного уровня. Это согласуется и с описанием «эйдо-улитки» как сценария роста, усложнения, расщепления и взаимодействия возникающих структур. 

### Я проверил это и непосредственно на иллюстрациях статьи

Для шести опубликованных изображений на стр. 7 — три «целостности» и три «дискретности» — я сделал отдельный raster-probe.

Проверены grayscale thresholds:

```text
64
96
128
160
192
224
245
```

На каждом считались:

[
(\beta_0,\beta_1).
]

Результат оказался отрицательным во всех семи случаях:

```text
ни один threshold не даёт
единую T0-сигнатуру для трёх «целостностей»
и одновременно другую единую сигнатуру
для трёх «дискретностей»
```

Это хороший результат именно потому, что он не позволяет нам обмануть себя красивой гипотезой:

[
\boxed{
\text{простые Betti numbers не извлекают очевидный глазу общий мотив.}
}
]

[Посмотреть полный измеренный raster-probe](sandbox:/mnt/data/eidolang_mobile_v0_1_r45/EXAMPLE_ARTICLE_RASTER_PROBE.json)

Причём это ещё более слабые данные, чем будут у приложения: PDF потерял исходные цвета, слои и, главное,

[
\Gamma=(e_1,\ldots,e_n).
]

Поэтому к статье нельзя применить полноценные `T1/T2`.

### Cross-observer positive control

Я также реализовал synthetic corpus:

* 6 разных «наблюдателей»;
* 12 эйдограмм;
* очень разные геометрические реализации;
* два заранее заданных causal classes;
* leave-one-observer-out.

Получилось:

```text
Geometry       10 / 12 = 0.833333
T0              6 / 12 = 0.500000
T1             12 / 12 = 1.000000
T2             12 / 12 = 1.000000

T2 - Geometry  = +0.166667
```

То есть именно минимальный (T_0) оказался бесполезен, а необходимое дополнительное отношение `containment` восстановило общий класс.

Я добавил и label-destruction controls. Причём пришлось исправить ещё одну методологическую ошибку: глобальная перестановка названий классов не является отрицательным контролем — она сохраняет классификационную структуру. Теперь метки меняются **несогласованно между наблюдателями**.

Результат:

```text
real T2 accuracy             = 1.000
max negative-control T2      = 0.750
gate                         = PASS
```

Это, конечно, только positive control алгоритма, а не данные о реальных людях.

### Что теперь находится в коде

Добавлены:

```text
topology.py
topological_discovery.py
r45_synthetic.py
tests/test_r45.py
tools/article_raster_probe.py
```

Команда:

```bash
eidolang topology session.json --level T2
```

строит топологическое представление реальной smartphone-session.

А `CrossObserverTopologyEvaluator` уже реализует правильный будущий тест:

[
\text{train on observers }O_1,\ldots,O_{n-1}
]

и

[
\text{test on unseen }O_n.
]

Причём causal label не придумывает TDA. Он должен прийти из уже сертифицированного `R4 CausalMacroName`. Это принципиально:

[
\boxed{
TDA\text{ ищет representation invariant, а не назначает смысл.}
}
]

Все предыдущие проверки сохранены:

```text
Ran 25 tests
OK
```

[Методология R4.5](sandbox:/mnt/data/eidolang_mobile_v0_1_r45/R4_5_METHODOLOGY.md)
[Измеренный checkpoint](sandbox:/mnt/data/eidolang_mobile_v0_1_r45/R4_5_CHECKPOINT.md)
[Synthetic report](sandbox:/mnt/data/eidolang_mobile_v0_1_r45/EXAMPLE_R45_REPORT.json)
[Audit иллюстраций статьи](sandbox:/mnt/data/eidolang_mobile_v0_1_r45/ARTICLE_ILLUSTRATION_AUDIT.md)

SHA-256 архива:

```text
63b6de5896da9f69874b69143299f24da1d1022a0ddb2a554a9d5478b1465e8c
```

Теперь следующий шаг изменился довольно существенно. Я бы пока не переходил к обычному `R5`.

Нам нужен:

[
\boxed{
R4.6 =
\text{Multilayer Topology}
}
]

потому что статья рассматривает цвет, форму и композицию как разные эйдо-среды, а PDF-проба уже показала проблему: бинаризация разрушает структуру серого/чёрного/белого слоя. 

На смартфоне этой проблемы не будет: мы точно знаем, **каким цветом, слоем, мультимедийным каналом и в какой момент создан каждый элемент**.

Поэтому следующий естественный объект уже не простой комплекс (K), а:

[
\boxed{
K=
K_{\rm form}
\oplus
K_{\rm color}
\oplus
K_{\rm audio}
\oplus
K_{\rm haptic},
}
]

с отношениями между слоями.

И вот здесь может впервые проявиться тот общий мотив, который глазами заметен в статье, но не извлекается из одной бинарной геометрии.

Продолжил именно в этом направлении и закрыл `EidoLang-Mobile v0.1-R4.6 — Multilayer Topological Motif Discovery`.

[Скачать полный архив R4.6](sandbox:/mnt/data/EidoLang-Mobile_v0.1-R4.6.zip)

Основная идея теперь реализована буквально: эйдограмма рассматривается не как одна плоская картинка, а как мультислойный объект

[
\boxed{
\mathcal M(\Gamma)=
(K_{\rm form},
{K_{\rm color}^{c}},
K_{\rm audio},
K_{\rm haptic},
I)
}
]

где (I) — отношения между слоями. Это непосредственно следует из структуры статьи: цвет, форма и композиция там рассматриваются как различные эйдо-среды, сочетание которых образует целостный смысло-образ.  При этом статья отдельно подчёркивает, что важны история и способ помещения смысла в контекст. 

Введён новый уровень:

[
T_3 =
T_2(\text{form})
+
\text{multilayer topology}.
]

Цвет при этом не получает придуманной нами семантики. Система хранит реальные цвета, но cross-observer representation по умолчанию факторизуется относительно произвольного переименования палитры:

[
red\leftrightarrow blue
]

не должно менять топологический класс само по себе.

Зато сохраняется структурная информация:

[
\text{одинаковый слой}
\quad/\quad
\text{другой слой}.
]

И здесь обнаружился новый no-go, которого в предыдущей версии ещё не было.

Можно построить две эйдограммы, у которых одновременно совпадают:

[
T_2,
]

число цветовых слоёв,

[
|\mathcal C|,
]

мультимножество топологий отдельных слоёв,

число переключений цвета,

и даже общее число межслойных containment relations.

Но их слои по-разному располагаются внутри **иерархии вложенности**.

Поэтому агрегаты вида

[
#layers,\quad #containment,\quad
\sum\beta_i
]

оказались недостаточны.

Теперь система строит Hasse-структуру containment и сохраняет pattern равенства слоёв вдоль её цепей:

[
AAB,\qquad ABA,\qquad ABC,\ldots
]

Например в synthetic counterexample получено:

```text
session A: [AAB, ABC]
session B: [ABA, ABC]
```

хотя все более грубые показатели совпадают.

Это очень близко к тому, что было видно глазами на иллюстрациях статьи: общий мотив может находиться не в конкретной фигуре и даже не просто в числе компонент, а в **способе организации различий внутри целого**.

Второе существенное расширение — мультимедиа теперь встроено не как украшение.

Для `AUDIO` и `HAPTIC` строится временная топология интервалов, а затем вычисляется их incidence с топологическими событиями рисования.

Получился строгий пример:

[
T_2(A)=T_2(B),
]

одинаковая форма, один и тот же haptic pulse, но:

```text
A: HAPTIC | dB0+
B: HAPTIC | dB1+
```

То есть в первом случае вибрация приходится на появление новой связной компоненты, а во втором — на замыкание цикла.

Поэтому:

[
\boxed{
\text{media token сам по себе недостаточен;
важно его место в топологической программе}.
}
]

Никакого произвольного temporal window я не вводил. Связь существует только при фактическом пересечении временных интервалов media-event и physical stroke.

Смартфонный capture также обновлён. Теперь web-клиент реально пишет:

[
COLOR,\quad AUDIO,\quad HAPTIC
]

в общий event stream. Если цвет меняется прямо внутри одного `START…END` stroke, ядро не делает скрытого предположения «один stroke = один цвет», а автоматически разбивает его на `ColorRun`.

Cross-observer synthetic test теперь специально устроен так, что геометрия и формальная (T_2)-топология вообще не несут информации о классе:

```text
Geometry   6 / 12 = 0.500
T2         6 / 12 = 0.500
T3        12 / 12 = 1.000
```

То есть:

[
\boxed{
Accuracy(T_3)-Accuracy(T_2)=0.5.
}
]

Для разрушенных observer-wise causal labels:

```text
max negative-control T3 = 0.833333
```

поэтому текущий synthetic gate:

[
\boxed{PASS}.
]

Это, разумеется, positive control алгоритма, а не свидетельство существования такого инварианта у реальных людей.

Все inherited проверки R1–R4.5 сохранены:

```text
Ran 31 tests
OK
```

Отдельно доступны [методология R4.6](sandbox:/mnt/data/eidolang_mobile_v0_1_r46/R4_6_METHODOLOGY.md), [checkpoint R4.6](sandbox:/mnt/data/eidolang_mobile_v0_1_r46/R4_6_CHECKPOINT.md) и [полный измеренный report](sandbox:/mnt/data/eidolang_mobile_v0_1_r46/EXAMPLE_R46_REPORT.json).

SHA-256 архива:

```text
b694600169c9fb6628d86fcd4ae57815676adb25be811c1263b7383856f41a39
```

Содержательно теперь получается более интересная иерархия:

[
\boxed{
\text{geometry}
\rightarrow
\text{form topology}
\rightarrow
\text{layer topology}
\rightarrow
\text{topology of relations between layers}
\rightarrow
\text{causal operator}.
}
]

И это хорошо согласуется с тем, как сама статья описывает процессуальные эйдограммы: важен не только набор элементов, а сценарий их развития, взаимодействия, усложнения и расщепления. 

При этом я сознательно **не ввёл следующий уровень автоматически** — например, конкретные RGB-значения, частоты звука, embedding LLM или готовую multiparameter persistence. После R4.6 это снова должно решаться через necessity: сначала проверяем (T_3) на реальном смартфонном корпусе; только конкретный оставшийся counterexample имеет право потребовать следующую структуру.

Иначе мы снова начнём проектировать язык вместо того, чтобы его обнаруживать.

Продолжил, но не стал добавлять ещё один топологический или латентный слой. После `R4.6` обнаружилась более фундаментальная проблема: прежде чем искать общий мотив у разных людей, нужно доказать, что **измеренные эффекты этих людей вообще сопоставимы**. В самой статье прямо отмечается индивидуальность эйдограмм одного предмета у разных операторов. 

Получилась стадия:

[
\boxed{\textbf{EidoLang-Mobile v0.1-R4.7 — Cross-Observer Effect Alignment}}
]

[Скачать полный архив R4.7](sandbox:/mnt/data/EidoLang-Mobile_v0.1-R4.7.zip)

Отдельно: [методология R4.7](sandbox:/mnt/data/eidolang_mobile_v0_1_r47/R4_7_METHODOLOGY.md) · [checkpoint](sandbox:/mnt/data/eidolang_mobile_v0_1_r47/R4_7_CHECKPOINT.md) · [измеренный report](sandbox:/mnt/data/eidolang_mobile_v0_1_r47/EXAMPLE_R47_REPORT.json) · [шаблон anchor-протокола](sandbox:/mnt/data/eidolang_mobile_v0_1_r47/examples/anchor_protocol.template.json)

Главный новый no-go:

[
\boxed{
\Delta o_A=\Delta o_B
\not\Rightarrow
\Delta m_A=\Delta m_B.
}
]

Даже если два человека используют одинаковые шкалы `focus`, `noise` и получают одинаковые числа, это ещё не означает, что их координаты состояния соответствуют друг другу.

Более формально, произвольная репараметризация

[
h:O_B\rightarrow O'_B
]

может сохранить все внутрииндивидуальные результаты `R1–R4`, но полностью изменить cross-observer расстояния. Поэтому прежняя конструкция

[
Effect_A(w_A)\simeq Effect_B(w_B)
]

до этой стадии была математически недоопределена.

### Что сделал R4.7

Вводится эмпирический transport:

[
\Phi_i:O_i\rightarrow O_{\rm ref}.
]

Но я намеренно не разрешил сразу произвольный affine map или neural network.

Первый допустимый класс:

[
\boxed{
\Phi_i(\Delta o)
================

s_i\Delta o R_i,
}
]

где

[
s_i>0,
\qquad
R_i^\top R_i=I.
]

То есть только:

* общий масштаб;
* вращение;
* отражение.

И обязательно:

[
\Phi_i(0)=0.
]

Последнее существенно: мы переносим **изменения состояния**, поэтому отсутствие изменения не имеет права превратиться в ненулевой эффект.

---

### Общие anchors не получают семантики

Это тоже принципиально.

Anchor `a_j` — не:

> «спокойствие»,

> «фокус»,

> «транс».

Это только один и тот же заранее определённый физический протокол, предъявленный каждому наблюдателю:

[
a_j.
]

Для человека (i) измеряется:

[
v_i(a_j)
========

o_i^{after}-o_i^{before}.
]

Получается матрица ответов:

[
V_i=
\begin{pmatrix}
v_i(a_1)\
\vdots\
v_i(a_n)
\end{pmatrix}.
]

И проверяется, существует ли простой transport:

[
V_iR_is_i\approx V_{\rm ref}.
]

Таким образом, мы вообще не предполагаем, что конкретный anchor субъективно означает для двух людей одно и то же.

---

### Fit и holdout anchors разделены

Самая важная часть:

[
A=A_{\rm fit}\sqcup A_{\rm holdout}.
]

(\Phi_i) строится только по:

[
A_{\rm fit}.
]

После чего проверяется на совершенно невиденных:

[
A_{\rm holdout}.
]

То есть хороший fit больше не является основанием для cross-observer transport.

---

### Добавлен rank gate

Если наблюдаемое состояние имеет размерность (d), anchors должны реально возбуждать достаточное число независимых направлений:

[
\operatorname{rank}V_i\ge d.
]

Если нет:

```text
INVALID
```

а не попытка подобрать красивое преобразование.

Это важный результат для будущего реального смартфонного протокола: anchor battery должна не просто вызывать «что-нибудь», а давать достаточную геометрию ответов.

---

### Exact destructive control

Для проверки того, что найденное соответствие anchors не случайно, я не использую asymptotic approximation.

На fit-наборе полностью разрушается соответствие:

[
a_j^{(i)}
\leftrightarrow
a_{\pi(j)}^{(ref)}.
]

При шести fit anchors проверяются все:

[
6!=720
]

перестановок.

Для каждой снова строится transport и измеряется ошибка на **непереставленных holdout anchors**.

Exact p-value:

[
p=
\frac{
#{\pi:
RMSE_{\rm holdout}^{(\pi)}
\le
RMSE_{\rm observed}}
}{
6!
}.
]

Если число перестановок превышает вычислительный cap:

[
\boxed{\texttt{UNRESOLVED}}
]

без автоматического перехода к Monte-Carlo.

---

### Familywise gate

Если к reference observer выравниваются (N-1) других людей:

[
\alpha_i=
\frac{\alpha}{N-1}.
]

Каждый transport обязан одновременно пройти:

[
RMSE_{holdout}\le\varepsilon
]

и

[
p_{\rm exact}\le\alpha_i.
]

Если один observer не проходит:

[
\boxed{
\text{cross-observer causal comparison BLOCKED}.
}
]

Нельзя просто выкинуть неудобного человека и объявить общий язык.

---

## Измеренный positive control

Синтетически сгенерированы 6 observers.

У каждого действительно одна и та же двухмерная структура эффектов, но координаты преобразованы различными:

* масштабами;
* вращениями;
* отражениями;
* небольшим noise.

Использовано:

[
6;fit\ anchors
+
3;holdout\ anchors.
]

Результат:

```text
O1: holdout_rmse = 0.002046
    exact p      = 0.001388889
    PASS

O2: holdout_rmse = 0.003215
    exact p      = 0.001388889
    PASS

O3: holdout_rmse = 0.001606
    exact p      = 0.001388889
    PASS

O4: holdout_rmse = 0.003168
    exact p      = 0.001388889
    PASS

O5: holdout_rmse = 0.003719
    exact p      = 0.001388889
    PASS
```

При margin

[
\varepsilon=0.02.
]

Полный gate:

[
\boxed{PASS}.
]

---

## Более важный negative control

Я сделал `O5` специально неприятным.

На fit anchors он выглядит идеально совместимым с обычным similarity transport.

Но только на unseen anchors вводится нелинейное искажение.

Получено:

[
RMSE_{holdout}
==============

0.077646
]

при

[
\varepsilon=0.02.
]

Результат:

```text
O5: FAIL
whole alignment: BLOCKED
```

То есть:

[
\boxed{
\text{хорошо объяснить известные anchors недостаточно}.
}
]

Это именно тот тип curve-fitting, которого мы хотели избежать.

---

## Ещё один строгий результат

Если anchors лежат, например, фактически на одной прямой:

[
v_j=(x_j,2x_j),
]

то в двумерном пространстве:

[
rank(V)=1<2.
]

R4.7 возвращает:

```text
INVALID
```

Transport не идентифицируем.

А если exact permutation space превышает установленный лимит:

```text
UNRESOLVED
```

Все эти случаи покрыты тестами.

Полный suite теперь:

```text
Ran 36 tests
OK
```

---

## Что изменилось в общей теории языка

Теперь иерархия стала:

[
\boxed{
\begin{aligned}
&\text{Gesture}\
&\downarrow\
&\text{PredictiveOperator}\
&\downarrow\
&\text{CausalOperator}\
&\downarrow\
&\text{within-observer CausalMacroName}\
&\downarrow\
&\text{T2/T3 Topological Motif}\
&\downarrow\
&\textbf{Effect-coordinate transport } \Phi_i\
&\downarrow\
&\text{cross-observer comparison}.
\end{aligned}}
]

Это существенно меняет наш будущий `R5`.

Раньше мы собирались искать:

[
\Gamma_A\sim\Gamma_B
]

и смотреть, одинаков ли эффект.

Теперь правильная проверка:

[
\boxed{
\mathcal T_3(\Gamma_A)
\simeq
\mathcal T_3(\Gamma_B)
}
]

против

[
\boxed{
\Phi_A(\Delta o_A)
\simeq
\Phi_B(\Delta o_B).
}
]

То есть обе стороны сравнения теперь имеют собственный доказанный quotient:

* слева — топологический;
* справа — observer-coordinate transport.

---

## И только теперь появляется правильный кандидат на общий «смысл»

Если для разных людей:

[
\Gamma_A\neq\Gamma_B,
]

но

[
T_3(\Gamma_A)\simeq T_3(\Gamma_B),
]

и одновременно после независимой calibration:

[
\Phi_A(Effect_A(\Gamma_A))
\simeq
\Phi_B(Effect_B(\Gamma_B)),
]

то впервые появляется эмпирически содержательный кандидат:

[
\boxed{
Name_{\rm common}
=================

[
T_3(\Gamma)
]_{\rm aligned\ causal}.
}
]

Это уже не общий рисунок.

И не одинаковое слово в self-report.

Это **общая топологическая программа, реализующая один операторный эффект после независимого выравнивания пространств состояния**.

Именно такой объект, на мой взгляд, теперь имеет смысл искать в тех общих мотивах иллюстраций, на которые вы обратили внимание.

SHA-256 архива:

```text
09c3471d4a48d650d227730ea57225c76d36672f844e40610df648129fc49664
```

Следующий шаг теперь тоже вынужденный: не расширять TDA, а сделать `R4.8 — Real Corpus Acquisition Gate` — смартфонный протокол сбора anchor-сессий и эйдограмм так, чтобы **ни anchors, ни holdout, ни topology labels нельзя было выбирать постфактум**. После него уже можно будет впервые запустить весь тракт `R1→R4.7` не на synthetic positive controls, а на данных нескольких реальных людей.

Продолжил до `EidoLang-Mobile v0.1-R4.8 — Real Corpus Acquisition Gate`.

[Скачать полный архив R4.8](sandbox:/mnt/data/EidoLang-Mobile_v0.1-R4.8.zip)

Отдельно: [методология R4.8](sandbox:/mnt/data/eidolang_mobile_v0_1_r48/R4_8_METHODOLOGY.md) · [checkpoint](sandbox:/mnt/data/eidolang_mobile_v0_1_r48/R4_8_CHECKPOINT.md) · [измеренный integrity-report](sandbox:/mnt/data/eidolang_mobile_v0_1_r48/EXAMPLE_R48_INTEGRITY_REPORT.json) · [шаблон preregistration manifest](sandbox:/mnt/data/eidolang_mobile_v0_1_r48/examples/r48_acquisition_manifest.template.json).

Главная проблема, которую закрывает эта стадия: после появления достаточно богатого (T_3) мы уже можем очень легко «найти язык» постфактум — выбрать удобные anchors, удачный holdout, нужную версию TDA и только те causal classes, которые красиво совпали. `R4.8` делает такой путь машинно недопустимым.

Теперь исследование имеет двухступенчатую фиксацию.

До первой реальной сессии создаётся

[
P=\texttt{AcquisitionManifest}
]

и вычисляется

[
\boxed{
H_P=SHA256(\operatorname{CanonicalJSON}(P)).
}
]

В него входят:

* точный набор anchor-протоколов;
* число повторов;
* координаты `StateObservation`;
* число fit anchors;
* seed разделения;
* правила исключения;
* `T3` как замороженный representation level;
* версия topology extractor;
* разрешённое семейство effect-transport;
* политика causal labels;
* cross-observer evaluation policy.

Изменение любого из этих решений меняет `protocol_hash`.

### Fit/holdout теперь нельзя выбрать после данных

Принадлежность anchor определяется только:

[
r(a)=SHA256(seed\Vert a).
]

После сортировки:

[
A=A_{\rm fit}\sqcup A_{\rm holdout}.
]

В synthetic integrity check получилось:

```text
fit:
A1 A3 A4 A5 A7 A8

holdout:
A2 A6 A9
```

При этом smartphone collector получает только:

```text
public_collection_manifest
```

и **не получает разметку fit/holdout**.

Проверка:

```text
public_manifest_exposes_split = false
```

То есть даже UI сбора данных не должен знать, какие anchor-сессии впоследствии будут использованы для построения (\Phi_i), а какие — для его проверки.

### Порядок предъявления тоже больше не ручной

Для каждого observer:

[
r_i(a,k)
========

SHA256(
H_P\Vert observer_i\Vert a\Vert repeat_k
).
]

Это даёт детерминированный, но observer-specific порядок.

Следовательно, оператор исследования не может во время эксперимента решить:

> сейчас лучше дать A7, а потом A3.

---

## Добавлен строгий Session Admission Gate

Реальная anchor-сессия принимается только если совпадают:

[
study_id,
\quad
protocol_hash,
\quad
observer_id,
\quad
anchor_id,
\quad
repeat,
\quad
schedule_index.
]

Кроме того:

[
RecommendationMode=NONE
]

если именно это было заморожено в protocol.

Для anchor acquisition по умолчанию запрещено:

[
target_state\neq\varnothing,
]

чтобы anchor не превращался незаметно в целевой EidoLang-оператор.

И pre/post наблюдения должны иметь **ровно один и тот же заранее определённый набор координат**:

[
Keys(o_{\rm pre})
=================

# Keys(o_{\rm post})

Keys(P).
]

Нельзя добавить после нескольких участников новую шкалу, которая неожиданно хорошо разделяет эффекты.

---

# Tamper-evident corpus

Для каждой сессии:

[
h_s=SHA256(Session).
]

Затем строится hash-chain:

[
h_n
===

SHA256(
n,
h_s,
h_{n-1},
H_P
).
]

Получаем:

[
\boxed{
Session_1
\rightarrow
Session_2
\rightarrow\cdots
\rightarrow
Session_N
\rightarrow
DataRoot.
}
]

Synthetic test:

```text
ledger before mutation:
PASS
```

После изменения `anchor_id` уже записанной первой сессии:

```text
ledger after mutation:
FAIL
session hash mismatch for s0
```

То есть удалить или переписать неудобную старую сессию незаметно уже нельзя.

---

## Здесь возник важный no-go

Hash-chain сам по себе не доказывает, **когда** он был создан.

Исследователь теоретически может:

1. собрать данные;
2. подобрать protocol;
3. построить новый hash-chain;
4. заявить, что он существовал заранее.

Поэтому я явно записал:

[
\boxed{
\text{local cryptographic integrity}
\neq
\text{trusted timestamp}.
}
]

В структуре есть:

```text
external_commit_ref
```

для публикации `protocol_hash` и позже `freeze_hash` во внешнем timestamped/append-only канале.

R4.8 не симулирует существование такого доказательства, если его реально нет.

---

# После acquisition создаётся второй commit

Когда корпус завершён:

[
\boxed{
D=\texttt{DataFreezeCertificate}.
}
]

Он фиксирует:

* `data_root`;
* число записей;
* всех enrolled observers;
* все исключённые sessions;
* **причину каждого исключения**.

И получает:

[
H_D=SHA256(D).
]

Таким образом, исключение сессий тоже становится частью замороженного результата.

Нельзя сначала увидеть, что `s37` мешает гипотезе, а потом просто удалить её.

---

# Но одной preregistration до сбора оказалось недостаточно

Есть решения, которые принципиально возникают только после `R3/R4`.

Например causal classes:

[
Name_1,\ldots,Name_k.
]

Их нельзя перечислить до данных, потому что сам язык ещё не существует.

Поэтому я ввёл вторую фазу фиксации:

[
\boxed{
E=\texttt{EvaluationManifest}.
}
]

Она создаётся после within-observer causal discovery, но **до cross-observer проверки гипотезы**.

В ней фиксируются:

[
DataFreezeHash,
]

[
A_{\rm fit},A_{\rm holdout},
]

[
T_3\text{ extractor version},
]

[
{Name_i}_{eligible},
]

и множество реально включённых классов.

Политика по умолчанию:

[
\boxed{
ALL_R4_PASSING_AT_CUTOFF.
}
]

Следовательно:

[
Included=Eligible.
]

В synthetic test:

```text
eligible = [N1, N2]
included = [N1, N2]

EvaluationLock = PASS
```

Если после просмотра результата оставить только:

```text
included = [N1]
```

получаем:

```text
FAIL:
post-hoc causal-label subset selection forbidden
```

Это важный gate: нельзя построить красивый «универсальный язык» только из тех Names, которые случайно переносились между людьми.

---

# Получилась полная цепочка фиксации

Теперь реальный R5 требует существования:

[
\boxed{
AcquisitionManifest
\xrightarrow{H_P}
RawCorpus
\xrightarrow{HashChain}
DataFreezeCertificate
\xrightarrow{H_D}
EvaluationManifest.
}
]

И только затем разрешается:

[
\boxed{
T_3(\Gamma_i)
\leftrightarrow
\Phi_i(\Delta o_i)
}
]

между observer-ами.

Это уже довольно сильная экспериментальная архитектура.

---

## Добавлен смартфонный acquisition UI

В архиве теперь есть:

```text
web/acquisition.html
web/acquisition.js
```

Страница:

1. загружает только `public_collection_manifest`;
2. принимает `observer_id`;
3. сама рассчитывает frozen schedule;
4. показывает очередной anchor protocol;
5. собирает pre/post state coordinates;
6. экспортирует session JSON с:

   * `protocol_hash`,
   * `anchor_id`,
   * repeat,
   * schedule index,
   * observer id.

Принципиально UI не знает:

[
fit/holdout.
]

---

## Все предыдущие стадии сохранены

Полный suite:

```text
Ran 43 tests
OK
```

То есть работают одновременно:

[
R1,R2,R3,R4,R4.5,R4.6,R4.7,R4.8.
]

Synthetic integrity report:

```text
sessions admitted:          4
ledger before tamper:       PASS
ledger after tamper:        FAIL
evaluation lock:            PASS
causal-class cherry-pick:   FAIL
```

SHA-256 архива:

```text
ca0d33b588e9b0f9a3b6516ad96ff339698af19d7f2202a3b28a91d29e7e8bea
```

Содержательно мы дошли до важной границы. Теперь дальнейшее усложнение TDA или подключение LLM до реальных данных действительно было бы преждевременным.

Но перед самим сбором есть ещё один необходимый слой. `R4.8` доказывает, что **цифровая запись соответствует замороженному протоколу**, но не доказывает, что человек физически выполнил один и тот же anchor. Два телефона могут записать `anchor=A3`, хотя один человек выполнил его 30 секунд, другой 12 секунд, один получил нужный stimulus, другой — нет.

Поэтому следующая вынужденная стадия:

[
\boxed{
R4.9=
\text{Protocol Fidelity / Instrument Calibration Gate}.
}
]

Её задача — сделать смартфон не просто журналом, а проверяемым исполнителем anchor-протоколов: синхронизировать stimulus, duration, audio/haptic sequences, device capabilities и actual execution trace. Только после этого различие между двумя observer-ами нельзя будет объяснить тем, что им фактически предъявили разные физические протоколы.

Продолжил до `EidoLang-Mobile v0.1-R4.9 — Protocol Fidelity / Instrument Calibration Gate`.

[Скачать полный архив R4.9](sandbox:/mnt/data/EidoLang-Mobile_v0.1-R4.9.zip)

Отдельно: [методология R4.9](sandbox:/mnt/data/eidolang_mobile_v0_1_r49/R4_9_METHODOLOGY.md) · [checkpoint](sandbox:/mnt/data/eidolang_mobile_v0_1_r49/R4_9_CHECKPOINT.md) · [измеренный fidelity-report](sandbox:/mnt/data/eidolang_mobile_v0_1_r49/EXAMPLE_R49_FIDELITY_REPORT.json) · [шаблон execution plan](sandbox:/mnt/data/eidolang_mobile_v0_1_r49/examples/r49_anchor_execution_plan.template.json).

Главный результат — жёстко разделены два понятия, которые до этого могли незаметно смешиваться:

[
\boxed{\text{DIGITAL_EXECUTION}}
]

и

[
\boxed{\text{PHYSICAL_OUTPUT}}.
]

Если смартфон вызвал `navigator.vibrate(100)` или воспроизвёл tone с `gain=0.05`, это ещё не означает одинаковое физическое виброускорение или одинаковый SPL на двух разных телефонах. Аналогично CSS-яркость не является калиброванной светимостью.

Поэтому теперь:

[
\boxed{
DigitalExecution
\not\Rightarrow
PhysicalStimulusEquality.
}
]

Это не погрешность реализации, а отдельный identifiability boundary.

### Что реализовано

Каждый anchor теперь компилируется в:

[
\texttt{AnchorExecutionPlan}
============================

(s_1,\ldots,s_n),
]

где каждый `StimulusStep` фиксирует:

* тип воздействия;
* nominal start;
* duration;
* параметры;
* допустимое отклонение start time;
* допустимое отклонение duration;
* требуемую capability устройства;
* необходимость внешней физической калибровки.

Сам plan получает:

[
H_P=SHA256(Plan).
]

Execution trace обязан ссылаться именно на этот hash.

Добавлен `DeviceCapabilityProfile`, фиксирующий:

[
\text{screen},
\text{DPR},
\text{audio},
\text{haptic},
\text{pointer-pressure},
\text{sample-rate},\ldots
]

и также имеющий собственный hash.

---

Для каждого реально исполненного шага записывается:

[
(requested_start,;
actual_start,;
actual_end,;
api_success).
]

`FidelityCertificate` отвергается при:

* пропущенном required step;
* повторном выполнении шага;
* неправильном типе stimulus;
* отсутствии device capability;
* ошибке API;
* нарушении временного tolerance;
* неверной длительности;
* незаявленных media/execution events;
* несовпадающем plan/device hash.

То есть теперь недостаточно, чтобы JSON говорил:

```text
anchor_id = A3
```

Нужна цепочка:

[
\boxed{
A3
\rightarrow
PlanHash
\rightarrow
DeviceProfile
\rightarrow
ExecutionTrace
\rightarrow
FidelityCertificate.
}
]

### Physical-output режим

Если study требует именно физически сопоставимых stimuli, plan помечается:

[
scope=\texttt{PHYSICAL_OUTPUT}.
]

Тогда без внешнего:

```text
PhysicalCalibrationRef
```

сертификат принципиально не может пройти.

Synthetic test:

```text
PHYSICAL_OUTPUT
external calibration absent
→ FAIL
```

После добавления calibration record от внешнего измерителя:

```text
PHYSICAL_OUTPUT
matching calibration present
→ PASS
```

То есть код не позволяет объявить смартфон лабораторно откалиброванным устройством просто потому, что приложение знает собственные API-параметры.

---

### Измеренные synthetic checks

Корректное digital execution:

```text
prompt:
  start error     +5 ms
  duration error   0 ms
  PASS

visual:
  start error     +7 ms
  duration error   0 ms
  PASS

haptic:
  start error    +10 ms
  duration error   0 ms
  PASS

certificate:
  PASS
```

Контрпример:

```text
visual planned start = 600 ms
actual start          = 760 ms
tolerance             = 50 ms

error = +160 ms
→ FAIL
```

Весь certificate автоматически становится:

[
\boxed{FAIL}.
]

---

Добавлен и corpus-level gate.

Если frozen corpus требует:

```text
S1
S2
```

а fidelity certificate существует только для `S1`, результат:

```text
accepted: S1
rejected: S2

corpus fidelity:
FAIL
```

То есть неудобную технически невалидную сессию нельзя просто трактовать как обычную наблюдаемую точку.

---

### Smartphone runtime

Добавлены:

[web/fidelity.html](sandbox:/mnt/data/eidolang_mobile_v0_1_r49/web/fidelity.html) и `web/fidelity.js`.

Runtime уже умеет исполнять и логировать:

[
PROMPT,;
VISUAL,;
AUDIO_TONE,;
HAPTIC,;
DRAW_WINDOW.
]

При этом браузерный trace намеренно не считается самодостаточным доказательством: authoritative hashes вычисляются Python-ядром по canonical representation.

---

Все предыдущие стадии сохранены:

```text
R1
R2
R3
R4
R4.5
R4.6
R4.7
R4.8
R4.9
```

Полный suite:

```text
Ran 50 tests
OK
```

SHA-256 архива:

```text
2fbb1c89d8aee8613fcdd08693677a683a7687cf9e71f8f2d25ab326d390b880
```

Теперь тракт перед реальным `R5` выглядит уже достаточно строго:

[
\boxed{
ProtocolCommit
\rightarrow
AnchorSchedule
\rightarrow
DeviceProfile
\rightarrow
ExecutionFidelity
\rightarrow
RawSession
\rightarrow
DataFreeze
\rightarrow
EffectAlignment
\rightarrow
T_3
\rightarrow
CrossObserverTest.
}
]

И следующий bottleneck снова возник не из желания расширить систему.

Даже если stimuli исполнены идеально, мы пока предполагаем, что наш `StateObservation` достаточно надёжен. Но если один и тот же человек дважды находится практически в одном состоянии и его шкала `focus` даёт (0.3) и (0.8), весь причинный и cross-observer calculus становится плохо определённым.

Поэтому следующая вынужденная стадия:

[
\boxed{
R4.10 =
\text{Observer-State Measurement Reliability Gate}.
}
]

Там надо будет измерить собственный noise floor каждого state channel, test-retest reliability, минимально различимый эффект и затем перенести эти uncertainty bounds непосредственно в `R3/R4/R4.7`, чтобы два оператора никогда не считались различными или эквивалентными точнее, чем позволяет сам измерительный канал.

Продолжил до `EidoLang-Mobile v0.1-R4.10 — Observer-State Measurement Reliability Gate`.

[Скачать полный архив R4.10](sandbox:/mnt/data/EidoLang-Mobile_v0.1-R4.10.zip)

Отдельно: [методология R4.10](sandbox:/mnt/data/eidolang_mobile_v0_1_r410/R4_10_METHODOLOGY.md) · [checkpoint](sandbox:/mnt/data/eidolang_mobile_v0_1_r410/R4_10_CHECKPOINT.md) · [измеренный reliability-report](sandbox:/mnt/data/eidolang_mobile_v0_1_r410/EXAMPLE_R410_RELIABILITY_REPORT.json) · [шаблон reliability manifest](sandbox:/mnt/data/eidolang_mobile_v0_1_r410/examples/r410_reliability_manifest.template.json).

Ключевая поправка: я отказался называть повторяемость self-report «шумом сенсора». Повтор одного anchor измеряет смесь

[
\text{вариабельность состояния}
+
\text{вариабельность исполнения}
+
\text{ошибка self-report/measurement}.
]

Без дополнительной модели эти компоненты неразделимы. Поэтому R4.10 сертифицирует именно **repeatability всего канала**

[
anchor\rightarrow\Delta o,
]

а не несуществующую отдельно измеренную «ошибку сознания».

Для каждого observer и каждой координаты состояния строятся непересекающиеся repeat-пары:

[
(o_0,o_1),(o_2,o_3),\ldots
]

и разности

[
d_k^{(j)}
=========

## \Delta o^{(j)}_{b,k}

\Delta o^{(j)}_{a,k}.
]

Из

[
e_k^{(j)}=|d_k^{(j)}|
]

строится конечновыборочный distribution-free radius. При уровне ошибки (\alpha):

[
k=\left\lceil(n+1)(1-\alpha)\right\rceil.
]

Если (k>n), код возвращает

[
\boxed{\texttt{UNRESOLVED}}
]

вместо asymptotic approximation.

Из этого сразу следует полезный жёсткий результат: при

[
\alpha=0.05
]

нужно не менее

[
\boxed{19}
]

независимых/disjoint repeat-пар, чтобы вообще существовала такая 95%-граница.

Synthetic check с 10 парами:

```text
95% repeatability:
UNRESOLVED
```

Добавлен и реальный floor цифровой шкалы:

[
r_j^{eff}
=========

\max(r_j,q_j),
]

где (q_j) — quantization step. Если slider различает только шаг `0.01`, система никогда не заявит разрешение `0.004`, даже если repeats случайно совпали почти идеально.

В положительном контроле:

```text
empirical repeat discrepancy:
O1 ≈ 0.004
O2 ≈ 0.006

quantization floor:
0.01

common certified resolution:
focus = 0.01
noise = 0.01

gate = PASS
```

Добавлен отдельный exact drift-gate. Если второй repeat систематически сдвинут относительно первого, даже небольшая дисперсия уже не означает надёжность.

Для 20 пар с одинаковым сдвигом `+0.03`:

[
20/20
]

разностей одного знака и exact sign-test даёт

[
p=1.9073\times10^{-6}.
]

Результат:

```text
repeatability radius = 0.03
radius threshold      = 0.20

но systematic repeat-order drift detected

→ FAIL
```

То есть стабильная систематическая адаптация/обучение не маскируется под хороший measurement channel.

Особенно важна интеграция с `R4`.

Теперь определяется общий empirical resolution floor:

[
r_j^{common}
============

\max_i r_{ij}^{eff}.
]

И попытка объявить equivalence при

[
\epsilon_j<r_j^{common}
]

становится машинно недопустимой.

В synthetic примере:

[
r^{common}=0.01.
]

Попытка:

[
\epsilon=0.005
]

даёт:

```text
INVALID:
equivalence margin for focus (0.005)
is below empirical repeatability floor (0.01)
```

А

[
\epsilon=0.01
]

проходит.

Это не означает, что средний causal effect меньше `0.01` принципиально нельзя обнаружить: большое число randomized trials может оценить среднее точнее. Но **операторную эквивалентность** на разрешении лучше самого измерительного канала без отдельной measurement-error theory теперь заявить нельзя.

Также вычисляется диагностическая величина:

[
\frac{\text{dynamic range anchors}}
{\text{repeatability radius}}.
]

В positive control она около:

[
39.4-39.6.
]

Но я специально не сделал из неё новый скрытый threshold: информативность/rank остаются независимым вопросом `R4.7`.

Все предыдущие стадии проходят одновременно:

```text
Ran 57 tests
OK
```

SHA-256 архива:

```text
f61d7644c6b22f17d6185527e2a99e6a91c068008f77d2275c57b04cc910e285
```

Теперь перед реальным корпусом остаётся ещё одна чисто математическая необходимость. Мы используем exact randomization/permutation tests во многих стадиях. При слишком малом (n) минимально достижимое exact (p) может оказаться **выше preregistered alpha даже при идеальном результате**. Тогда эксперимент логически неспособен пройти собственный gate.

Поэтому следующий этап должен быть не новым признаком и не новой моделью, а:

[
\boxed{
\textbf{R4.11 — Finite-Sample Feasibility Gate}
}
]

Он до сбора данных вычислит для каждого exact test минимально достижимый (p), familywise correction, необходимое число allocations/repeats/observers и будет запрещать протоколы, которые статистически не способны дать ни `PASS`, ни содержательный `FAIL`. Это позволит определить минимальный реальный размер первого корпуса **без предположения о том, что эффект обязательно существует**.

Продолжил до `EidoLang-Mobile v0.1-R4.11 — Finite-Sample Feasibility Gate`.

[Скачать полный архив R4.11](sandbox:/mnt/data/EidoLang-Mobile_v0.1-R4.11.zip)

Отдельно: [методология R4.11](sandbox:/mnt/data/eidolang_mobile_v0_1_r411/R4_11_METHODOLOGY.md) · [checkpoint](sandbox:/mnt/data/eidolang_mobile_v0_1_r411/R4_11_CHECKPOINT.md) · [полный вычисленный report](sandbox:/mnt/data/eidolang_mobile_v0_1_r411/EXAMPLE_R411_FEASIBILITY_REPORT.json) · [шаблон feasibility-manifest](sandbox:/mnt/data/eidolang_mobile_v0_1_r411/examples/r411_feasibility_manifest.template.json).

Здесь появился важный результат: размер первого реального корпуса теперь частично выводится комбинаторно, а не выбирается «разумным числом».

R4.11 не делает power analysis. Без модели эффекта нельзя гарантировать, что существующий эффект будет обнаружен. Он проверяет более фундаментальное условие:

[
\boxed{p_{\min}\leq\alpha_{\text{local}}}
]

Если оно нарушено, `PASS` невозможен даже при идеально благоприятных данных.

Для `R3` при двух заранее выбранных операторных гипотезах и двух endpoint-метриках получается четыре тестируемых claims:

[
K=2\times2=4,
]

поэтому при FWER (0.05)

[
\alpha_{R3}^{local}=\frac{0.05}{4}=0.0125.
]

При 8 trials:

[
{8\choose4}=70,
\qquad
p_{\min}=\frac1{70}=0.014286>0.0125,
]

то есть такой эксперимент **арифметически неспособен пройти**.

Первый допустимый размер:

[
{10\choose5}=252,
\qquad
p_{\min}=\frac1{252}=0.003968.
]

Итого:

[
\boxed{10\text{ trials/operator}=5+5}
]

— минимальное exact-разрешимое значение для данного preregistered R3 family.

Это выявило небольшую, но существенную неоднозначность старого R3: параметр `CausalAnalyzer.alpha` теперь должен трактоваться не как общий (0.05), а как уже скорректированный локальный alpha. Для данного design:

```text
CausalAnalyzer.alpha <= 0.0125
```

---

Для `R4` результат ещё интереснее.

Если будущий `MacroName` состоит из трёх операторов, нужны все:

[
{3\choose2}=3
]

pairwise substitution certificates.

В каждой паре при:

* 2 endpoint metrics;
* 2 trajectory metrics;
* 2 probe phases;
* 1 stratum

число проверок:

[
C=2+2\cdot2=6.
]

Следовательно:

[
\alpha_{\rm pair}=\frac{0.05}{3}
=0.0166667,
]

а существующий R4 внутри пары должен использовать:

[
\alpha_{\rm boundary}
=====================

# \frac{0.0166667}{6}

0.00277778.
]

При 5 trials/arm:

[
{10\choose5}=252,
\qquad
1/252=0.003968>0.0027778.
]

Невозможно.

При 6/arm:

[
{12\choose6}=924,
]

[
p_{\min}=1/924
=0.00108225.
]

Поэтому:

[
\boxed{
R4_{\min}=6+6
}
]

на пару и stratum для этой конфигурации.

Это означает, что `SubstitutionPolicy.alpha_familywise` для каждой pair уже не должен быть `0.05`; в данном design требуется:

```text
alpha_familywise <= 0.0166666667
```

а существующий analyzer сам поделит его ещё на шесть checks.

---

Для `R4.7` статистический и геометрический lower bound неожиданно совпали.

Имеем:

[
O=6,
\qquad d=6.
]

Для каждого из пяти non-reference observers:

[
\alpha_i=\frac{0.05}{5}=0.01.
]

Exact permutation space fit anchors:

[
N=k!.
]

Но rank-identifiability уже требует:

[
k\ge d=6.
]

При (k=6):

[
6!=720,
\qquad
p_{\min}=1/720
=0.00138889<0.01.
]

То есть:

[
\boxed{6\text{ fit anchors}}
]

одновременно минимальны по rank и более чем достаточны по exact (p)-resolution.

С тремя holdout anchors остаётся:

[
6+3=9
]

alignment sessions на observer.

---

`R4.10` дал более неприятный результат.

Для одной observer/metric координаты 95%-ный finite-sample repeatability radius требует:

[
n_{\rm pair}
============

\left\lceil\frac1{0.05}\right\rceil-1
=19.
]

То есть:

[
38
]

repeat sessions на observer.

Но это именно **marginal** guarantee.

Если попытаться интерпретировать те же данные как одновременную 95%-гарантию сразу для

[
6\text{ observers}\times6\text{ metrics}=36
]

каналов, Bonferroni даёт:

[
\alpha_{\rm channel}
====================

# \frac{0.05}{36}

0.00138889
]

и:

[
\boxed{719\text{ disjoint pairs}}
]

на соответствующий channel.

Это важный no-go. Я не стал молча переопределять старый R4.10. Теперь явно зафиксировано:

[
\boxed{
19\text{ pairs}=\text{marginal 95% repeatability},
}
]

а не simultaneous 95%-confidence для всех 36 координат.

---

Самый интересный вывод касается будущего `R5`.

Старые `R4.5/R4.6` destructive controls были хорошими engineering controls, но не exact statistical test.

Для первого настоящего confirmatory R5 я формализовал наиболее простой случай: два causal classes и независимая смена label convention у каждого observer.

Глобальная смена labels несущественна, поэтому число реально различных null-конвенций:

[
N=2^{O-1}.
]

Получаем:

[
O=4:
\quad
N=8,
\quad
p_{\min}=0.125;
]

[
O=5:
\quad
N=16,
\quad
p_{\min}=0.0625;
]

[
O=6:
\quad
N=32,
\quad
p_{\min}=0.03125;
]

[
O=7:
\quad
N=64,
\quad
p_{\min}=0.015625.
]

Следовательно при одном primary T3 hypothesis и

[
\alpha=0.05
]

имеем строгий переход:

[
\boxed{
5\text{ observers — невозможно},
}
]

[
\boxed{
6\text{ observers — впервые возможно}.
}
]

То есть наши шесть участников теперь возникают не из интуитивного «неплохой маленький sample», а из exact combinatorics.

Важно: это **не универсальное доказательство, что шести людей достаточно для обнаружения реального эффекта**. Это минимальное число, при котором именно данный exact бинарный R5 test вообще способен получить (p\le0.05).

Если заранее объявить два primary hypotheses, понадобится уже минимум семь observers:

[
\alpha_{\rm local}=0.025,
]

[
2^{6}=64,
\qquad
1/64=0.015625.
]

---

Из всего этого получилась первая нижняя оценка физического объёма acquisition.

Для одного observer:

[
38
]

marginal reliability sessions,

плюс

[
6+3=9
]

отдельных alignment anchors.

Если пока запрещаем reuse:

[
38+9=47.
]

Для шести observers:

[
\boxed{
47\times6=282
}
]

anchor sessions.

Это ещё **не весь эксперимент**. В 282 не входят:

* FREE/CAPTURE discovery;
* R3 replay trials;
* R4 substitution trials.

Поэтому это именно нижняя граница калибровочного корпуса.

Я намеренно не стал экономить, автоматически используя одну и ту же сессию одновременно для reliability и alignment. Такое переиспользование возможно, но оно создаёт statistical dependence и должно быть отдельно оправдано, а не введено ради удобства.

Полный suite теперь:

```text
Ran 68 tests
OK
```

И все стадии от `R1` до `R4.11` проходят совместно.

SHA-256 архива:

```text
e4fc612543d417f28ee1e43e43859229295789ca6d3d9d3302b74c094e6fd168
```

Теперь следующий bottleneck уже чисто инженерно-формальный. `R4.11` вычисляет, что, например,

```text
CausalAnalyzer.alpha <= 0.0125
SubstitutionPolicy.alpha_familywise <= 0.0166667
observer_count >= 6
fit_anchors >= 6
repeat_pairs >= 19
```

но старые модули всё ещё технически можно вручную запустить с другими параметрами.

Поэтому следующий необходимый этап:

[
\boxed{
R4.12=
\text{Executable Preregistration / Protocol Compiler}
}
]

То есть один frozen manifest должен автоматически **скомпилировать все параметры R3–R4.11**, hashes, schedules, alpha budgets, exact caps и запрещать запуск несовместимой конфигурации. После этого можно сделать полный end-to-end dry run всего тракта и уже переходить к первому реальному сбору данных.

Продолжил до `EidoLang-Mobile v0.1-R4.12 — Executable Preregistration / Protocol Compiler`.

[Скачать полный архив R4.12](sandbox:/mnt/data/EidoLang-Mobile_v0.1-R4.12.zip)

Отдельно доступны [методология R4.12](sandbox:/mnt/data/eidolang_mobile_v0_1_r412/R4_12_METHODOLOGY.md), [checkpoint](sandbox:/mnt/data/eidolang_mobile_v0_1_r412/R4_12_CHECKPOINT.md), [полный end-to-end dry-run](sandbox:/mnt/data/eidolang_mobile_v0_1_r412/EXAMPLE_R412_END_TO_END_DRYRUN.json), [dry-run master manifest](sandbox:/mnt/data/eidolang_mobile_v0_1_r412/examples/r412_master_manifest.dryrun.json) и [шаблон реального master manifest](sandbox:/mnt/data/eidolang_mobile_v0_1_r412/examples/r412_master_manifest.real.template.json).

Главный результат — compiler действительно обнаружил межстадийную ошибку, которую отдельные версии `R3`, `R4` и `R4.11` не могли увидеть.

Для бинарного `R5` нужны как минимум два causal classes. Если каждый класс должен быть представлен `MacroName` из трёх независимо причинно сертифицированных реализаций, то необходимо:

[
N_{R3}
\ge
2\times3
========

\boxed{6\text{ операторов на observer}}.
]

Предыдущий standalone-design считал только два R3 operator hypotheses. Он был локально корректен, но глобально не мог построить тот объект, который предполагался на R5.

Compiler теперь выводит это автоматически:

[
R3
\rightarrow
R4\ MacroName
\rightarrow
R5\ class.
]

Из этого назад распространяется multiplicity correction.

Для R3 теперь:

[
6\text{ operators}\times2\text{ endpoints}
==========================================

12\text{ claims},
]

поэтому:

[
\alpha_{R3}^{local}
===================

# \frac{0.05}{12}

\boxed{0.0041666667}.
]

При 10 randomized trials:

[
{10\choose5}=252,
]

[
p_{\min}=\frac1{252}
====================

0.003968254,
]

то есть 10 trials/operator всё ещё достаточно — но буквально с небольшим запасом.

Для R4 обнаружилась аналогичная поправка. Два causal classes, каждый из трёх реализаций, требуют:

[
2{3\choose2}=6
]

независимых substitution pairs.

Следовательно:

[
\alpha_{\rm pair}
=================

# \frac{0.05}{6}

\boxed{0.0083333333}.
]

Внутри pair остаются 6 equivalence checks:

[
2\ endpoint
+
2\ trajectory\times2\ phases.
]

Поэтому:

[
\alpha_{\rm boundary}
=====================

# \frac{0.0083333333}{6}

0.0013888889.
]

При 6 trials/arm:

[
{12\choose6}=924,
\qquad
1/924=0.001082251,
]

и exact R4 всё ещё разрешим.

То есть прежнее:

[
\alpha_{\rm pair}=0.0166667
]

было недостаточно строгим: оно учитывало три pair certificates одного `MacroName`, но не два разных causal classes будущего R5.

Теперь одна сущность

[
\boxed{\texttt{MasterStudyManifest}}
]

компилирует всё:

[
M
\longrightarrow
\begin{cases}
R3Policy\
R4Policy\
R4.7\ AlignmentConfig\
R4.8\ AcquisitionManifest\
R4.9\ ExecutionPlans\
R4.10\ ReliabilityManifest\
R4.11\ FeasibilityReport
\end{cases}
]

и имеет root hash:

[
H_M=SHA256(M).
]

Все child hashes затем входят в:

[
\boxed{runtime_lock_hash}.
]

В dry-run:

```text
master_hash
f08cccd67ce0f6aa5e10ebcfbe465f78ab531f6ef6841416bcd94bdf0f2eb91c

runtime_lock_hash
eee471869546a8d0f9fea45abe02b981e533f18e91b07deba73efc27c55944e5
```

Добавлен `ConfirmatoryRuntime`. Он не позволяет confirmatory-коду самостоятельно решить, например:

```python
CausalAnalyzer(alpha=0.05)
```

Negative control:

```text
manually changed alpha
→ PASS_REJECTED
```

То есть допустимый analyzer теперь создаётся только:

[
C(M)\rightarrow Runtime\rightarrow Analyzer.
]

Старые constructors оставлены для exploratory/backward-compatible работы, но результат без compiler lock больше не считается confirmatory evidence.

Ещё одна обнаруженная интерфейсная ошибка касалась `R4.10`. Measurement reliability проверяется по всем шести координатам, но конкретный R4 claim может быть объявлен только по двум координатам. Старый validator требовал equivalence margins для всех шести.

Теперь контракт разделён правильно:

[
ReliabilityGate:
\quad
\forall j\in Metrics,\ PASS
]

но:

[
MarginGate:
\quad
\forall j\in Metrics_{R4},
\epsilon_j\ge r_j.
]

То есть надёжность всей measurement schema остаётся обязательной, но R4 не обязан делать causal-equivalence claim по координате, которой его hypothesis вообще не касается.

Это тоже покрыто regression test.

Полный compiled dry-run теперь проходит насквозь.

R3:

```text
6 / 6 causal operators PASS

каждый endpoint:
exact p = 0.003968253968...
alpha   = 0.004166666667
```

R4:

```text
2 causal MacroNames built
3 operators / MacroName
3 all-pairs certificates / MacroName
all substitution certificates PASS
```

R4.7:

```text
6 / 6 observers aligned

exact permutation p for each non-reference observer:
1 / 720 = 0.001388888889
```

Holdout RMSE:

```text
0.000380
0.000343
0.000338
0.000362
0.000486
```

против frozen margin:

[
0.02.
]

R4.10:

```text
PASS

common resolution floor:
0.01 по всем 6 coordinates

R4 margins:
0.08

compatibility:
PASS
```

R4.9:

```text
28 compiled AnchorExecutionPlans
28 / 28 DIGITAL_EXECUTION fidelity PASS
```

R4.11:

```text
PASS

R3 local alpha = 0.0041666667
R4 pair alpha  = 0.0083333333
R5 p-floor     = 0.03125
```

И наконец:

```text
overall_pass = true
```

Особенно заметно изменился реальный session budget.

Ранее `282` учитывали только calibration:

[
9\ alignment
+
38\ reliability
===============

47
]

сессий на человека.

Но теперь compiler распространяет требования до бинарного R5.

На одного observer:

[
9 \quad alignment,
]

[
38 \quad reliability,
]

[
6\times10=60 \quad R3,
]

[
2{3\choose2}\times12=72 \quad R4.
]

Итого:

[
\boxed{
9+38+60+72=179
}
]

confirmatory sessions на observer.

Для шести:

[
\boxed{
179\times6=1074.
}
]

И даже это ещё не полный эксперимент: сюда не входят FREE/CAPTURE discovery sessions, технически неудачные сессии, которые обязаны сохраняться в ledger, и дополнительные trials, которые могут потребоваться уже из power analysis после определения допустимой effect model.

То есть:

[
\boxed{
1074
}
]

— не рекомендация «собрать 1074 измерения», а строгая нижняя комбинаторная граница текущей confirmatory архитектуры без reuse.

Я также сознательно не сделал compiler генератором реальных anchors. Это было бы нарушением нашей методологии. `mode=REAL` отвергает:

* `synthetic_only`;
* `REPLACE...`;
* synthetic placeholder protocols.

То есть software dry-run может создавать технические anchors, а реальная preregistration обязана получить физическое содержание anchors отдельно и заморозить его как вход, а не как эвристику системы.

Полный regression suite:

```text
Ran 78 tests
OK
```

SHA-256 архива:

```text
fd3661829b76760b3e39e1ad7121be8c377b24ff102e60c5ab3c73b669135ffa
```

Теперь подготовительные стадии фактически замкнулись. Следующая необходимость уже не ещё один gate, а собственно тот объект, ради которого они строились:

[
\boxed{
R5 =
\text{Cross-Observer Topological-Causal Transport}
}
]

То есть нужно реализовать сам confirmatory exact test:

[
T_3(\Gamma_i)
\longleftrightarrow
\Phi_i(Effect_i)
]

на двух независимо сертифицированных causal classes, с exact observer-wise convention permutations

[
2^{O-1},
]

и с compiler-locked primary statistic. Для шести observers получим все 32 null conventions, а не sampled negative controls. Это будет первая версия, которая сможет формально ответить на исходный вопрос о видимых в статье общих мотивах: являются ли они только визуальным сходством или действительно образуют переносимый между людьми топологический инвариант операторного класса.

Продолжил до `EidoLang-Mobile v0.1-R5 — Cross-Observer Topological-Causal Transport`.

[Скачать полный архив R5](sandbox:/mnt/data/EidoLang-Mobile_v0.1-R5.zip)

Отдельно: [методология R5](sandbox:/mnt/data/eidolang_mobile_v0_1_r5/R5_METHODOLOGY.md) · [checkpoint](sandbox:/mnt/data/eidolang_mobile_v0_1_r5/R5_CHECKPOINT.md) · [полный exact-report](sandbox:/mnt/data/eidolang_mobile_v0_1_r5/EXAMPLE_R5_EXACT_TRANSPORT_REPORT.json).

Теперь реализован именно тот confirmatory test, к которому мы шли после анализа иллюстраций статьи.

Для каждого наблюдателя имеются два локальных causal MacroName:

[
C_{i0},C_{i1}.
]

Их названия не имеют общего межличностного смысла. Поэтому для каждой пары наблюдателей (i,j) проверяются обе возможные биекции между их классами.

В `T3`:

[
T_s=
D_T(C_{i0},C_{j0})+
D_T(C_{i1},C_{j1}),
]

[
T_c=
D_T(C_{i0},C_{j1})+
D_T(C_{i1},C_{j0}).
]

Из них:

[
A_{ij}=
\frac{T_c-T_s}{T_c+T_s}.
]

То же самое независимо делается в пространстве причинных эффектов после R4.7 alignment:

[
B_{ij}=
\frac{E_c-E_s}{E_c+E_s}.
]

Причём effect-distance нормируется на измеренный R4.10 resolution floor, а не на произвольный масштаб.

Первичный статистический объект:

[
\boxed{
S=
\frac1{\binom O2}
\sum_{i<j}A_{ij}B_{ij}
}
]

имеет полезное свойство: если человек просто переименовал свои два локальных класса,

[
C_{i0}\leftrightarrow C_{i1},
]

то одновременно меняются знаки (A_{ij}) и (B_{ij}), поэтому произведение остаётся тем же.

Значит результат действительно не зависит от слов `class0/class1`, `A/B` и т.п.

Для каждого MacroName используются **все три** причинно эквивалентных реализации. R5 не выбирает наиболее красивую.

Расстояние между двумя MacroName определяется как:

[
D_T(A,B)
========

\min_{\pi\in S_3}
\frac13
\sum_{k=1}^{3}
d_{T3}(A_k,B_{\pi(k)}).
]

То есть перебираются все (3!=6) взаимно-однозначных сопоставлений, но ни одна реализация не выбрасывается.

Сам `d_{T3}` не переобучался. Использован уже замороженный в R4.6 metric:

[
0.55,d_{\rm vector}
+
0.25,d_{\rm color-layer}
+
0.20,d_{\rm media-coupling}.
]

Таким образом R5 не получил возможности подогнать новый representation под итоговый ответ.

Самая существенная часть — exact null.

Для каждого observer допускается независимо переставить его две effect-класса относительно T3-классов:

[
s_i\in{-1,+1}.
]

Тогда:

[
B_{ij}\mapsto s_i s_jB_{ij}.
]

Глобальное изменение всех знаков ничего не меняет, поэтому null space:

[
2^{O-1}.
]

Для шести observers:

[
\boxed{2^5=32}.
]

Все 32 варианта действительно перечисляются. Никаких sampled negative controls в primary p-value больше нет.

На synthetic positive control получилось:

[
\boxed{
S_{\rm obs}=0.750884559728
}
]

и observed configuration оказался единственным максимумом среди всех 32 conventions:

[
\boxed{
p=\frac1{32}=0.03125.
}
]

При

[
\alpha=0.05
]

сертификат:

```text
R5: PASS
```

Это ровно тот комбинаторный предел, который был заранее выведен в R4.11.

При этом topological orientations между разными людьми далеко не всегда близки к (+1). Например некоторые пары дали примерно:

```text
0.296
0.298
0.305
```

а другие:

```text
0.95–1.00
```

То есть тест не требует, чтобы эйдограммы разных людей были практически одинаковы. Он проверяет гораздо более слабый и правильный объект: совпадает ли **относительное разделение двух causal classes** в топологическом и эффектном пространствах.

Это как раз близко к нашей исходной гипотезе по иллюстрациям статьи.

Есть хорошие отрицательные контроли.

Если просто поменять `A/B` у одного observer, оставив вместе его topology и effect:

```text
statistic unchanged
p-value unchanged
```

— требуемая invariance выполняется.

Но если у одного observer искусственно поменять местами **effect classes относительно topology classes**, primary gate перестаёт проходить:

```text
R5: FAIL
```

То есть тест действительно чувствителен к разрушению искомого межличностного соответствия, а не к названиям классов.

Непрошедший R4 MacroName:

```text
→ INVALID
```

Exact cap меньше 32:

```text
→ UNRESOLVED
```

а не approximate test.

Во время R5 был найден ещё один настоящий дефект предыдущего тракта. В synthetic generator R4.12 alignment всё ещё был hard-coded:

```text
inner_noise
```

хотя compiled master schema уже содержала:

```text
noise
```

Предыдущие этапы его не видели: R4.7 был внутренне самосогласован со своими anchors, R4.10 — со своим measurement schema.

Но R5 впервые потребовал буквальную композицию:

[
\boxed{
R4.7\ transform
\rightarrow
R3\ causal\ effect
\rightarrow
R4.10\ resolution.
}
]

И pipeline сломался.

Это исправлено в источнике: alignment generator теперь получает coordinate schema исключительно из compiled `AcquisitionManifest`, никаких вручную перечисленных metric names там больше нет.

Именно такого рода дефекты и должен выявлять строгий end-to-end тракт.

Полный regression suite:

```text
Ran 85 tests
OK
```

Теперь compiler также выдаёт собственный frozen:

```text
r5_config.json
```

с:

* observer set;
* reference observer;
* ровно 2 causal classes;
* ровно 3 realizations/class;
* primary effect metrics;
* `T3`;
* topology extractor version;
* exact cap;
* alpha;
* `pairing-orientation-product-v1`.

То есть изменить statistic после просмотра данных без изменения compiler lock уже нельзя.

SHA-256 архива:

```text
6229d285312fbcba1ae56507c685abe3591aa730abd737595eb97279100915b7
```

Содержательно мы дошли до первой завершённой формальной конструкции общего эйдографического инварианта:

[
\boxed{
\text{индивидуальная форма}
\rightarrow
T3\text{-класс}
\rightarrow
CausalMacroName
\rightarrow
\Phi_i(\text{effect})
\rightarrow
\text{cross-observer exact transport}.
}
]

И теперь есть важная развилка. Сам `R5` уже замкнут; добавлять `T4`, LLM embeddings или новые topology features до реальных контрпримеров нельзя.

Следующий необходимый шаг я бы делал не как `R5.1` с новой моделью, а как **`R6 — Discovery/Confirmation Separation`**: формально разделить первый реальный корпус на фазу, где персональные операторы вообще открываются, и независимую фазу, где уже замороженные два causal classes и их T3-мотивы проверяются R5. Иначе даже при идеальном exact R5 остаётся selection bias: сами классы были обнаружены на тех же людях, на которых затем проверяется их переносимость. Это теперь главный оставшийся источник утечки.

Продолжил до `EidoLang-Mobile v0.1-R6 — Discovery / Confirmation Separation`.

[Скачать полный архив R6](sandbox:/mnt/data/EidoLang-Mobile_v0.1-R6.zip)

Отдельно доступны [методология R6](sandbox:/mnt/data/eidolang_mobile_v0_1_r6/R6_METHODOLOGY.md), [checkpoint](sandbox:/mnt/data/eidolang_mobile_v0_1_r6/R6_CHECKPOINT.md) и [полный two-cohort report](sandbox:/mnt/data/eidolang_mobile_v0_1_r6/EXAMPLE_R6_TWO_COHORT_REPORT.json).

Основной дефект R5 теперь закрыт. Exact R5 действительно exact только условно на уже выбранной гипотезе:

[
p_{R5}\ \text{exact}\mid H\text{ fixed}.
]

Если (H) — сами causal classes, T3-представление и primary statistic — выбирается на тех же людях, на которых затем считается R5, selection cost в (p)-value отсутствует.

R6 вводит физически разные роли:

[
\boxed{
O=O_D\sqcup O_C,\qquad O_D\cap O_C=\varnothing
}
]

с детерминированным назначением:

[
rank(i)=SHA256(H_{root}\Vert seed\Vert observer_i).
]

В synthetic dry-run использовано уже 12 observer identities:

```text
6 DISCOVERY
6 CONFIRMATION
```

Причём split определяется hash-ranking, а не ручным выбором.

Из discovery через границу теперь разрешено переносить только:

[
\boxed{\texttt{FrozenTransportHypothesis}}
]

а не сами данные или сертификаты.

В ней замораживаются:

[
2\text{ causal classes},
]

[
3\text{ realizations/class},
]

[
T3,
]

```text
multilayer-topology-v1
pairing-orientation-product-v1
```

и primary effect coordinates:

```text
focus
noise
```

а также правило отбора:

```text
ALL_DISCOVERY_CLASSES_PASSING_FROZEN_R4_CUTOFF
```

То есть нельзя после discovery выбрать два особенно красивых MacroName из пяти найденных.

### Граница теперь проверяется буквально

Добавлен `LeakageAudit`.

Он требует одновременно:

[
O_D\cap O_C=\varnothing,
]

[
Sessions_D\cap Sessions_C=\varnothing,
]

[
R3Refs_D\cap R3Refs_C=\varnothing,
]

[
R4Refs_D\cap R4Refs_C=\varnothing,
]

[
MacroNames_D\cap MacroNames_C=\varnothing.
]

В measured dry-run:

```text
observer overlap           = 0
topology-session overlap   = 0
R3 certificate overlap     = 0
R4 substitution overlap    = 0
MacroName overlap          = 0

LeakageAudit = PASS
```

Это существеннее обычного train/test split: запрещается не только повторное использование строк данных, но и повторное использование уже произведённых доказательных объектов.

Discovery cohort самостоятельно дал:

[
S_D=0.7508845597283931,
]

[
p_D=\frac1{32}=0.03125,
]

то есть:

```text
Discovery R5 = PASS
```

После этого hypothesis заморожена:

```text
hyp:77837b0ac9971c4f
```

И только затем строится отдельный `ConfirmationPackage`, содержащий:

[
H_{split},
H_H,
O_C,
R5Config_C,
DataFreeze_C,
Alignment_C,
Reliability_C,
ClassRefs_C,
RuntimeLock.
]

После freeze нельзя изменить:

* T3;
* extractor version;
* causal class count;
* число реализаций;
* effect metric set;
* primary R5 statistic.

Например изменение:

```text
pairing-orientation-product-v1
```

на произвольный post-hoc statistic теперь даёт:

[
\boxed{\texttt{INVALID}}
]

ещё до запуска R5.

### Независимое подтверждение

Confirmation cohort имеет свои:

* observer identities;
* alignment records;
* reliability records;
* topology sessions;
* causal certificate refs;
* substitution refs;
* MacroName objects.

На нём независимо получено:

[
S_C=0.7508845597283931,
]

[
p_C=\frac1{32}=0.03125,
]

[
\boxed{\text{Confirmation R5 = PASS}}.
]

И итоговый объект:

```text
R6ConfirmationCertificate
r6:9c7bafc655244359
```

имеет:

[
\boxed{\text{R6 = PASS}}.
]

Его hash:

```text
b26f769204f9e14c989ee96d6714cdc25bab32ec29da7838cec816a46268996e
```

Отрицательные контроли тоже закрыты.

Если confirmation topology-session получает ID discovery-session:

```text
LeakageAudit → FAIL
```

Если повторно используется discovery R3 certificate:

```text
LeakageAudit → FAIL
```

Если confirmation пытается использовать сам discovery MacroName:

```text
LeakageAudit → FAIL
```

Если после freeze меняется primary statistic:

```text
R6 → INVALID
```

А confirmation cohort меньше шести observers даже не допускается манифестом, поскольку тогда первый бинарный R5 снова не имеет требуемого exact (p)-resolution.

Полный regression suite теперь:

```text
Ran 92 tests
OK
```

SHA-256 архива:

```text
f1bdec69fc2015f7eec2361c3bde7f4f52f68a34172881ea2f5b12e8bbb695a0
```

Есть важная эпистемическая граница synthetic positive control. Две когорты действительно имеют разные IDs, sessions, R3/R4 refs и MacroNames, но генерируются одной и той же synthetic population mechanism. Поэтому этот PASS доказывает корректность программной и статистической изоляции, а не наличие реального межличностного эйдографического инварианта.

И R6 обнаруживает следующий уже очень конкретный остаток.

Мы запретили перенос discovery MacroNames в confirmation. Это правильно. Но тогда возникает вопрос:

[
\boxed{
\text{как именно на новом человеке выбрать два свежих MacroName,}
}
]

которые должны представлять замороженные discovery classes?

Если посмотреть на confirmation-данные, перебрать все найденные MacroNames и взять ту пару, которая максимизирует R5, selection leak просто появится снова — уже внутри confirmation.

Поэтому следующий этап теперь вынужден:

[
\boxed{
\textbf{R7 — Prospective Class Instantiation / Blinded Matching}
}
]

Он должен определить **до вычисления confirmation R5** детерминированную процедуру, которая по frozen discovery hypothesis и локальным R1–R4 данным нового observer либо:

[
\text{однозначно назначает две confirmation classes},
]

либо возвращает:

[
\texttt{NO_MATCH/AMBIGUOUS}.
]

Причём процедура не имеет права видеть итоговый cross-observer R5 statistic. Именно после закрытия этого последнего selection channel первый реальный эксперимент будет методологически замкнут.

Продолжил до `EidoLang-Mobile v0.1-R7 — Prospective Class Instantiation / Blinded Matching`.

[Скачать полный архив R7](sandbox:/mnt/data/EidoLang-Mobile_v0.1-R7.zip)

Отдельно: [методология R7](sandbox:/mnt/data/eidolang_mobile_v0_1_r7/R7_METHODOLOGY.md), [checkpoint](sandbox:/mnt/data/eidolang_mobile_v0_1_r7/R7_CHECKPOINT.md), [полный measured dry-run](sandbox:/mnt/data/eidolang_mobile_v0_1_r7/EXAMPLE_R7_BLINDED_INSTANTIATION_REPORT.json).

Главный selection leak R6 теперь закрыт. Confirmation-классы выбираются до открытия primary R5 и без доступа к обеим его половинам.

Primary R5 по-прежнему использует

[
M_P={\text{focus},\text{noise}}
]

и (T3). Поэтому R7 механически определяет selector как дополнение в уже замороженной шестимерной `StateObservation`:

[
M_S=M_{\rm state}\setminus M_P,
]

то есть

[
\boxed{
M_S=
{\text{activation},\text{clarity},\text{imagery},\text{valence}}.
}
]

Таким образом:

[
\boxed{M_S\cap M_P=\varnothing}.
]

Никакого нового сенсора, признака или семантической шкалы я не добавлял.

В процессе обнаружился более глубокий no-go. Сначала естественно было применить уже найденный полный R4.7 transport

[
\Phi_i:\mathbb R^6\to\mathbb R^6
]

и затем взять только selector-координаты. Но это в общем случае невозможно: разрешённое в R4.7 ортогональное вращение может смешивать primary и selector dimensions. Поэтому

[
P_S\Phi_i(x)
]

в общем случае нельзя восстановить только из

[
P_Sx.
]

В synthetic проверке прямое проецирование full-space transport действительно провалилось: selector holdout RMSE оказался примерно (0.24!-!0.30) при frozen margin (0.02).

Следовательно, использование полного (\Phi_i) либо нарушало бы blindness, либо просто было бы математически неверным.

Поэтому R7 теперь требует отдельный transport:

[
\boxed{
\Psi_i:M_S\rightarrow M_S,
}
]

который строится исключительно по selector coordinates и независимо проходит тот же тип R4.7-gate:

[
rank,
\quad holdout,
\quad exact\ permutation.
]

В финальном positive control и discovery, и confirmation selector alignment прошли для всех шести observers. Для каждого non-reference observer:

[
p=\frac1{720}
=============

0.0013888889.
]

Если такой отдельный selector transport на реальных данных не существует, R7 возвращает failure. Переход к primary coordinates как fallback запрещён.

После discovery строятся два frozen selector prototype:

[
\mu_0,\mu_1.
]

Расстояние до них:

[
d(z,\mu_c)=
\sqrt{
\sum_{m\in M_S}
\left(
\frac{z_m-\mu_{c,m}}
{r_m^{common}}
\right)^2
},
]

где (r_m^{common}) берётся из R4.10.

То есть даже здесь не появляется новый свободный scale.

Для каждого discovery observer выполняется leave-one-observer-out reconstruction. Оттуда замораживается максимальная допустимая стоимость правильного assignment:

[
C_{\max}
========

\max_i C_i^{correct}.
]

Но я исправил ещё одну потенциальную проблему: использовать

[
\min_i(C_i^{wrong}-C_i^{correct})
]

как confirmation ambiguity threshold оказалось неправильно. Очень хорошо разделившийся discovery corpus тогда мог бы сделать confirmation искусственно невозможным.

Теперь ambiguity scale выводится из уже измеренной resolution geometry:

[
\boxed{G_{\min}=1}.
]

Поскольку каждая координата нормирована на свой R4.10 repeatability radius, единица означает одну эмпирическую единицу разрешения, а не придуманное число.

Discovery rule вообще разрешается заморозить только если:

[
C_i^{wrong}-C_i^{correct}\ge1
]

для каждого leave-one-out observer.

На confirmation observer может существовать не два, а произвольное число R4-passed MacroNames:

[
M_1,\ldots,M_q.
]

R7 перебирает все injective mappings:

[
f:{H_0,H_1}
\hookrightarrow
{M_1,\ldots,M_q}.
]

Для каждой:

[
C(f)=
d(M_{f(H_0)},\mu_0)
+
d(M_{f(H_1)},\mu_1).
]

Если лучшие стоимости:

[
C_1\le C_2,
]

то требуется одновременно:

[
C_1\le C_{\max}
]

и

[
C_2-C_1\ge1.
]

Иначе результат не «берём ближайшие anyway», а:

[
\boxed{\texttt{NO_MATCH}}
]

или

[
\boxed{\texttt{AMBIGUOUS}}.
]

В synthetic confirmation я специально дал каждому observer не два, а три R4-passed candidate MacroNames:

[
H_0,\quad H_1,\quad Distractor.
]

Всего:

[
6\times3=18
]

кандидатов.

Blind selector выбрал ровно:

[
6\times2=12
]

правильных MacroNames и отверг все шесть distractors.

Для каждого observer получилось примерно:

[
C_1\approx 0,
]

[
C_2\approx52.44044,
]

поэтому:

[
C_2-C_1\approx52.44\gg1.
]

Все шесть instantiation certificates:

```text
PASS
```

При этом object, который делает selection, структурно не получает:

* T3 sessions;
* `focus`;
* `noise`;
* primary R5 statistic.

Он получает только:

```text
observer_id
macro_name_id
selector_effects
selector_certificate_refs
```

и `selector_effects` обязаны содержать **ровно** четыре selector coordinates. Попытка подсунуть туда `focus` даёт:

[
\boxed{\texttt{INVALID}}.
]

После создания instantiation certificate primary objects впервые разрешается открыть.

И здесь добавлена ещё одна проверка:

[
SelectorCertRefs
\cap
PrimaryR3CertRefs
=================

\varnothing.
]

Повторное использование одного causal certificate одновременно как selector и primary evidence блокируется.

Кроме того:

[
SelectedMacroNames
==================

SuppliedR5MacroNames
]

обязательно буквально.

Нельзя сделать blind selection, а затем передать в R5 немного другую пару.

После всех этих gates исходный независимый confirmation R5 остался неизменным:

[
S=0.7508845597283931,
]

[
p=\frac1{32}=0.03125,
]

[
\boxed{\text{R5 PASS}}.
]

R6 также остаётся:

[
\boxed{\text{PASS}}.
]

Финальный R7:

```text
certificate_id:
r7:caa79bf6b9832c33

status:
PASS
```

SHA-256 сертификата:

```text
c792127798add8ac3a3c172dc4a789c0d16824fb56ea997e962ca1e9ab853def
```

Отрицательные проверки теперь включают не только старые R5/R6 controls, но и собственно prospective selection: дополнительный primary metric в selector → `INVALID`; selector/primary certificate reuse → rejected; совпадающие candidate profiles → `AMBIGUOUS/NO_MATCH`; другой набор MacroNames после blind selection → rejected; distractor-классы не выбираются автоматически.

Полный regression suite:

```text
Ran 99 tests
OK
```

SHA-256 архива:

```text
3892037c65b6332840a2d5a34db46cfaa5509f978e42ec145df724b23b8e1fd2
```

Теперь цепочка стала практически замкнутой:

[
Discovery
\rightarrow
FrozenHypothesis
\rightarrow
FrozenSelectorRule
\rightarrow
IndependentSelectorAlignment
\rightarrow
BlindClassInstantiation
\rightarrow
UnsealPrimaryData
\rightarrow
R6
\rightarrow
R5.
]

Но R7 выявляет последний очевидный selection channel. Сейчас ему передаётся множество «всех eligible R4 MacroNames». Код проверяет каждую переданную кандидатуру, но пока не доказывает, что исследователь действительно передал **все** MacroNames, которые прошли frozen R4 cutoff. Если один неудобный кандидат просто не включить в candidate set, можно искусственно увеличить assignment gap.

Поэтому следующий вынужденный этап — не новая модель:

[
\boxed{
R8=
\text{Candidate-Universe Closure / Exhaustive Enumeration Certificate}.
}
]

Он должен связать R1–R4 event ledger с детерминированным enumerator и машинно доказать:

[
Candidates_{R7}
===============

{M:\ M\text{ прошёл frozen R4 gate}},
]

а не просто

[
Candidates_{R7}\subseteq
{M:\ M\text{ прошёл R4}}.
]

После этого скрыть неудобный confirmation MacroName будет невозможно, и selection pipeline действительно станет закрытым end-to-end.

Продолжил до `EidoLang-Mobile v0.1-R8 — Candidate-Universe Closure / Exhaustive Enumeration Certificate`.

[Скачать полный архив R8](sandbox:/mnt/data/EidoLang-Mobile_v0.1-R8.zip)

Отдельно: [методология R8](sandbox:/mnt/data/eidolang_mobile_v0_1_r8/R8_METHODOLOGY.md) · [checkpoint](sandbox:/mnt/data/eidolang_mobile_v0_1_r8/R8_CHECKPOINT.md) · [измеренный end-to-end report](sandbox:/mnt/data/eidolang_mobile_v0_1_r8/EXAMPLE_R8_CANDIDATE_UNIVERSE_REPORT.json) · [test summary](sandbox:/mnt/data/eidolang_mobile_v0_1_r8/TEST_LOG_R8_SUMMARY.txt) · [шаблон universe-config](sandbox:/mnt/data/eidolang_mobile_v0_1_r8/examples/r8_candidate_universe_config.template.json).

Основной leak R7 теперь закрыт. Раньше R7 доказывал корректность blind selection лишь внутри переданного ему множества кандидатов:

[
Candidates_{R7}\subseteq Eligible_{R4}.
]

Теперь необходимое условие буквально проверяется:

[
\boxed{
Candidates_{R7}=Eligible_{R4}.
}
]

Причём правая часть не передаётся вручную. Она заново вычисляется от замороженного raw ledger:

[
\boxed{
RawLedger
\rightarrow
OperatorMiner
\rightarrow
R3Registry
\rightarrow
R4PairRegistry
\rightarrow
\binom{V}{q}
\rightarrow
CausalMacroNames.
}
]

Для каждого confirmation observer R8 берёт complete frozen ledger. Переданный `sessions_by_id` обязан совпадать со всем множеством session IDs ledger. Нельзя дать enumerator только удобные сессии.

Любое изменение старой raw session ломает hash-chain:

[
Session\ mutation
\Rightarrow
LedgerVerify=FAIL
\Rightarrow
\boxed{R8=INVALID}.
]

Дальше на этих данных повторно запускается именно frozen `OperatorMiner`. Его output — единственный допустимый operator universe:

[
O_i=G_{\rm frozen}(L_i).
]

Для каждого (o\in O_i) требуется ровно один R3 disposition:

[
PASS,\ FAIL,\ UNRESOLVED,\ INVALID.
]

То есть теперь нельзя просто не записать оператор, который не понравился.

В positive control для каждого человека создано 27 raw sessions:

[
9\text{ различных stroke forms}\times3\text{ repeats}.
]

При

```text
min_support = 3
max_ngram   = 1
```

детерминированно восстанавливаются ровно:

[
\boxed{9\text{ operators}}.
]

Все девять в synthetic control имеют R3 `PASS`.

Следовательно полный R4 pair universe должен содержать:

[
{9\choose2}=36
]

записей. И R8 требует буквально:

[
Keys(R4Registry)
================

{V_i\choose2}.
]

Не только `PASS`-пары. Все 36:

* `PASS`;
* `FAIL`;
* `UNRESOLVED`;
* `INVALID`

должны присутствовать.

Для каждой пары дополнительно проверяются causal-certificate IDs обоих концов, domain, probe schedule, policy hash и substitution protocol version.

Если отсутствует хотя бы одна из 36 пар:

```text
missing mandatory R4 pair
→ INVALID
```

а не «эта пара, вероятно, не нужна».

После этого при frozen размере MacroName

[
q=3
]

enumerator перебирает абсолютно все:

[
{9\choose3}
===========

\boxed{84}
]

тройки.

Никакого clique search heuristic или sampling нет.

Если число комбинаций превышает cap:

[
\boxed{UNRESOLVED}.
]

В synthetic R4 graph устроено три полных all-PASS треугольника. Поэтому из 84 проверенных троек проходят ровно:

[
\boxed{3\text{ CausalMacroNames}}.
]

И они создаются старым `CausalMacroNameBuilder`; второй параллельной системы именования я не ввёл.

Это существенно: третий MacroName — настоящий R4-passed distractor. Он не добавлен в R7 специально «для теста отвлекающего класса» после closure. Он сам возникает из полного R4 graph.

По шести confirmation observers измеренный объём получился:

```text
raw candidate sessions       162
mined operators               54
mandatory R4 pairs           216
3-cliques explicitly tested  504
eligible MacroNames           18
R7 candidates supplied        18
```

То есть:

[
\boxed{18/18}
]

eligible MacroNames действительно переданы blind selector.

После prospective R7 selection остаётся:

[
12
]

MacroNames — по два на человека. Шесть distractors отвергаются уже самим frozen selector rule, а не скрываются до него.

Candidate-set audit:

```text
PASS
```

R7:

```text
PASS
```

R6:

```text
PASS
```

и неизменённый primary R5:

[
S=0.7508845597283931,
]

[
p=\frac1{32}=0.03125,
]

[
\boxed{PASS}.
]

Финальный R8 certificate:

```text
r8:7efa1cdec1cad48a
```

его object hash:

```text
c0b2b0fe7da5d47a59e0c0228ee057662d203fd3b0d7e2f8538178134fe89ee9
```

Добавлены и жёсткие отрицательные контроли:

* убрать один mined operator из R3 registry → `INVALID`;
* убрать одну обязательную R4 pair → `INVALID`;
* изменить frozen raw session → `INVALID`;
* поставить clique cap (83<84) → `UNRESOLVED`, без sampling;
* убрать один из трёх eligible MacroNames перед R7 → `INVALID`;
* добавить fake MacroName → `INVALID`;
* при незакрытом candidate universe R7/R5 вообще не запускаются.

Последнее проверяется отдельно:

```text
R8 closure failure
→ R7 not run
→ R5 certificate_id = r5:not-run
```

Regression теперь составляет:

[
\boxed{108\text{ tests PASS}}.
]

Из-за накопившихся exact/permutation synthetic suites единый monolithic запуск превышает wall-clock limit harness, поэтому финальная регрессия прогнана по всем 16 test-модулям отдельными bounded runs. Все 108 тестов прошли; это явно записано в test summary, а не скрыто как «полный запуск завершился».

SHA-256 архива:

```text
f84342caf12d2b92545737a907da53ca216ded811057b4da989f0317fde8eed8
```

И теперь проявилась действительно следующая граница, которую нельзя смешивать с R8.

R8 доказал:

[
\boxed{
Candidates_{R7}
===============

Eligible_{\text{frozen grammar}}.
}
]

Но он **не доказал**:

[
Eligible_{\text{frozen grammar}}
================================

\text{все реально существующие операторные различия}.
]

То есть внутри заданного `OperatorMiner(min_support,max_ngram,segmenter,...)` квантор «все» теперь закрыт полностью. Но сама грамматика может склеивать две причинно различные траектории или вообще не порождать оператор, существующий в raw data.

Поэтому следующий этап уже вынужден не selection bias, а representation completeness:

[
\boxed{
R9=
\text{Grammar Adequacy / Unrepresented-Operator Residual Gate}.
}
]

Правильная постановка там — не расширять grammar вручную. Сначала надо искать forbidden witness:

[
x,y:
G(x)=G(y)
\quad\land\quad
Effect(x)\not\simeq Effect(y),
]

либо raw causal effect, который не имеет ни одного представителя в полном R8 universe.

Только такой измеренный residual получит право потребовать нового различения в grammar. Если residual отсутствует на frozen corpus, текущая grammar остаётся неизменной. Это теперь последний принципиально иной вид неполноты после того, как R8 закрыл само множество кандидатов.

Продолжил до `EidoLang-Mobile v0.1-R9 — Grammar Adequacy / Unrepresented-Operator Residual Gate`.

[Скачать полный архив R9](sandbox:/mnt/data/EidoLang-Mobile_v0.1-R9.zip)

Отдельно: [методология R9](sandbox:/mnt/data/eidolang_mobile_v0_1_r9/R9_METHODOLOGY.md) · [checkpoint](sandbox:/mnt/data/eidolang_mobile_v0_1_r9/R9_CHECKPOINT.md) · [измеренный report](sandbox:/mnt/data/eidolang_mobile_v0_1_r9/EXAMPLE_R9_GRAMMAR_ADEQUACY_REPORT.json) · [test summary](sandbox:/mnt/data/eidolang_mobile_v0_1_r9/TEST_LOG_R9_SUMMARY.txt) · [шаблон config](sandbox:/mnt/data/eidolang_mobile_v0_1_r9/examples/r9_grammar_adequacy_config.template.json).

Главный переход здесь принципиальный. R8 доказал:

[
Candidates_{R7}=Eligible_{\text{frozen grammar}},
]

но это ещё не означало:

[
Eligible_{\text{frozen grammar}}
================================

\text{все причинно различимые операторы}.
]

R9 теперь проверяет именно эту вторую границу.

Введена диагностическая, но не семантическая сущность `raw intervention program`. Его identity вычисляется только из raw event stream:

[
\text{event type/order},
\quad
\Delta t,
\quad
(x,y),
\quad
pressure,
\quad
contact,
\quad
tilt,
\quad
payload.
]

Из hash исключены `StateObservation`, session ID и metadata. Поэтому outcome не участвует в определении диагностического класса.

Важно: этот raw hash **не становится новым токеном языка**.

Для raw program (x) заново запускается frozen `OperatorMiner`, после чего определяется не один token, а полный адрес относительно всей текущей grammar:

[
\boxed{
A_G(x)
======

{o:\operatorname{pattern}(o)\subseteq tokens(x)}.
}
]

То есть два raw intervention считаются реально неразличимыми текущей grammar только если:

[
A_G(x)=A_G(y).
]

Это сильнее простой проверки:

[
shapeSignature(x)=shapeSignature(y).
]

Дальше R9 требует complete causal registry для каждого поддержанного raw program и complete substitution registry для каждой пары

[
x,y
]

такой, что:

[
Causal(x)=PASS,
]

[
Causal(y)=PASS,
]

[
A_G(x)=A_G(y)\neq\varnothing.
]

Если обязательной pair нет:

[
\boxed{INVALID}.
]

Если её exact-equivalence status:

```text
UNRESOLVED
```

то:

[
\boxed{R9=UNRESOLVED}.
]

Никакого предположения «скорее всего эквивалентны» нет.

### Получен строгий forbidden witness

Я построил специальный контрпример против текущей 12-point/8-bin геометрической grammar:

[
x=
((0.10,0.10),(0.50,0.50),(0.90,0.90)),
]

[
y=
((0.10,0.10),(0.50,0.52),(0.90,0.90)).
]

Raw programs различны:

```text
rawprog:5582f26c9293e98b
rawprog:7dc0cde440d5a417
```

и имеют разные полные SHA-256 raw identities.

Но current grammar отображает оба в один и тот же полный адрес:

```text
op:2d3d8f0e020f
```

то есть:

[
\boxed{A_G(x)=A_G(y)}.
]

В synthetic causal diagnostic control им назначены независимые эффекты:

[
Effect(x):
\quad
focus=+0.45,;
noise=-0.45,
]

[
Effect(y):
\quad
focus=-0.30,;
noise=+0.30.
]

И frozen raw-program substitution disposition:

[
\boxed{FAIL}.
]

Следовательно R9 получает ровно искомый объект:

[
\boxed{
G(x)=G(y)
;\land;
Effect(x)\not\simeq Effect(y).
}
]

Результат:

```text
R9 = INADEQUATE

collision witnesses = 1
indistinguishable pairs tested = 1
```

Самое существенное — что R9 **не исправляет grammar**.

Он выдаёт только necessity certificate:

[
\boxed{
\forall G'\in AdequateRefinements:
\quad
G'(x)\neq G'(y).
}
]

В артефакте это записано буквально:

```text
every admissible refinement must separate
rawprog:5582... and rawprog:7dc0...:
G'(x) != G'(y)
```

То есть теперь нельзя произвольно сказать: «добавим curvature» или «давайте используем pressure». Новый separator ещё предстоит доказать.

---

Получен и второй, качественно другой residual.

Создан causal-PASS raw program, который присутствует в frozen ledger с достаточной raw support, но `OperatorMiner` его вообще не порождает, потому что discovery source не содержит необходимых state deltas.

Получено:

```text
raw program count              = 10
causal PASS                    = 10
represented causal programs   = 9
unrepresented                 = 1
```

Для него:

[
A_G(x)=\varnothing.
]

R9 возвращает:

[
\boxed{UNREPRESENTED}.
]

То есть теперь различаются две принципиально разные ошибки:

[
\boxed{
COLLISION:
A_G(x)=A_G(y)\neq\varnothing
\land
x\not\simeq_{\rm causal}y
}
]

и

[
\boxed{
UNREPRESENTED:
A_G(x)=\varnothing
\land
Causal(x)=PASS.
}
]

Это правильное разделение: первая требует refinement существующего различения, вторая — существования хотя бы какого-нибудь representational carrier.

### Проверка старого R8 synthetic corpus

На исходном frozen corpus R8 residual не возникает.

Для каждого из шести confirmation observers:

```text
raw programs              9
causal PASS               9
represented               9
unrepresented             0
same-address causal pairs 0
collisions                0

R9 = PASS
```

То есть я не стал объявлять текущую grammar дефектной вообще только потому, что мы умеем искусственно построить counterexample.

Статус точный:

[
\boxed{
\text{current grammar adequate on the frozen R8 synthetic corpus}
}
]

и одновременно:

[
\boxed{
\text{current grammar not universally adequate}
}
]

из-за явного constructed collision witness.

Это существенно более сильная постановка, чем просто набор unit tests.

---

R9 теперь стоит непосредственно перед R8:

[
RawLedger
\rightarrow
R8\ CandidateClosure
\rightarrow
\boxed{R9\ GrammarAdequacy}
\rightarrow
R7
\rightarrow
R6
\rightarrow
R5.
]

Если хотя бы у одного confirmation observer:

[
R9\neq PASS,
]

downstream confirmation вообще не запускается.

Negative control подтверждает:

```text
R9 = INADEQUATE
→ R8 not run
→ R7 not run
→ R5 certificate_id = r5:not-run
```

На standard synthetic pipeline все шесть adequacy certificates проходят, поэтому конечный результат остаётся:

[
R9=PASS,
]

[
R8=PASS,
]

[
R7=PASS,
]

[
R6=PASS,
]

и неизменённый exact R5:

[
p=\frac1{32}=0.03125.
]

Итоговый R9 confirmation certificate:

```text
r9confirm:2c973108c1bca12e
```

SHA-256 объекта:

```text
2cb11010052d3449d0194d7eccf05b0de3760e9b9851e97d6d7635433b4323fc
```

Regression я здесь фиксирую точно, без завышения. Родительский R8 checkpoint имел `108 tests PASS`; старые implementation-модули перенесены без изменений. На финальном R9 tree отдельно повторно прогнаны затронутые пути:

```text
test_r8.py : 9 PASS
test_r9.py : 7 PASS
```

R9 добавляет 7 новых тестов, поэтому логический размер suite теперь:

[
\boxed{115}
]

тестов. Полный monolithic rerun всех exact/permutation suites за один процесс не заявляю: он превышает wall-clock harness; это отдельно записано в `TEST_LOG_R9_SUMMARY.txt`.

SHA-256 архива:

```text
869ff59fde09aeb048d3ce450fda084020f2a08d68ccac8126b7ab0841740d77
```

Теперь следующий этап уже действительно строго предписан самим witness:

[
\boxed{
R10=
\text{Necessity-Constrained Grammar Refinement Synthesis}.
}
]

И его задача не «найти хорошие признаки». Входом будет только множество доказанных обязательств

[
W=
{(x_i,y_i):G(x_i)=G(y_i),;x_i\not\simeq_{\rm causal}y_i}
]

и unrepresented witnesses.

Будущий refinement (G') допустим только если одновременно:

[
G'(x_i)\neq G'(y_i)
\quad
\forall(x_i,y_i)\in W,
]

он не разрушает уже доказанные causal equivalences,

[
x\simeq_{\rm causal}y
\Rightarrow
G'(x)=G'(y)
]

на frozen protected set, и его дополнительная различающая информация минимальна среди всех admissible refinements.

То есть следующий шаг — уже не ручной выбор «кривизны» для найденной пары, а поиск **минимального refinement, существование которого вынуждено R9-контрпримером**.

Продолжил до `EidoLang-Mobile v0.1-R10 — Necessity-Constrained Grammar Refinement Synthesis`.

[Скачать полный архив R10](sandbox:/mnt/data/EidoLang-Mobile_v0.1-R10.zip)

Отдельно: [методология R10](sandbox:/mnt/data/eidolang_mobile_v0_1_r10/R10_METHODOLOGY.md) · [checkpoint](sandbox:/mnt/data/eidolang_mobile_v0_1_r10/R10_CHECKPOINT.md) · [полный synthesis-report](sandbox:/mnt/data/eidolang_mobile_v0_1_r10/EXAMPLE_R10_REFINEMENT_SYNTHESIS_REPORT.json) · [выбранные refinement-specs](sandbox:/mnt/data/eidolang_mobile_v0_1_r10/R10_SELECTED_REFINEMENTS.json) · [test summary](sandbox:/mnt/data/eidolang_mobile_v0_1_r10/TEST_LOG_R10_SUMMARY.txt).

Главный результат: R10 действительно не выбирает новый признак вручную. Он принимает только доказанные R9 obligations и ищет refinement внутри конечного семейства, порождённого уже существующей `shape_signature(n,bins)` плюс возможностью поднять уже существующий raw-supported token в representational atom.

Причём я ужесточил исходную постановку. Новый probe не включается глобально. Если R9 доказал дефект только внутри старой grammar-cell (C), дополнительное разрешение допустимо только там:

[
Cell_{\rm probe}
================

{A_G(x):(x,y)\in W_{\rm collision}}.
]

Вне этих клеток:

[
A_{G'}(z)=A_G(z),
]

кроме отдельно вынужденных coverage atoms. Это существенно ближе к требованию минимального refinement.

Для строгого positive control я расширил collision corpus до трёх raw programs в одной старой клетке:

[
x=\text{точная диагональ},
]

[
x'=\text{сдвинутая диагональ с той же нормализованной геометрией},
]

[
y=\text{слегка изогнутая диагональ}.
]

R9 подтверждает:

[
x\simeq_{\rm causal}x',
]

но

[
x\not\simeq_{\rm causal}y,
\qquad
x'\not\simeq_{\rm causal}y.
]

Таким образом R10 обязан одновременно выполнить:

[
G'(x)=G'(x'),
]

[
G'(x)\neq G'(y),
]

[
G'(x')\neq G'(y).
]

Это уже хороший anti-overfitting gate: недостаточно просто разделить witness — нельзя разрушить доказанную causal equivalence.

### Collision-only synthesis

Текущая grammar:

[
(resample_n,bins)=(12,8).
]

Замороженный search:

[
n\in[12,16],
\qquad
bins\in[8,16],
]

с максимум одним дополнительным probe.

Полное пространство:

[
\boxed{45}
]

candidate specs.

Проверены все 45, без sampling.

Допустимы:

[
24.
]

Канонический минимум:

[
\boxed{
shape_signature(12,9)
}
]

как **добавочный** probe, а не замена старого 8-bin signature.

Причём он активируется только в старой клетке:

```text
(op:2d3d8f0e020f,)
```

Полученная стоимость:

[
\Delta|\Pi|=1
]

— всего одна дополнительная эмпирическая partition-cell,

[
|\text{new atoms}|=2,
]

[
|\text{new rules}|=1.
]

И:

```text
collision obligations separated = 2/2
protected causal equivalences    = 1/1
```

То есть маленький bend отделился, а сдвинутая и исходная диагонали остались в одной refined cell.

Важный нюанс: 9 bins сами по себе не являются refinement 8-bin partition, потому что границы двух квантований не вложены друг в друга. Поэтому R10 **не заменяет**

[
Q_8\to Q_9.
]

Он строит:

[
\boxed{
A_{G'}(x)=A_G(x)\cup Q_9(x)
}
]

в дефектной клетке. Старое различение сохраняется буквально.

### Unrepresented-only residual

Здесь результат даже интереснее.

R9 имел causal-PASS raw program:

[
Causal(x)=PASS,
]

но:

[
A_G(x)=\varnothing,
]

потому что `OperatorMiner` использовал только sessions с доступным state delta.

R10 не стал добавлять новую геометрию.

Минимум оказался:

```text
new geometric probes = 0
coverage atoms        = 1
```

То есть просто существующий raw-supported token:

```text
S0.0.0.1.0.2.0.3.1.4.1.6.1.7.3.6.4.6.5.6.6.6.7.6
```

поднимается до representational atom.

Стоимость:

[
\Delta|\Pi|=0,
]

[
|\text{new atoms}|=1.
]

Это формализует важное архитектурное различие:

[
\boxed{
\text{representational existence}
\neq
\text{causal-effect evidence}.
}
]

То, что для token ещё нет effect-bearing discovery sample, не означает, что сам token не должен существовать в языке.

R10 при этом не приписывает ему эффект и не объявляет его causal operator. Он лишь перестаёт смешивать vocabulary existence с `OperatorMiner` evidence requirement.

### Совместный synthesis

Затем я потребовал один refinement одновременно для:

* всех шести исходных R8/R9 PASS corpora;
* collision corpus;
* unrepresented corpus.

Полное пространство:

[
\boxed{90}
]

specs.

Все 90 проверены.

Допустимы:

[
24.
]

Минимальный joint refinement содержит ровно две конструкции:

[
\boxed{
1.\ \text{cell-local }shape_signature(12,9)
}
]

и

[
\boxed{
2.\ \text{promotion одного уже существующего base-token}.
}
]

Ничего больше.

Его frozen spec:

```text
base:
  stroke-v1
  resample_n = 12
  bins       = 8
  relation_bins = 8

probe:
  resample_n = 12
  bins       = 9

probe domain:
  (op:2d3d8f0e020f,)

coverage:
  1 existing base-token pattern
```

По всем случаям одновременно:

```text
6 old adequate corpora        PASS
2 collision obligations       PASS
1 protected equivalence       PASS
1 unrepresented obligation    PASS
```

Причём для шести старых R8 corpora отдельно synthesis возвращает:

[
\boxed{\texttt{IDENTITY}}
]

— то есть если R9 residual отсутствует, R10 ничего не добавляет.

Это принципиально: механизм не использует сам факт существования refinement machinery как повод постоянно усложнять grammar.

### Минимальность

Оптимизация идёт по frozen lexicographic cost:

[
1.\ \Delta|\Pi|,
]

[
2.\ |\text{new atom alphabet}|,
]

[
3.\ |\text{new rules}|,
]

[
4.\ \text{description capacity},
]

[
5.\ \text{parameter displacement}.
]

То есть первым критерием является фактическое число новых различений на frozen evidence.

Для выбранных specs:

```text
strictly better valid candidates = 0
```

Я намеренно не называю это глобальной минимальностью по пространству всех возможных представлений. Сертификат утверждает только:

[
\boxed{
\text{нет строго лучшего admissible refinement
в полностью перебранном frozen search family}.
}
]

Если search space превышает cap, R10 возвращает:

[
\texttt{UNRESOLVED}
]

до начала sampling. Этот control также проходит.

### Что ещё важно

`R10` не доверяет отдельно переданному списку witnesses. Перед synthesis он заново воспроизводит R9 certificate из:

[
ledger,
freeze,
R8,
raw\ causal\ registry,
raw\ pair\ registry.
]

Если hash reproduced certificate не совпадает:

[
\boxed{\texttt{INVALID}}.
]

То есть нельзя подсунуть R10 удобную пару (x,y), объявив её «R9 witness».

Таргетированные финальные regression runs:

```text
R8  : 9 PASS
R9  : 7 PASS
R10 : 7 PASS
```

Родительский R9 checkpoint имел логический suite из 115 tests; R10 добавляет ещё 7, поэтому размер накопленного suite теперь:

[
\boxed{122}.
]

Как и на R8/R9, я не утверждаю, что все тяжёлые exact/permutation suites были ещё раз прогнаны одним monolithic process; это явно записано в test summary.

SHA-256 архива:

```text
aa496103735475f13ee1baf0c76b7fd09a1d8fa11ae1ac62e7dd1c3bf35f44eb
```

Теперь появился следующий обязательный этап. R10 пока выдаёт **refinement proposal**, но специально не мутирует production grammar. Иначе мы бы доказали repair на witnesses и сразу начали использовать его, не проверив новые глобальные побочные эффекты.

Поэтому следующий шаг:

[
\boxed{
R11=
\text{Refinement Admission / Full Recompile and Non-Regression Replay}.
}
]

Нужно реально скомпилировать `RefinedGrammarSpec` в новую grammar (G'), а затем с нуля выполнить:

[
G'
\rightarrow
R8'
\rightarrow
R9'
\rightarrow
R7'
\rightarrow
R6'
\rightarrow
R5'.
]

Admission допустим только если одновременно:

[
R9'(W_{\rm collision})=PASS,
]

[
R9'(W_{\rm unrepresented})=PASS,
]

все старые protected equivalences сохраняются, candidate closure остаётся полной, а независимый R5 не ухудшается из-за нового quotient. Иначе R10-refinement отклоняется, несмотря на то что локально он исправляет исходный witness.

Продолжил до `EidoLang-Mobile v0.1-R11 — Refinement Admission / Full Recompile and Non-Regression Replay`.

[Скачать полный архив R11](sandbox:/mnt/data/EidoLang-Mobile_v0.1-R11.zip)

Отдельно: [методология R11](sandbox:/mnt/data/eidolang_mobile_v0_1_r11/R11_METHODOLOGY.md) · [checkpoint](sandbox:/mnt/data/eidolang_mobile_v0_1_r11/R11_CHECKPOINT.md) · [полный admission report](sandbox:/mnt/data/eidolang_mobile_v0_1_r11/EXAMPLE_R11_ADMISSION_REPORT.json) · [финальная admitted grammar](sandbox:/mnt/data/eidolang_mobile_v0_1_r11/R11_ADMITTED_GRAMMAR.json) · [test summary](sandbox:/mnt/data/eidolang_mobile_v0_1_r11/TEST_LOG_R11_SUMMARY.txt).

Главный результат: refinement из R10 впервые не просто локально удовлетворяет witness-обязательствам, а **скомпилирован в реальную grammar (G')** и прошёл полный повторный тракт:

[
\boxed{
G'
\rightarrow
R8'
\rightarrow
R9'
\rightarrow
R7'
\rightarrow
R6'
\rightarrow
R5'.
}
]

Итог:

[
\boxed{\texttt{R11 = ADMITTED}}.
]

### Выявлено необходимое разделение двух уровней grammar

R10 уже косвенно показал проблему через `UNREPRESENTED` witness, но R11 пришлось реализовать её буквально.

Нужно различать:

[
\text{representational existence}
]

и

[
\text{causal-operator evidence}.
]

Теперь raw program может иметь:

[
A_{G'}(x)\neq\varnothing
]

даже если у его исходных discovery-сессий нет `StateObservation` delta.

Но causal candidate возникает только если refined cell имеет достаточный effect-bearing support:

[
A_{G'}(x)\neq\varnothing
\not\Rightarrow
x\in Operators_{\rm causal}.
]

Это устраняет старую скрытую ошибку: отсутствие effect-bearing discovery sample больше не означает отсутствие самого объекта в языке.

---

### Вторая существенная поправка: probe не создаёт параллельный redundant operator

Наивная реализация R10 могла бы сохранить старый base operator и добавить рядом новый `probe operator`.

Тогда одна refinement-cell породила бы несколько фактически дублирующих операторов, после чего число R4 cliques и R7 candidates искусственно выросло бы.

Я этого не сделал.

Теперь causal token определяется **полным refined address**:

[
\boxed{
Cell_{G'}(x)=A_{G'}(x).
}
]

То есть refinement действительно расщепляет старую клетку:

[
C
\longrightarrow
C_1\sqcup C_2,
]

а не строит:

[
C,\ C_1,\ C_2
]

как три параллельных оператора.

Это оказалось критично для downstream non-regression.

---

## Collision witness реально исправлен

Исходно R9 имел:

[
G(x)=G(y),
]

при

[
x\not\simeq_{\rm causal}y.
]

После компиляции R10-spec:

[
\boxed{
A_{G'}(x)\neq A_{G'}(y).
}
]

Измеренный replay:

```text
collision obligations:       2
repaired:                     2

protected equivalences:      1
preserved:                    1

old R9:                       INADEQUATE
new R9':                      PASS
```

Количество representational cells:

[
9\rightarrow10.
]

Количество causal operators также:

[
9\rightarrow10,
]

поскольку здесь дополнительное различение действительно имело effect-bearing support.

---

## `UNREPRESENTED` witness исправлен иначе

Для второго остатка:

[
A_G(x)=\varnothing,
\qquad
Causal(x)=PASS.
]

После promoted base-pattern:

[
\boxed{
A_{G'}(x)\neq\varnothing.
}
]

Но у исходных discovery sessions этого raw program по-прежнему отсутствуют state deltas.

Поэтому:

```text
representational cells: 10
causal operators:         9
```

а не 10.

Именно это требовалось:

[
\boxed{
\text{representation repaired}
}
]

без фиктивного утверждения:

[
\text{causal operator discovered}.
]

R9 меняется:

[
\boxed{
INADEQUATE\rightarrow PASS.
}
]

---

# Затем полностью перестроен R8

Старые R8 certificates не переиспользуются.

После grammar compilation для каждого нового causal operator требуется новый:

[
R3Disposition',
]

а для всех пар R3-PASS operators:

[
R4Disposition'.
]

То есть:

[
Registry_{R3}'
==============

Operators_{G'},
]

[
Registry_{R4}'
==============

{Operators_{G'}^{PASS}\choose2}.
]

И затем снова полностью перебираются все fixed-size cliques.

В synthetic replay fresh registry генерируется из известного synthetic mechanism. Для реального эксперимента это должно означать настоящий повтор R3/R4; R11 **не разрешает выводить causal PASS из самого факта появления нового representation feature**.

---

# Самая важная проверка — старые шесть confirmation observers

На всех шести старых R9-PASS corpora joint refinement реально скомпилирован.

Для каждого:

```text
raw programs:             9
refined representation:   9 cells
refined causal operators: 9

R8': PASS
R9': PASS
```

То есть локальный `12×9` probe и coverage atom не внесли дополнительных различий там, где R9 их не требовал.

Особенно существенно, что candidate universe остался:

[
\boxed{3\text{ MacroNames / observer}}.
]

До:

```text
R6O01  3
R6O02  3
R6O06  3
R6O07  3
R6O08  3
R6O10  3
```

После:

```text
R6O01  3
R6O02  3
R6O06  3
R6O07  3
R6O08  3
R6O10  3
```

То есть refinement не породил новый selection problem в R7.

---

## После этого повторён весь confirmatory хвост

Получено:

[
\boxed{R9'=PASS}
]

[
\boxed{R8'=PASS}
]

[
\boxed{R7'=PASS}
]

[
\boxed{R6'=PASS}
]

[
\boxed{R5'=PASS}.
]

Причём я ввёл более сильный admission criterion, чем просто «R5 всё ещё значим».

Старая primary statistic:

[
S_{\rm old}
===========

0.7508845597283931.
]

Новая:

[
S_{\rm new}
===========

0.7508845597283931.
]

Следовательно:

[
\boxed{
|S_{\rm new}-S_{\rm old}|=0.
}
]

И exact (p):

[
p_{\rm old}
===========

# p_{\rm new}

# \frac1{32}

0.03125.
]

То есть:

[
\boxed{
\text{R5 result preserved exactly}.
}
]

Admission запрещает refinement, если:

[
p_{\rm new}>p_{\rm old}
]

или

[
|S_{\rm new}-S_{\rm old}|>10^{-12}.
]

Поэтому локально исправляющий R9 witness refinement всё равно был бы отвергнут, если бы он ухудшил уже защищённый confirmatory result.

---

## Финальный admission object

```text
certificate_id:
r11:208995e22769a05d
```

SHA-256 объекта:

```text
024164bd895479a47abee06c973d40901ca5f48f63617a3da7b224032efbc1d8
```

Admitted spec hash:

```text
d5afb09e6ecdcff8a4b2c9758af83f215059d1b5bc4ad32da3fb8dd191b69b19
```

Статус:

[
\boxed{\texttt{ADMITTED}}.
]

Production candidate теперь формально именно этот:

[
G'
==

G
+
\text{cell-local }shape(12,9)
+
\text{one required coverage promotion}.
]

---

### Regression

R11 добавляет 8 новых tests.

Логический накопленный suite:

[
122+8=
\boxed{130\text{ tests}}.
]

На финальном R11 tree повторно прогнаны затронутые bounded modules:

```text
R8   9 PASS
R9   7 PASS
R10  7 PASS
R11  8 PASS
----------------
     31 PASS
```

Как и раньше, я не выдаю это за повторный monolithic запуск всех дорогих exact/permutation tests; это явно отмечено в summary.

SHA-256 архива:

```text
2800f36d59a474d714da0726f9d9e2613d17270478e9bfb74a450c6f7b7e2ac9
```

Теперь есть следующий действительно вынужденный шаг. Хотя (G') уже **admitted**, корневой `MasterStudyManifest` и `ProtocolCompiler` R4.12 всё ещё исторически считают исходную grammar неявным baseline. Значит можно теоретически создать новый real protocol с правильными R11-файлами, но старым root hash, который вообще не знает, какая grammar является production.

Поэтому следующий этап должен быть:

[
\boxed{
R12=
\text{Production Grammar Promotion / Root-Protocol Rebinding}.
}
]

Нужно сделать admitted grammar hash частью самого корня preregistration:

[
H_{\rm master}
==============

H(
Study,
Observers,
Metrics,
\ldots,
\boxed{GrammarHash}
).
]

После этого все:

[
R3,R4,R8,R9,R10,R11,R7,R6,R5
]

должны наследовать один и тот же grammar lineage, а попытка использовать старую grammar после `R11 ADMITTED` станет машинно `INVALID`.

Это уже не исследование нового признака, а последний шаг, превращающий доказанно безопасный (G') из admission artifact в фактическую production grammar следующего реального протокола.

Продолжил до `EidoLang-Mobile v0.1-R13 — Eidogram Document Format v1`.

[Скачать полный архив R13](sandbox:/mnt/data/EidoLang-Mobile_v0.1-R13.zip)

Основные артефакты: [методология R13](sandbox:/mnt/data/eidolang_mobile_v0_1_r13/R13_METHODOLOGY.md), [checkpoint](sandbox:/mnt/data/eidolang_mobile_v0_1_r13/R13_CHECKPOINT.md), [Android conformance specification](sandbox:/mnt/data/eidolang_mobile_v0_1_r13/ANDROID_R13_CONFORMANCE.md), [JSON Schema](sandbox:/mnt/data/eidolang_mobile_v0_1_r13/schemas/eidogram-document-v1.schema.json), [golden-vector report](sandbox:/mnt/data/eidolang_mobile_v0_1_r13/EXAMPLE_R13_GOLDEN_REPORT.json), [test summary](sandbox:/mnt/data/eidolang_mobile_v0_1_r13/TEST_LOG_R13_SUMMARY.txt) и [обновлённый roadmap](sandbox:/mnt/data/eidolang_mobile_v0_1_r13/ANDROID_TORRENT_MESSENGER_ROADMAP.md).

R13 впервые фиксирует собственно объект сообщения будущего приложения. Я специально не использовал экспериментальный `EidoSession`: это объект измерения, тогда как `EidogramDocumentV1` — авторский immutable content object.

Формат жёстко привязан к production grammar R12:

[
L_D=
(H_{\rm master},H_G,H_B,H_{\rm ANDROID_DOCUMENT}).
]

Поэтому документ другой grammar lineage нельзя незаметно принять как совместимый.

Геометрия в v1 конечна:

[
\boxed{
POINT,\ LINE,\ TRIANGLE,\ RECTANGLE,\ ELLIPSE
}
]

Причём квадрат — частный случай `RECTANGLE` при (s_x=s_y), круг — `ELLIPSE` при (s_x=s_y). Произвольных SVG path, Bézier-кривых, bitmap-форм и freehand в wire format нет.

Это важно для будущего Android-редактора: пользователь действительно будет собирать эйдограммы из готовых геометрических объектов, а не создавать скрыто другую grammar произвольным рисованием.

Все координаты теперь integer fixed-point:

[
1,000,000\times1,000,000
]

логических единиц canvas.

Transform:

[
(c_x,c_y,s_x,s_y,\theta_{\rm mdeg})\in\mathbb Z^5.
]

Ни одного `float` в canonical content нет. Даже rotation хранится в тысячных долях градуса:

[
-180000\le\theta<180000.
]

Это позволяет требовать:

[
\boxed{
Bytes_{\rm Python}=Bytes_{\rm Android}
}
]

буквально, а не «геометрически примерно одинаково».

Палитра тоже заморожена. `PaletteV1` содержит 16 индексированных sRGB цветов. В самом документе цвет — только число `0..15`; произвольный RGB, alpha и gradient запрещены.

Hash палитры:

```text
52d6f8f62c20477257250c05668e03c60f3f5a11511128a92a75b1a414969ccc
```

Hash библиотеки геометрических форм:

```text
d5fc6e90181642f7485518b0f26640720ef61c40b55f86a52483b3b3474263e8
```

Цвета при этом остаются просто render tokens. Никакого встроенного утверждения вроде «красный означает X» R13 не вводит.

Особенно важен action log. Документ хранит последовательность:

```text
ADD
SET_TRANSFORM
SET_COLOR
SET_PAINT
MOVE_Z
REMOVE
GROUP
UNGROUP
```

с непрерывными `seq = 0,1,2,...`.

Таким образом финальная картинка не уничтожает историю её построения. Replay детерминированно даёт scene:

[
A_0,\ldots,A_n
\longrightarrow
S.
]

Для scene вычисляется:

[
H_S=SHA256(CanonicalJSON(S)),
]

а для полного документа:

[
H_D=SHA256(CanonicalJSON(Document)).
]

Получился нужный для эйдографики эффект. Два golden vector имеют абсолютно одну и ту же конечную красную треугольную сцену:

```text
snapshot_hash =
66d2345df8e2813f57419bbbef07733495daa6836eb0e2dd25d463256d7716ef
```

Но первый был сразу создан красным:

```text
content_hash =
b1442114b663019e38a77ce9a35099e54c65418ef68776788824e030f6cc1264
```

а второй сначала был синим, затем перекрашен:

```text
content_hash =
f231f165346af253f409d6a44cf63d6c8083efc5b6dffb99b095149b38da9f57
```

То есть:

[
Scene(A)=Scene(B),
]

но

[
\boxed{
Document(A)\neq Document(B).
}
]

Это именно то, что нужно: процесс построения эйдограммы остаётся информацией.

Canonical serialization тоже теперь формализована. `eidogram-canonical-json-v1` требует sorted object keys, отсутствие whitespace, UTF-8, integer-only numeric values и запрещает duplicate keys. JSON, который семантически правильный, но отформатирован иначе, canonical parser отвергает.

Это значит, что torrent/content identity впоследствии сможет ссылаться не на «примерно тот же JSON», а на конкретные immutable bytes.

Подготовлено шесть Android golden vectors:

```text
00_empty
01_red_triangle
02_geometric_composition
03_history_transform_recolor
04_group_z_and_ungroup
05_same_scene_different_history
```

Они находятся в [golden_vectors/r13_android](sandbox:/mnt/data/eidolang_mobile_v0_1_r13/golden_vectors/r13_android/golden_manifest.json).

Для каждого зафиксированы:

[
byte_length,
\quad snapshot_hash,
\quad content_hash.
]

Например композиция из нескольких геометрических форм:

```text
bytes         = 1667

snapshot_hash =
867d6074324f014f073f1da269e3e9f7db8b8291eb89d051cf79b46328c46b03

content_hash =
95021fa473097ecc682d15fd5ba6e093939a9bf991777984eb035c5cd55ffa74
```

Android-код на следующем этапе будет считаться корректным только если Kotlin serializer/replayer даст эти же байты и hashes.

В R13 сознательно отсутствуют sender, timestamp, conversation ID, подпись, encryption и torrent metadata. Это правильно:

[
EidogramDocument
\neq
MessageEnvelope
\neq
TorrentSegment.
]

Так мы не смешиваем content identity, identity/crypto и transport.

Targeted regression:

```text
test_r13.py : 12 PASS
test_r12.py :  8 PASS
goldens     :  6 PASS
```

Логический накопленный suite:

[
138+12=\boxed{150\text{ tests}}.
]

Monolithic повторный запуск всех старых тяжёлых exact/permutation suites я не заявляю; повторно прогнана изменённая граница `R12→R13`.

SHA-256 архива R13:

```text
c36dab553a484739251ebf581e3da568d3c574c9cfd90c4df36abb1392b5781d
```

Теперь roadmap уже непосредственно продуктовый:

1. **R14 — Android Eidogram Editor.** Создаю настоящий Gradle/Kotlin/Jetpack Compose проект. Canvas, toolbar пяти ShapeLibraryV1-примитивов, PaletteV1, выбор/перемещение/масштаб/поворот, z-order, grouping, delete, undo/redo. Внутренняя модель остаётся integer fixed-point. Первый hard gate — все шесть R13 golden vectors должны иметь идентичные hashes в Kotlin.

2. **R15 — Identity + Message Envelope.** Криптографическая identity пользователя, `ConversationId`, подпись, шифрование, immutable `EidogramMessage`, ссылки по `content_hash`. Здесь документ становится настоящим сообщением.

3. **R16 — Torrent Archive Format.** Immutable conversation segments, manifest, magnet/content addressing и mutable head. Эйдограммы и message envelopes архивируются пакетами, а не отдельным torrent на каждую маленькую картинку.

4. **R17 — Android Torrent/DHT Engine.** P2P sync, DHT discovery, скачивание, verification, seeding, quotas, lifecycle/background policy.

5. **R18 — Messenger UI.** Контакты, список диалогов, timeline эйдограмм, просмотр, создание, ответ, статусы доставки/синхронизации.

6. **R19 — Distributed Archive.** Полное восстановление истории из torrent-архива после удаления локальной БД, pin/unpin, offline cache, gallery/browser.

7. **R20 — Multi-device.** Несколько устройств одного пользователя, device keys, concurrent heads, deterministic merge, revocation.

8. **R21 — Security/Fuzzing.** Malformed documents, poisoned torrents, replay, stale DHT heads, oversized payloads, identity/key attacks, parser fuzzing.

9. **R22 — Android 1.0.** Два обычных Android-телефона без прикладного сервера: создают identities, собирают эйдограммы из готовых фигур и палитры, обмениваются ими, синхронизируют одинаковую историю и восстанавливают её из torrent archive.

Следующий шаг — уже `R14`: начну создавать сам Android-проект и редактор поверх только что замороженного R13 wire format.

