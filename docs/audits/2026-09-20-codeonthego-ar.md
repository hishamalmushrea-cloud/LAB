# تقرير نقل وفحص Code on the Go

> تاريخ اللقطة والمراجعة: 2026-09-20
>
> نوع المراجعة: جرد ساكن شامل للشجرة، مع تعمق موجّه في المعمارية والبناء والاختبارات والأمن وCI/CD والتاريخ الحديث للمشروع.
>
> هذا التقرير يصف اللقطة المستوردة، وليس ضمانًا لخلو كل مسار تنفيذي من العيوب.

## 1. ملخص تنفيذي

تم نقل لقطة مطابقة لشجرة فرع `stage` من مشروع
[`appdevforall/CodeOnTheGo`](https://github.com/appdevforall/CodeOnTheGo)
إلى هذا المستودع، مع الإبقاء على `origin` الخاص بالمستخدم وإضافة المصدر باسم
`upstream`.

المشروع **بيئة تطوير أندرويد حقيقية تعمل على جهاز أندرويد نفسه**، وليس مجرد محرر
نصوص. نواته الحالية تشمل: إدارة المشاريع والقوالب، محررًا مدعومًا بـLSP للـJava
وKotlin وXML، Gradle Tooling API وخادم بناء في عملية JVM منفصلة، Terminal مبنيًا
على Termux، Git، تشغيل APK وتصحيح الأخطاء، سجلات وتشخيصات، profiler، توثيقًا محليًا،
ونظام إضافات وقوالب قابلًا للتوسعة.

أهم تصحيح لفهم المنتج الحالي:

- **الذكاء الاصطناعي، Jetpack Compose Preview، وLayout Editor المتقدم لم تعد أجزاء
  مدمجة في النواة**؛ نُقلت عمدًا إلى إضافات في
  [`appdevforall/plugin-examples`](https://github.com/appdevforall/plugin-examples).
- ما زالت في هذه الشجرة وثائق ونصوص وسكربتات وإعدادات قديمة توحي بعكس ذلك.
- البناء ليس self-contained: حمولات Android SDK وGradle وMaven cache وbootstrap
  والتوثيق الكبيرة تُجلب من بنية App Dev for All وقت البناء.
- قابلية نقل المصدر ممتازة، لكن **قابلية نقل خط البناء والتوزيع محدودة** قبل توفير
  runners وsecrets والأصول الخارجية وإعادة تهيئة خدمات المؤسسة.
- أخطر حد ثقة هو نظام الإضافات: الإضافات تُحمّل bytecode وnative code داخل عملية
  التطبيق. صلاحيات Plugin API تقيد الخدمات الرسمية، لكنها ليست sandbox للـbytecode
  نفسه؛ لذلك يجب التعامل مع تثبيت `.cgp` كتنفيذ كود موثوق، لا كإضافة معزولة.

## 2. إثبات النقل وحدوده

| البند | القيمة |
|---|---|
| مستودع المصدر | `https://github.com/appdevforall/CodeOnTheGo.git` |
| فرع المصدر | `stage` |
| رأس المصدر المنقول | `681a7dcf5daba015c15e3d2d029a922990ca976e` |
| Tree المصدر | `deaaefb48d9b8269cb636b26112ca505af6ac45a` |
| Tree التزام الاستيراد المحلي | `deaaefb48d9b8269cb636b26112ca505af6ac45a` |
| التزام الاستيراد في LAB | `1e11cf44eb40a97be3571c9c927976cb6e488618` |
| فرع العمل في LAB | `arena/01a0c07f-lab` |
| Remote المصدر | `upstream` |
| الترخيص | GPL-3.0-or-later |

التطابق أعلاه يعني أن **التزام الاستيراد نفسه** يحمل الشجرة ذاتها الموجودة في رأس
`upstream/stage`. هذا التقرير أضيف في التزام لاحق، ولذلك يصبح رأس LAB مختلفًا عن
المصدر بمقدار ملف التقرير فقط.

### ما نُقل وما لم يُنقل

- نُقلت جميع الملفات المتعقبة في لقطة رأس `stage` وعددها 7,212 ملفًا.
- لم يُستبدل `origin` الخاص بمستودع LAB.
- لم يُستورد التاريخ الكامل أو كل الفروع والوسوم؛ المنقول snapshot موثق داخل تاريخ
  LAB. يمكن الرجوع إلى التاريخ الخارجي عبر `upstream` وGitHub.
- `.gitmodules` فارغ، ولذلك لا توجد submodules فعالة كان يلزم تهيئتها في هذه اللقطة.

## 3. حجم الشجرة وتركيبها

نتائج الجرد الساكن:

- 7,212 ملفًا متعقبًا.
- 6,481 ملف كود/تهيئة ضمن عدّ اللغات، بنحو **1,310,159 سطرًا**.
- Java: نحو 965,983 سطرًا.
- Kotlin/KTS: نحو 290,545 سطرًا.
- جزء معتبر من Java ليس منطق منتج جديدًا، بل كود مضمّن أو مشتق من OpenJDK وTermux
  وLemMinX ومكونات XML/JAXP؛ لذلك لا يصح استخدام عدد الأسطر وحده كمقياس لحجم منطق
  المنتج.
- 86 مشروع Gradle معلنًا في `settings.gradle.kts`، و345 حافة اعتماد داخلية.
- `:app` يعتمد مباشرة على 49 مشروعًا داخليًا، ما يجعله integration hub كبيرًا.
- 122 ملف `build.gradle(.kts)` إجمالًا عند احتساب composite builds والمشروعات
  المستقلة والأمثلة غير المضمّنة في root build.
- 412 ملفًا في مسارات الاختبار، منها 373 تحت `src/test` و39 تحت `src/androidTest`.
- 12 ترجمة محلية إلى جانب اللغة الافتراضية. الترجمة العربية موجودة، لكنها تغطي
  قرابة 450 مدخلًا مقابل 1,449 في الملف الأساسي، أي إنها غير مكتملة بوضوح.

## 4. ما الذي يقدمه المنتج فعليًا؟

### 4.1 قدرات النواة الموجودة في هذه الشجرة

1. **إدارة المشاريع**
   - إنشاء مشاريع من قوالب `.cgt`.
   - فتح المشاريع الحديثة واستنساخ مستودعات Git.
   - استيراد/تصدير الملفات والتعامل مع Android `DocumentsProvider`.
   - روابط عميقة لفتح مشروع وملف وسطر وعمود مع تحقق واضح من المسارات.

2. **المحرر والتحليل اللغوي**
   - محرر Sora مع تبويبات، تلوين، إكمال، تشخيصات، outline، وانتقال داخل الكود.
   - Java LSP وخدمات `javac` مضمّنة.
   - Kotlin Analysis API/LSP.
   - XML LSP وأدوات resources/AAPT/XML DOM.
   - code actions وتنسيق واستيرادات ومراجع ورموز.

3. **البناء والتشغيل على الجهاز**
   - Gradle Tooling API مع `tooling-api` client و`tooling-api-impl` server.
   - تشغيل خادم البناء في JVM منفصلة، ومراقبة daemon والذاكرة/الحرارة.
   - quick build وتجميع APK وتثبيته وتشغيله.
   - Android SDK وGradle distribution وMaven cache محلية تُثبت داخل مساحة التطبيق.

4. **Terminal وبيئة Unix**
   - دمج Termux: terminal view/emulator/app/shared.
   - bootstrap خاص بكل ABI وأدوات command-line.

5. **التصحيح والمراقبة**
   - debugger عبر JDWP/JDI، breakpoints، stack frames، variables، والتنقل إلى المصدر.
   - سجلات التطبيق والـIDE وlog sender.
   - profiler وتقارير وheap dump، مع أجزاء تعتمد على Shizuku.
   - overlay عائم لخدمات التصحيح.

6. **Git**
   - JGit لعمليات clone/status/diff/commit/branch/pull/push.
   - حفظ username/token باستخدام AES-GCM ومفتاح داخل Android Keystore.

7. **واجهة وتصميم**
   - نواة XML inflater وUI designer ما زالت موجودة.
   - الواجهة خليط من Views/Fragments مع تبنّي Compose للشاشات الجديدة وفق ADR 0009.
   - Layout Editor الكامل وCompose Preview للمشروع الذي يحرره المستخدم أصبحا إضافتين
     خارجيتين، لا وحدتين فعالتين في هذه النواة.

8. **التوثيق والعمل دون اتصال**
   - قاعدة SQLite محلية للتوثيق، Brotli، templates، bookshelf وtooltips.
   - خادم HTTP محلي موروث على `localhost:6174`، إلى جانب interceptor داخل العملية.

9. **الإضافات والقوالب**
   - مدير موحد لإضافات `.cgp` وقوالب `.cgt`.
   - Plugin API للخدمات والواجهات والـsidebar والملفات والمحرر والبناء والأوامر
     والقوالب والتوثيق.
   - تحميل الموارد وDEX والمكتبات الأصلية، وتفعيل/تعطيل الإضافات وحارس crash loop.

10. **التدويل وإمكانية الاستخدام**
    - موارد لعدة لغات، دعم الوضع الليلي، اختصارات ولوحات مفاتيح، وtooltips.
    - العربية موجودة لكن اكتمالها أقل بكثير من النص الإنجليزي.

### 4.2 قدرات اختيارية موجودة خارج هذا المستودع

مستودع `plugin-examples` يحتوي حاليًا أمثلة/إضافات مثل:

- Jetpack Compose Preview وLayout Editor.
- AI Code Suggestions وGet AI Models، إضافة إلى backends/agents لـGemini وOpenAI
  والنماذج المحلية وMCP الموجودة في جذر مستودع الإضافات.
- APK Analyzer وMarkdown Previewer وNDK Installer.
- Code Together وSpeech to Text وVector Search وSketch to UI.
- Keystore Generator وFlutter/Python tools وقوالب وsnippets إضافية.

لذلك يجب عدم وصف كل هذه الإمكانات بأنها جزء من APK الأساسي. النواة توفر platform
والـAPI، بينما التحقق من تلك المزايا وبناؤها وإصداراتها يتم في مستودع منفصل.

## 5. المعمارية

### 5.1 الطبقات العامة

- **UI:** Activities/Fragments/Views وCompose حديث، مع ViewModels وStateFlow/coroutines.
- **مجال التطبيق:** إدارة المشروع والمحرر والبناء وactions/preferences/templates/plugins.
- **الخدمات اللغوية:** وحدات LSP مستقلة نسبيًا لـJava/Kotlin/XML.
- **طبقة المشروع:** models وclasspath وGradle sync وتحويل ناتج Tooling API.
- **البناء:** client داخل التطبيق، IPC إلى tooling server في JVM منفصلة، ثم Gradle
  daemon/tooling models.
- **البيئة:** Termux، JDK/Android SDK/Gradle/Maven cache داخل مساحة التطبيق.
- **التوسعة:** Plugin API + manager + DexClassLoader + registries وخدمات مسموحة.
- **التخزين:** Room للمشروعات الحديثة، SQLite للتوثيق، preferences وfilesystem.

### 5.2 مسار فتح وبناء مشروع

1. يختار المستخدم مشروعًا أو قالبًا/رابطًا عميقًا.
2. تُسجل بيانات recent project ويُفتح `EditorActivity`.
3. يبدأ Gradle sync عبر tooling client/server ويُبنى model للمشروع والوحدات.
4. تُهيأ language servers وindexing والـeditor actions.
5. عند البناء، تُرسل العملية إلى Gradle/JVM المنفصلة.
6. تُعرض diagnostics/logs/metrics، ثم يُثبت APK ويُشغّل عند النجاح.

هذا الفصل بين عملية UI وعملية Gradle قرار صحيح لتقليل انهيار التطبيق بسبب حمل
البناء، لكنه يزيد تعقيد IPC ودورة حياة daemon والتوافق بين نسخ tooling.

### 5.3 محاور الاعتماد

أكثر الوحدات مركزية هي `:common` و`:logger` و`:resources` و`:shared`. أما `:app`
فيجمع 49 اعتمادًا داخليًا مباشرًا. النتيجة:

- إعادة الاستخدام جيدة على مستوى الوحدات.
- لكن app module ما زال نقطة دمج ضخمة، ويزيد ذلك زمن configuration/compile واحتمال
  أن يؤدي تغيير عابر إلى إعادة بناء واسعة.
- composite builds تفصل build logic واعتمادات مشتقة من OpenJDK، لكنها تجعل فهم
  النسخ الفعلية أصعب من version catalog واحد.

## 6. البناء والنسخ

هناك مجموعتان من النسخ، ويجب عدم الخلط بينهما:

| المجال | النسخة في اللقطة |
|---|---|
| Java لبناء المشروع | 17 |
| Gradle wrapper لبناء CodeOnTheGo | 8.14.4 |
| Android Gradle Plugin لبناء التطبيق | 8.8.2 |
| Kotlin plugin لبناء التطبيق | 2.3.0 |
| compile SDK للتطبيق | 36 |
| min SDK للتطبيق | 28 |
| target SDK للتطبيق | 28 |
| NDK | 29.0.14206865 |
| Toolchain الموزع لمشاريع المستخدم: Gradle | 9.6.1 |
| Toolchain الموزع لمشاريع المستخدم: AGP | 9.3.1 |
| Toolchain الموزع لمشاريع المستخدم: Kotlin | 2.3.21 |

### Variants

- بعد ABI مستقلان: `v7` (`armeabi-v7a`) و`v8` (`arm64-v8a`).
- build types: debug وrelease، مع مهام instrumentation خاصة.
- الإصدار البسيط مشتق من timestamp التزام Git لزيادة reproducibility.
- version code ما زال مشتقًا من وقت البناء، ولذلك ليست كل metadata قابلة لإعادة
  الإنتاج بالكامل.

### أوامر البناء الرسمية

بعد تجهيز Flox أو Java 17 وAndroid SDK وملفات البيئة المطلوبة:

```bash
flox activate
./gradlew :app:assembleV8Debug
./gradlew :app:assembleV7Debug
./gradlew test
./gradlew spotlessCheck
```

إعدادات المشروع تطلب حتى 8GB heap و30 worker؛ البيئة الصغيرة قد تحتاج تقليل
`org.gradle.jvmargs` و`org.gradle.workers.max`.

## 7. الأصول الخارجية وقابلية إعادة البناء

الشجرة لا تحمل الحمولات الكبيرة الفعلية اللازمة للـAPK:

- Android SDK لكل ABI.
- Termux/bootstrap لكل ABI.
- Gradle distribution وGradle API jar.
- local Maven repository.
- قاعدة التوثيق.
- أصول القوالب والإضافات المولدة.

`app/build.gradle.kts` ينزل تسعة أصول debug عبر HTTPS من
`appdevforall.org/dev-assets`، بينما CI يجلب نسخ release عبر SCP. يوجد تحقق MD5،
لكن checksum يأتي من القناة/الخادم نفسه؛ هو جيد لاكتشاف التلف، وليس توقيع provenance
مستقلًا. يفضل مستقبلاً SHA-256 مع manifest موقّع أو release artifacts قابلة للتتبع.

توجد تسعة ملفات release مضغوطة صفرية الحجم متعقبة كـplaceholders. كما أن أسماء
بعضها تحمل نسخة Gradle أقدم من الثوابت الحالية. البناء الطبيعي ينشئ/يجلب الأسماء
الجديدة، لكن placeholders تزيد الالتباس ولا تجعل المصدر self-contained.

**الخلاصة:** استنساخ Git وحده لا يكفي لبناء release مستقل تمامًا عن بنية المؤسسة.
يجب أرشفة الأصول وإصداراتها وchecksums خارجية بطريقة قابلة لإعادة الإنتاج.

## 8. الاختبارات والجودة

### الموجود في الشجرة

- JVM unit tests، Robolectric، JUnit 4 وJupiter.
- Android instrumentation وKaspresso واختبارات end-to-end لبناء مشاريع Groovy/KTS.
- اختبارات صريحة للمسارات العميقة، zip-slip/symlinks، strict mode، plugins، tooling
  وGradle daemon، XML/LSP، templates، documentation، debugger وغيرها.
- Spotless/ktlint وقواعد Compose.
- JaCoCo وSonarQube/SonarCloud وRenovate مع OSV vulnerability alerts.

### الدليل الخارجي على رأس المصدر المنقول

الرأس `681a7dcf...` مرّ في المصدر بما يلي:

- Build and deploy to Firebase: **نجاح** في 2026-09-18.
- تحليل JaCoCo + SonarQube/SonarCloud: **نجاح** في 2026-09-20.
- build release لـv7 وv8: **نجاح** في 2026-09-20.
- Firebase Test Lab المجدول: **فشل قبل الاختبارات** في خطوة `Setup Android SDK`؛
  لم يصل إلى assemble أو تشغيل الاختبارات على الجهاز، ولذلك لا يُعد فشلًا وظيفيًا
  مثبتًا، لكنه يعني أن دليل device E2E لذلك التشغيل مفقود.

روابط التشغيلات المرجعية:

- <https://github.com/appdevforall/CodeOnTheGo/actions/runs/35381663638>
- <https://github.com/appdevforall/CodeOnTheGo/actions/runs/35496985090>
- <https://github.com/appdevforall/CodeOnTheGo/actions/runs/35509715667>
- <https://github.com/appdevforall/CodeOnTheGo/actions/runs/35510468764>

### ما لم يمكن تشغيله محليًا أثناء هذه المراجعة

بيئة النقل لا تحتوي `flox` أو `java` أو Android SDK أو `adb`. لذلك لم يُشغّل Gradle
محليًا، ولم تُنفذ اختبارات device أو تحليل ديناميكي للشبكة/الصلاحيات. هذا قيد صريح
على المراجعة، ويعوضه جزئيًا نجاح CI على نفس SHA، لا كليًا.

## 9. مراجعة الأمن والخصوصية

### 9.1 نقاط جيدة مثبتة

- لم يعثر الفحص النصي على private keys أو tokens فعلية معروفة ضمن الملفات المتعقبة.
- `app/dev.keystore` وTermux test key مفاتيح تطوير عامة، وليستا مفاتيح release.
- مفاتيح Git تُشفّر بـAES-GCM ومفتاح Android Keystore.
- deep-link parser يعيد التحقق من scheme/host، يحد الطول، ويرفض traversal، ثم يتحقق
  من احتواء المسار فعليًا.
- unzip له اختبارات وحماية من zip-slip وsymlink escapes وحالات TOCTOU في المكوّن
  النهائي.
- تحديث إضافة موجودة يتطلب تطابق شهادة الإضافة القديمة والجديدة.
- telemetry لا يبدأ قبل موافقة صريحة، ويوجد خيار `Keep offline`.
- الخادم المحلي مربوط بـ`localhost` لا بـ`0.0.0.0`، وendpoint التنفيذي التجريبي
  معطل في الكود الحالي.

### 9.2 حدود الثقة والمخاطر المهمة

#### أ. الإضافات ليست sandbox — أولوية عالية

`PluginManager` يستخدم `DexClassLoader` داخل عملية التطبيق، ويمكنه تحميل `.so` أيضًا.
`PluginSecurityManager.checkClassAccess()` غير مستعمل خارج تعريفه، والتحقق من توقيع
الإضافة الجديدة يكتفي بالتحذير ثم **يكمل التحميل** إذا لم تكن موقعة. صلاحيات plugin
تُطبق داخل الخدمات الرسمية مثل file/command services، لكن bytecode الإضافة يعمل في
العملية نفسها ويملك parent classloader وAndroid APIs؛ لذلك لا تشكل تلك الصلاحيات
حدًا أمنيًا عامًا.

النتيجة العملية: يجب تثبيت إضافات من مصادر موثوقة فقط، وعرض تحذير واضح بأن `.cgp`
كود كامل الصلاحية. الخيارات الأقوى هي process isolation، allowlisted signed catalog،
وتطبيق توقيع إلزامي/ثقة ناشر، مع عدم تقديم permission list على أنها sandbox.

#### ب. خادم `localhost:6174` يكشف بيانات تشخيصية — أولوية عالية/متوسطة

`MainActivity` يبدأ الخادم دائمًا. لا توجد مصادقة للطلبات، وloopback في أندرويد ليس
خاصًا بالتطبيق: تطبيق محلي آخر يستطيع الاتصال به. المسار `/pr/pr` يعرض جدول recent
projects بما فيه الاسم و**المسار الكامل للمشروع**، كما تعرض مسارات تشخيصية أخرى
معلومات قواعد البيانات/التوثيق. تعليق الكود نفسه يقر بأن أي تطبيق على الجهاز يمكنه
GET لهذا المنفذ.

يوصى بإزالة endpoints التشخيصية من release، أو حمايتها بـnonce عشوائي، أو الاعتماد
على `DocumentationRequestInterceptor` داخل العملية بدل socket حيث أمكن.

#### ج. cleartext عام وtarget SDK قديم — أولوية متوسطة/عالية

الـmanifest و`network_security_config.xml` يسمحان cleartext على نحو عام، مع أن حالة
الاستخدام الرئيسية المعلنة هي loopback. كما أن التطبيق يترجم ضد SDK 36 لكنه يستهدف
SDK 28. قد يكون ذلك قرار توافق متعمدًا لـTermux/التخزين/التثبيت، لكنه:

- يحرم التطبيق من كثير من قيود وحمايات السلوك الحديث.
- يوسع أثر أي اتصال HTTP غير مقصود.
- يخلق مخاطر قبول في متاجر تفرض target SDK حديثًا.

يجب توثيق سبب target 28، اختبار خطة رفع تدريجية، وقصر cleartext على loopback أو
نطاقات محددة بدل base-config عام.

#### د. سطح صلاحيات واسع بطبيعة المنتج

التطبيق يطلب/يستخدم تخزينًا واسعًا، تثبيت الحزم، overlay، notifications، foreground
services، إنترنت، ويشغل Gradle وterminal وplugins وكود مشاريع المستخدم. هذا متوقع
لـIDE على الجهاز، لكنه يجعل threat model واختبارات إساءة الاستخدام أساسية، خصوصًا
عند الجمع بين ملفات من خارج التطبيق وإضافات وأوامر shell.

#### هـ. صياغة الخصوصية لا تطابق كل البيانات المرسلة بدقة

بوابة الموافقة موجودة وتعمل قبل تهيئة Firebase/GlitchTip. لكن disclosure يقول
`anonymous` و`No personal information`، بينما الكود يضع `ANDROID_ID` كمعرف مستخدم
ويرسل manufacturer/model ويضيف logs كـbreadcrumbs وmetrics تشخيصية. قد تكون هذه
بيانات pseudonymous لا اسمية، لكنها ليست مجهولة تمامًا بالمعنى الصارم.

يوصى بتحديث disclosure وسياسة الخصوصية وقائمة البيانات، وتقليل/تجزئة المعرف، ومراجعة
السجلات لمنع تسرب أسماء مشاريع أو مسارات أو أسرار مصدرية إلى breadcrumbs.

#### و. سلسلة توريد CI

جميع استخدامات GitHub Actions الخارجية تقريبًا مربوطة tags/branches لا commit SHA
ثابتًا، ومنها مرجع `@master`. كما تعتمد الأصول على خادم المؤسسة وMD5. يلزم pinning
بالـSHA، Dependabot/Renovate للـActions، وprovenance/SBOM للأصول والـAPK.

## 10. CI/CD وقابلية النقل إلى هذا المستودع

توجد 16 workflow، لكنها مبنية حول منظومة App Dev for All:

- self-hosted runners بتسميات خاصة.
- Firebase App Distribution/Test Lab وGoogle service account.
- مفاتيح signing عبر SSH/SCP.
- Jira وSlack وCrowdin وCloudflare وSonarCloud.
- أسرار وأسماء hosts/organizations خاصة بالمصدر.
- بعض workflows تنفذ merge تلقائيًا أو تنشر/توزع artifacts.

لذلك نقل الملفات لا يعني أن pipeline أصبح جاهزًا في LAB. أول push أثبت ذلك عمليًا:
فشل job النشر إلى Cloudflare وjob بصمة مفتاح التوقيع لغياب secrets، وبقي build
مجدولًا على runner غير موجود. أضيف بعد ذلك حارس لكل jobs المستوردة: تعمل تلقائيًا في
`appdevforall/CodeOnTheGo` فقط، وتبقى skipped في LAB وأي fork ما لم يضبط مسؤول
المستودع المتغير `COTG_ENABLE_UPSTREAM_WORKFLOWS=true`. التعليمات موجودة في
`.github/workflows/README.md`.

قبل تفعيل CI في هذا المستودع ينبغي:

1. إبقاء الحارس معطلًا إلى أن تُهيأ بيئة LAB، وعدم تفعيل المتغير كحل شكلي.
2. تحديد أقل `permissions:` ممكن لكل workflow.
3. استبدال self-hosted labels أو توفير runners معزولة.
4. تدوير/إنشاء secrets خاصة بالمستودع الهدف، وعدم نسخ أسرار المصدر.
5. فصل build verification عن distribution؛ يجب أن يعمل PR check بلا مفاتيح نشر.
6. تغيير إحداثيات repo/URLs داخل build logic إذا أصبح LAB forkًا مستقلًا رسميًا.

## 11. الترخيص والالتزامات القانونية

- الترخيص GPL-3.0-or-later.
- يجب إبقاء copyright/license notices.
- عند توزيع APK معدل يجب إتاحة corresponding source بالشروط الملائمة، بما يشمل
  التعديلات ومواد/سكربتات البناء اللازمة بالقدر الذي يطلبه GPL.
- وجود كود vendored وتبعيات كثيرة يعني ضرورة الحفاظ على Notices وتراخيص الطرف
  الثالث وعدم افتراض أن ملف GPL الوحيد يغطي كل attribution المطلوب.
- إذا عُدّل اسم/علامة المنتج، فالترخيص البرمجي لا يمنح تلقائيًا حقوق العلامة
  التجارية أو الهوية البصرية.

## 12. الانحرافات والدين التقني المثبت

### أولوية P0/P1

1. **`ARCHITECTURE.md` قديم**: ما زال يصف Gemini وllama وLayout Editor وCompose
   Preview وكأنها وحدات نواة.
2. **`E2E_TESTING_REPORT.md` قديم**: يصف AI flows وbroadcasts وملفات لم تعد موجودة.
3. **`:compose-preview` ما زال معلنًا** في `settings.gradle.kts` رغم حذف دليله عند
   نقله إلى plugin. Gradle يستطيع تمثيله كمشروع فارغ، لكن الإعلان لا يعكس الشجرة.
4. **بقايا llama**: `subprojects/llama.cpp` ليس vendor tree؛ لا يحتوي إلا
   `.gitmodules` فارغًا، بينما سكربتات التحديث وملف version ووثائق ما زالت موجودة.
5. **نصوص AI قديمة** ما زالت في `resources`, ومنها Gemini/GGUF/llama، رغم انتقال
   التنفيذ إلى plugins.
6. **مراجع keystore plugin قديمة** في `docs/plugin-api.md` و`REVIEW.md` بعد حذفه
   من هذه الشجرة ونقله/إعادة إنشائه خارجيًا.
7. **CODEOWNERS يحتوي LayoutEditor paths غير موجودة**.

### أولوية P2

8. `subprojects/project-serial` و`subprojects/project-serialization` لهما build
   scripts ولا يعلنان في root settings ولا توجد مراجع لهما؛ يلزم تقرير إبقائهما أو
   حذفهما.
9. `vectormaster/` مشروع مستقل غير معلن، بينما نسخة الكود المستخدمة موجودة داخل
   `xml-inflater`; يبدو كأثر ترحيل.
10. aliases وإصدارات مكررة في `libs.versions.toml` لبعض الإحداثيات، ما يزيد احتمالات
    version skew.
11. نحو 295 علامة TODO/FIXME/HACK في النطاق الأولي، وقرابة 45 في الاختبارات؛ العدد
    مؤشر جرد فقط، لا يعني أن كلها bugs.
12. ترجمة العربية وبقية عدة لغات أقل اكتمالًا بكثير من الملف الأساسي.
13. آخر GitHub release ظاهر (`26.38.1`) metadata-only بلا assets في GitHub؛ التوزيع
    الفعلي يعتمد أساسًا على Firebase/موقع المؤسسة، وهي نقطة استمرارية تشغيلية.

## 13. خطة عمل مقترحة حسب الأولوية

### المرحلة 0 — تثبيت النسخة المنقولة

- حماية فرع LAB وتحديد استراتيجية مزامنة واضحة مع `upstream/stage`.
- الإبقاء على حارس workflows المستوردة وعدم تفعيله حتى تهيئة secrets والرunners
  وتغيير وجهات النشر الخاصة بالمصدر.
- حفظ manifest للأصول الخارجية بإصدارات وSHA-256 ومصدر قابل للأرشفة.
- إنشاء build check مستقل لا يحتاج أسرار نشر.

### المرحلة 1 — إغلاق مخاطر الثقة

- توثيق أن plugin كود موثوق وليس sandbox، ثم جعل التوقيع إلزاميًا أو إضافة trust
  store/catalog، ودراسة process isolation.
- إغلاق `/pr/pr` وendpoints التشخيصية في release أو إضافة مصادقة عشوائية.
- تضييق cleartext ووضع خطة target SDK حديثة.
- مواءمة disclosure مع Android ID/device/log breadcrumbs الفعلية.

### المرحلة 2 — إزالة الانحراف

- تحديث `ARCHITECTURE.md` وE2E وplugin docs وCODEOWNERS.
- حذف include `:compose-preview` القديم بعد تأكيد عدم وجود مستهلك.
- تقرير مصير llama scripts/stub، serialization projects و`vectormaster/`.
- نقل أي تعليمات تخص الإضافات إلى `plugin-examples` أو ربطها صراحة به.

### المرحلة 3 — إثبات البناء والصحة

- تجهيز Java 17 + Android SDK/Flox في runner نظيف.
- تشغيل `spotlessCheck`, JVM tests, lint, v7/v8 debug builds.
- إصلاح Setup Android SDK في Firebase Test Lab ثم تشغيل E2E الفعلي.
- إنتاج SBOM وفحص dependencies/native artifacts.
- اختبار ديناميكي للـmanifest، loopback server، plugin install، deep links،
  DocumentsProvider، والتعامل مع archives.

### المرحلة 4 — تحسين الاستدامة

- تقليل اعتماد `:app` المباشر وتقوية حدود الوحدات.
- توحيد نسخ المكتبات والaliases.
- تحسين اكتمال الترجمة العربية وبقية اللغات.
- توثيق release reproducibility وsigning/recovery/runbooks بعيدًا عن معرفة أفراد
  المؤسسة.

## 14. أوامر صيانة المصدر

لرؤية الفرق مع المصدر لاحقًا:

```bash
git fetch upstream stage
git diff HEAD..upstream/stage --stat
```

لاستيعاب تحديث جديد يجب اختيار merge/rebase/cherry-pick وفق سياسة LAB، لا تكرار
`read-tree --reset` بصورة عمياء؛ الأمر الأخير يستبدل الشجرة وقد يمحو تعديلات LAB.

## 15. الحكم النهائي

Code on the Go مشروع ناضج وطموح، وقيمته المميزة أنه يجمع دورة تطوير أندرويد شبه
كاملة على جهاز أندرويد نفسه مع بنية plugins حديثة نسبيًا. الشفرة تُظهر اهتمامًا
واضحًا بحالات الحواف، path traversal، lifecycle، reproducibility، واختبارات دقيقة.

في المقابل، صعوبة تشغيله لا تأتي فقط من حجم الكود، بل من ثلاثة أمور مترابطة:

1. toolchain وأصول ضخمة خارج Git؛
2. pipeline مؤسسي عالي الارتباط بخدمات خاصة؛
3. انتقال سريع من monolith إلى plugins ترك وثائق وإعدادات وبقايا غير متزامنة.

النقل البرمجي تم بنجاح وبتطابق tree مثبت. قبل اعتبار LAB forkًا قابلًا للبناء والتوزيع
مستقلًا، الأولوية ليست إضافة مزايا جديدة، بل تثبيت الأصول وCI، تنظيف الانحراف، وإغلاق
حدود الثقة في plugins والخادم المحلي والـtelemetry disclosure.
